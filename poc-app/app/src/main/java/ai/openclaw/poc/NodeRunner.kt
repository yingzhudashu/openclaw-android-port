package ai.openclaw.poc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * NodeRunner — Android 端 Node.js Gateway 管理器
 * 
 * 使用自研的 android-gateway.cjs（零依赖）
 * 通过 JNI 调用 libnode.so 启动 Node.js 进程
 */
class NodeRunner(private val context: Context) {

    companion object {
        private const val TAG = "NodeRunner"
        private const val ENGINE_ASSETS = "openclaw-engine"
        private const val ENGINE_DIR_NAME = "openclaw-engine"
        private const val DATA_DIR_NAME = "openclaw-data"
        private const val ENTRY_SCRIPT = "android-gateway.cjs"
        private const val HEALTH_URL = "http://127.0.0.1:18789/health"
        private const val HEALTH_TIMEOUT_MS = 10000

        init {
            System.loadLibrary("nodebridge")
        }
    }

    var onLog: ((String) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null

    @Volatile
    private var nodeThread: Thread? = null

    @Volatile
    var state: State = State.IDLE
        private set(value) {
            field = value
            onStateChange?.invoke(value)
        }

    enum class State { IDLE, STARTING, RUNNING, STOPPED, ERROR }

    suspend fun start() {
        if (state == State.RUNNING || state == State.STARTING) {
            log("Engine already running or starting")
            return
        }

        state = State.STARTING

        try {
            // Step 1: 解压 engine 脚本到 filesDir
            val engineDir = extractEngine()
            val entryScript = File(engineDir, ENTRY_SCRIPT)
            
            if (!entryScript.exists()) {
                log("ERROR: $ENTRY_SCRIPT not found at ${entryScript.absolutePath}")
                state = State.ERROR
                return
            }

            // Step 2: 准备数据目录
            val dataDir = File(context.filesDir, DATA_DIR_NAME).also { it.mkdirs() }
            File(dataDir, "config").mkdirs()
            File(dataDir, "state").mkdirs()
            File(dataDir, "workspace").mkdirs()
            File(dataDir, "sessions").mkdirs()

            log("Entry: ${entryScript.absolutePath}")
            log("Data: ${dataDir.absolutePath}")
            log("Size: ${entryScript.length() / 1024} KB")

            // Step 3: 在新线程中启动 Node.js
            nodeThread = Thread {
                try {
                    val exitCode = startNodeWithArguments(
                        arrayOf("node", entryScript.absolutePath),
                        engineDir.absolutePath,
                        true
                    )
                    log("Node.js exited with code $exitCode")
                    // exit code -2 means already started (C++ guard), treat as running
                    if (exitCode == -2) {
                        log("Node already initialized in this process, checking health...")
                    } else {
                        state = if (exitCode == 0) State.STOPPED else State.ERROR
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Node.js exception", e)
                    log("Exception: ${e.message}")
                    state = State.ERROR
                }
            }.apply {
                name = "NodeJS-Gateway"
                isDaemon = false
            }
            nodeThread?.start()

            state = State.RUNNING
            log("Gateway thread started!")
            delay(3000)

            // Start watchdog — auto-restart if Gateway dies
            startWatchdog()

        } catch (e: Exception) {
            log("Failed: ${e.message}")
            Log.e(TAG, "start() failed", e)
            state = State.ERROR
        }
    }

    private var watchdogJob: Job? = null
    private var restartCount = 0
    private val maxRestarts = 5

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(30000) // Check every 30 seconds
                if (state != State.RUNNING) continue
                val (healthy, _) = healthCheck()
                if (!healthy) {
                    log("Watchdog: Gateway unhealthy!")
                    if (restartCount < maxRestarts) {
                        restartCount++
                        log("Watchdog: Restarting (attempt $restartCount/$maxRestarts)...")
                        state = State.IDLE
                        try { start() } catch (e: Exception) {
                            log("Watchdog: Restart failed: ${e.message}")
                        }
                    } else {
                        log("Watchdog: Max restarts reached, giving up")
                        state = State.ERROR
                        break
                    }
                } else {
                    restartCount = 0 // Reset on successful health check
                }
            }
        }
    }

    fun stop() {
        log("Stopping...")
        watchdogJob?.cancel()
        watchdogJob = null
        nodeThread?.interrupt()
        nodeThread = null
        state = State.STOPPED
        log("Stopped")
    }

    suspend fun healthCheck(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL(HEALTH_URL)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = HEALTH_TIMEOUT_MS
            conn.readTimeout = HEALTH_TIMEOUT_MS
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val ok = code == 200
            log("Health: HTTP $code — $body")
            Pair(ok, "HTTP $code: $body")
        } catch (e: Exception) {
            val msg = "Health check failed: ${e.message}"
            log(msg)
            Pair(false, msg)
        }
    }

    private external fun startNodeWithArguments(
        arguments: Array<String>,
        modulesPath: String,
        redirectToLogcat: Boolean
    ): Int

    /**
     * 解压 assets/openclaw-engine/ 到 filesDir/openclaw-engine/
     * 支持递归子目录，版本变化时强制重新解压
     */
    private fun extractEngine(): File {
        val targetDir = File(context.filesDir, ENGINE_DIR_NAME)
        val versionFile = File(targetDir, ".version")
        val currentVersion = try {
            val configStream = context.assets.open("$ENGINE_ASSETS/openclaw.json")
            val configText = configStream.bufferedReader().readText()
            configStream.close()
            val versionMatch = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(configText)
            versionMatch?.groupValues?.get(1) ?: "unknown"
        } catch (_: Exception) { "unknown" }
        
        val existingVersion = if (versionFile.exists()) versionFile.readText().trim() else ""
        
        if (existingVersion == currentVersion && File(targetDir, ENTRY_SCRIPT).exists()) {
            log("Engine already extracted (v$currentVersion)")
            return targetDir
        }
        
        if (targetDir.exists()) {
            log("Version changed ($existingVersion -> $currentVersion), cleaning old engine...")
            targetDir.deleteRecursively()
        }

        targetDir.mkdirs()
        log("Extracting engine...")

        extractAssetDir(ENGINE_ASSETS, targetDir)

        File(targetDir, ".version").writeText(currentVersion)
        log("Engine extracted to ${targetDir.absolutePath} (v$currentVersion)")
        return targetDir
    }

    private fun extractAssetDir(assetPath: String, targetDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: emptyArray()
        
        targetDir.mkdirs()
        
        for (entry in entries) {
            val assetEntryPath = "$assetPath/$entry"
            val outFile = File(targetDir, entry)
            
            val subEntries = assetManager.list(assetEntryPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                extractAssetDir(assetEntryPath, outFile)
            } else {
                try {
                    assetManager.open(assetEntryPath).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    log("  Extracted: $entry (${outFile.length() / 1024} KB)")
                } catch (e: Exception) {
                    log("  Skip: $entry (${e.message})")
                }
            }
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }
}
