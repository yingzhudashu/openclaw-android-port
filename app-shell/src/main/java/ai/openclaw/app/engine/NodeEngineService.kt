package ai.openclaw.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * NodeEngineService - Android 前台服务，管理 Node.js 引擎的完整生命周期
 *
 * 核心职责：
 * 1. 从 APK assets 解压 JS bundle 到 app data 目录
 * 2. 使用 ProcessBuilder 启动 libnode.so 执行 JS 入口
 * 3. 通过 HTTP /health 端点监控 Node.js 进程健康状态
 * 4. 崩溃自动重启（最多 MAX_RESTART_COUNT 次）
 * 5. 优雅停止（SIGTERM → 超时 → SIGKILL）
 */
class NodeEngineService : Service() {

    companion object {
        private const val TAG = "NodeEngineService"

        // 通知相关常量
        private const val CHANNEL_ID = "openclaw_engine"
        private const val NOTIFICATION_ID = 1001

        // Action 常量
        private const val ACTION_STOP = "ai.openclaw.app.engine.action.STOP"
        private const val ACTION_RESTART = "ai.openclaw.app.engine.action.RESTART"

        // 引擎配置
        private const val ENGINE_PORT = 19789
        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L
        private const val HEALTH_CHECK_TIMEOUT_MS = 3_000
        private const val MAX_RESTART_COUNT = 3
        private const val GRACEFUL_STOP_TIMEOUT_MS = 5_000L

        // 资源路径
        private const val JS_BUNDLE_ASSET = "openclaw-engine.zip"
        private const val JS_ENTRY_FILE = "android-engine-poc.cjs"
        private const val ENGINE_DIR_NAME = "openclaw-engine"
        private const val DATA_DIR_NAME = "openclaw-data"

        // 版本标记文件，用于判断是否需要重新解压
        private const val VERSION_FILE = ".bundle_version"

        /**
         * 启动 NodeEngineService 前台服务
         */
        fun start(context: Context) {
            val intent = Intent(context, NodeEngineService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * 停止 NodeEngineService
         */
        fun stop(context: Context) {
            val intent = Intent(context, NodeEngineService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Node.js 进程引用
    @Volatile
    private var nodeProcess: Process? = null

    // 健康检查 Job
    private var healthCheckJob: Job? = null

    // 进程监控 Job
    private var processMonitorJob: Job? = null

    // 日志收集 Job
    private var logCollectorJob: Job? = null

    // 重启计数器
    @Volatile
    private var restartCount = 0

    // 服务是否已启动前台
    private var didStartForeground = false

    // 引擎是否正在停止
    @Volatile
    private var isStopping = false

    // ========== Service 生命周期 ==========

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NodeEngineService onCreate")
        ensureNotificationChannel()

        // 立即启动前台通知，避免 ANR
        val notification = buildNotification(
            title = "OpenClaw Engine",
            text = "正在初始化..."
        )
        startForegroundCompat(notification)

        // 异步启动引擎
        scope.launch {
            startEngine()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "收到停止指令")
                scope.launch {
                    stopEngine()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                Log.i(TAG, "收到重启指令")
                scope.launch {
                    restartEngine()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "NodeEngineService onDestroy")
        // 同步清理：确保 Node 进程被终止
        scope.launch {
            stopEngine()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========== 引擎生命周期管理 ==========

    /**
     * 启动 Node.js 引擎
     * 步骤：解压 bundle → 启动进程 → 等待就绪 → 开始健康检查
     */
    private suspend fun startEngine() {
        try {
            isStopping = false
            EngineManager.updateState(EngineState.Starting)
            updateNotification("正在启动引擎...")

            // 1. 确保 JS bundle 已解压到 data 目录
            val engineDir = ensureJsBundleExtracted()
            val jsEntry = File(engineDir, JS_ENTRY_FILE)
            if (!jsEntry.exists()) {
                val errorMsg = "JS 入口文件不存在: ${jsEntry.absolutePath}"
                Log.e(TAG, errorMsg)
                EngineManager.updateState(EngineState.Error(errorMsg))
                updateNotification("启动失败: 缺少入口文件")
                return
            }

            // 2. 确保数据目录存在
            val dataDir = File(filesDir, DATA_DIR_NAME)
            dataDir.mkdirs()

            // 3. 获取 libnode.so 路径
            val libnodePath = "${applicationInfo.nativeLibraryDir}/libnode.so"
            val libnodeFile = File(libnodePath)
            if (!libnodeFile.exists()) {
                val errorMsg = "libnode.so 不存在: $libnodePath"
                Log.e(TAG, errorMsg)
                EngineManager.updateState(EngineState.Error(errorMsg))
                updateNotification("启动失败: 缺少 libnode.so")
                return
            }

            // 4. 构建进程环境变量
            val env = buildEnvironment(dataDir, engineDir)

            // 5. 启动 Node.js 进程
            Log.i(TAG, "启动 Node.js 进程: $libnodePath ${jsEntry.absolutePath}")
            val processBuilder = ProcessBuilder(libnodePath, jsEntry.absolutePath).apply {
                directory(engineDir)
                environment().putAll(env)
                redirectErrorStream(true) // 合并 stdout 和 stderr
            }

            val process = processBuilder.start()
            nodeProcess = process
            Log.i(TAG, "Node.js 进程已启动, PID: ${getProcessPid(process)}")

            // 6. 启动日志收集
            startLogCollector(process)

            // 7. 等待引擎就绪（轮询 /health 端点）
            val ready = waitForEngineReady()
            if (!ready) {
                if (!isStopping) {
                    val errorMsg = "引擎启动超时，未通过健康检查"
                    Log.e(TAG, errorMsg)
                    EngineManager.updateState(EngineState.Error(errorMsg))
                    updateNotification("启动超时")
                    handleEngineCrash()
                }
                return
            }

            // 8. 引擎就绪
            restartCount = 0 // 成功启动后重置重启计数
            EngineManager.updateState(EngineState.Running)
            updateNotification("引擎运行中 · 端口 $ENGINE_PORT")
            Log.i(TAG, "Node.js 引擎启动成功，监听端口 $ENGINE_PORT")

            // 9. 启动持续健康检查
            startHealthCheck()

            // 10. 启动进程退出监控
            startProcessMonitor(process)

        } catch (e: Exception) {
            val errorMsg = "引擎启动异常: ${e.message}"
            Log.e(TAG, errorMsg, e)
            EngineManager.updateState(EngineState.Error(errorMsg))
            updateNotification("启动异常")
            if (!isStopping) {
                handleEngineCrash()
            }
        }
    }

    /**
     * 优雅停止 Node.js 引擎
     * 策略：SIGTERM → 等待超时 → SIGKILL
     */
    private suspend fun stopEngine() {
        isStopping = true
        EngineManager.updateState(EngineState.Stopping)
        updateNotification("正在停止引擎...")

        // 取消所有监控任务
        healthCheckJob?.cancel()
        healthCheckJob = null
        processMonitorJob?.cancel()
        processMonitorJob = null
        logCollectorJob?.cancel()
        logCollectorJob = null

        val process = nodeProcess ?: run {
            EngineManager.updateState(EngineState.Stopped)
            return
        }

        try {
            // 第一步：发送 SIGTERM（优雅关闭）
            Log.i(TAG, "发送 SIGTERM 给 Node.js 进程")
            process.destroy()

            // 等待进程退出
            val exited = withContext(Dispatchers.IO) {
                waitForProcessExit(process, GRACEFUL_STOP_TIMEOUT_MS)
            }

            if (!exited) {
                // 第二步：超时后强制 SIGKILL
                Log.w(TAG, "Node.js 进程未在 ${GRACEFUL_STOP_TIMEOUT_MS}ms 内退出，执行 SIGKILL")
                process.destroyForcibly()
                withContext(Dispatchers.IO) {
                    waitForProcessExit(process, 2_000L)
                }
            }

            Log.i(TAG, "Node.js 进程已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止引擎时异常: ${e.message}", e)
            // 确保进程被终止
            try {
                process.destroyForcibly()
            } catch (_: Exception) {}
        } finally {
            nodeProcess = null
            EngineManager.updateState(EngineState.Stopped)
        }
    }

    /**
     * 重启引擎
     */
    private suspend fun restartEngine() {
        Log.i(TAG, "重启引擎...")
        stopEngine()
        delay(1_000) // 短暂等待端口释放
        isStopping = false
        startEngine()
    }

    /**
     * 处理引擎崩溃，尝试自动重启
     */
    private suspend fun handleEngineCrash() {
        if (isStopping) return

        restartCount++
        if (restartCount <= MAX_RESTART_COUNT) {
            Log.w(TAG, "引擎崩溃，尝试第 $restartCount/$MAX_RESTART_COUNT 次重启")
            EngineManager.updateState(EngineState.Error("崩溃重启中 ($restartCount/$MAX_RESTART_COUNT)"))
            updateNotification("崩溃重启中 ($restartCount/$MAX_RESTART_COUNT)")

            // 清理旧进程
            nodeProcess?.let { proc ->
                try {
                    proc.destroyForcibly()
                } catch (_: Exception) {}
            }
            nodeProcess = null

            delay(2_000L * restartCount) // 递增延迟重启
            if (!isStopping) {
                startEngine()
            }
        } else {
            val errorMsg = "引擎连续崩溃 $MAX_RESTART_COUNT 次，已停止自动重启"
            Log.e(TAG, errorMsg)
            EngineManager.updateState(EngineState.Error(errorMsg))
            updateNotification("引擎已停止 · 需要手动重启")
        }
    }

    // ========== JS Bundle 解压 ==========

    /**
     * 确保 JS bundle 已从 APK assets 解压到 app data 目录
     * 使用版本标记文件避免重复解压
     */
    private suspend fun ensureJsBundleExtracted(): File = withContext(Dispatchers.IO) {
        val engineDir = File(filesDir, ENGINE_DIR_NAME)
        val versionFile = File(engineDir, VERSION_FILE)

        // 获取当前 APK 版本作为 bundle 版本标识
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).let {
                "${it.versionName}-${it.longVersionCode}"
            }
        } catch (_: Exception) {
            "unknown"
        }

        // 检查是否需要重新解压
        val needsExtract = !engineDir.exists() ||
                !versionFile.exists() ||
                versionFile.readText().trim() != currentVersion

        if (needsExtract) {
            Log.i(TAG, "解压 JS bundle 到 ${engineDir.absolutePath}")
            updateNotification("正在解压引擎文件...")

            // 清理旧目录
            if (engineDir.exists()) {
                engineDir.deleteRecursively()
            }
            engineDir.mkdirs()

            try {
                // 尝试解压 zip 格式的 bundle
                assets.open(JS_BUNDLE_ASSET).use { inputStream ->
                    ZipInputStream(inputStream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val outFile = File(engineDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out ->
                                    zip.copyTo(out)
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                // 如果 zip 解压失败，尝试直接复制单个 CJS 文件
                Log.w(TAG, "Zip 解压失败，尝试直接复制入口文件: ${e.message}")
                try {
                    assets.open(JS_ENTRY_FILE).use { input ->
                        File(engineDir, JS_ENTRY_FILE).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "复制入口文件也失败: ${e2.message}")
                    throw e2
                }
            }

            // 写入版本标记
            versionFile.writeText(currentVersion)
            Log.i(TAG, "JS bundle 解压完成")
        } else {
            Log.d(TAG, "JS bundle 已是最新版本，跳过解压")
        }

        engineDir
    }

    // ========== 进程环境变量 ==========

    /**
     * 构建 Node.js 进程的环境变量
     */
    private fun buildEnvironment(dataDir: File, engineDir: File): Map<String, String> {
        return mapOf(
            // OpenClaw 数据目录
            "OPENCLAW_ANDROID_DATA_DIR" to dataDir.absolutePath,
            // Node.js 模块搜索路径
            "NODE_PATH" to "${engineDir.absolutePath}/node_modules",
            // 引擎监听端口
            "OPENCLAW_ENGINE_PORT" to ENGINE_PORT.toString(),
            // Android 特定标记
            "OPENCLAW_PLATFORM" to "android",
            // 禁用 Node.js 颜色输出（避免日志乱码）
            "NO_COLOR" to "1",
            // tmpdir 指向 app cache
            "TMPDIR" to cacheDir.absolutePath,
            // HOME 目录指向 data
            "HOME" to dataDir.absolutePath,
        )
    }

    // ========== 健康检查 ==========

    /**
     * 等待引擎就绪（最多等 30 秒）
     */
    private suspend fun waitForEngineReady(): Boolean {
        val maxWaitMs = 30_000L
        val checkIntervalMs = 500L
        var elapsed = 0L

        while (elapsed < maxWaitMs && !isStopping) {
            if (checkHealth()) {
                return true
            }
            delay(checkIntervalMs)
            elapsed += checkIntervalMs

            // 检查进程是否还活着
            val process = nodeProcess
            if (process != null && !isProcessAlive(process)) {
                Log.e(TAG, "Node.js 进程在启动阶段意外退出")
                return false
            }
        }
        return false
    }

    /**
     * 启动持续健康检查
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            var consecutiveFailures = 0
            val maxConsecutiveFailures = 3

            while (isActive && !isStopping) {
                delay(HEALTH_CHECK_INTERVAL_MS)

                if (checkHealth()) {
                    if (consecutiveFailures > 0) {
                        Log.i(TAG, "健康检查恢复正常")
                        consecutiveFailures = 0
                        EngineManager.updateState(EngineState.Running)
                        updateNotification("引擎运行中 · 端口 $ENGINE_PORT")
                    }
                } else {
                    consecutiveFailures++
                    Log.w(TAG, "健康检查失败 ($consecutiveFailures/$maxConsecutiveFailures)")

                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        Log.e(TAG, "连续 $maxConsecutiveFailures 次健康检查失败，判定引擎异常")
                        handleEngineCrash()
                        break
                    }
                }
            }
        }
    }

    /**
     * 执行单次健康检查：GET http://localhost:ENGINE_PORT/health
     */
    private suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://127.0.0.1:$ENGINE_PORT/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = HEALTH_CHECK_TIMEOUT_MS
            conn.readTimeout = HEALTH_CHECK_TIMEOUT_MS
            conn.requestMethod = "GET"

            val responseCode = conn.responseCode
            conn.disconnect()

            responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    // ========== 进程监控 ==========

    /**
     * 监控 Node.js 进程退出事件
     */
    private fun startProcessMonitor(process: Process) {
        processMonitorJob?.cancel()
        processMonitorJob = scope.launch(Dispatchers.IO) {
            try {
                val exitCode = process.waitFor()
                Log.w(TAG, "Node.js 进程退出，exitCode=$exitCode")

                if (!isStopping) {
                    // 非主动停止的退出视为崩溃
                    EngineManager.updateState(EngineState.Error("进程退出 (code=$exitCode)"))
                    nodeProcess = null
                    handleEngineCrash()
                }
            } catch (_: InterruptedException) {
                // 被中断，忽略
            }
        }
    }

    /**
     * 收集 Node.js 进程的 stdout/stderr 日志
     */
    private fun startLogCollector(process: Process) {
        logCollectorJob?.cancel()
        logCollectorJob = scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && isActive) {
                        Log.d("NodeEngine", line ?: "")
                    }
                }
            } catch (_: Exception) {
                // 进程关闭时流会断开，忽略
            }
        }
    }

    // ========== 通知管理 ==========

    /**
     * 创建通知渠道
     */
    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenClaw Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "OpenClaw Node.js 引擎运行状态"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建前台通知
     */
    private fun buildNotification(title: String, text: String): Notification {
        // 点击通知打开主界面
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val launchPending = launchIntent?.let {
            PendingIntent.getActivity(
                this, 1, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // 停止按钮
        val stopIntent = Intent(this, NodeEngineService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 使用系统图标作为后备
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(launchPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "停止引擎", stopPending)
            .build()
    }

    /**
     * 更新前台通知内容
     */
    private fun updateNotification(text: String) {
        val notification = buildNotification("OpenClaw Engine", text)
        if (didStartForeground) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            startForegroundCompat(notification)
        }
    }

    /**
     * 兼容启动前台服务（指定 foregroundServiceType）
     */
    private fun startForegroundCompat(notification: Notification) {
        if (didStartForeground) return
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        didStartForeground = true
    }

    // ========== 工具方法 ==========

    /**
     * 获取进程 PID（用于日志）
     */
    private fun getProcessPid(process: Process): Long {
        return try {
            process.pid().toLong()
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * 检查进程是否还活着
     */
    private fun isProcessAlive(process: Process): Boolean {
        return try {
            process.isAlive
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 等待进程退出，带超时
     * @return true 如果进程在超时前退出
     */
    private fun waitForProcessExit(process: Process, timeoutMs: Long): Boolean {
        return try {
            process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
    }
}
