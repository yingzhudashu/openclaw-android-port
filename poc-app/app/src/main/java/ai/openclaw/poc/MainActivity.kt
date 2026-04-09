package ai.openclaw.poc

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Intent

/**
 * 主 Activity — 使用 ViewPager2 + TabLayout 管理 3 个页面
 *
 * Tab 结构：
 * - 💬 对话（ChatFragment）
 * - ⚙️ 设置（SettingsFragment）
 * - 📊 状态（StatusFragment）
 *
 * 引擎在 Activity 创建时自动启动。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var nodeRunner: NodeRunner
    private lateinit var webViewBridge: WebViewBridge
    private lateinit var deviceControlApi: DeviceControlApi

    // 日志和状态回调（供 Fragment 注册）
    private var logCallback: ((String) -> Unit)? = null
    private var stateCallback: ((NodeRunner.State) -> Unit)? = null

    // Tab 标题从资源加载
    private lateinit var tabTitles: Array<String>

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore dark mode preference
        val darkMode = getSharedPreferences("openclaw_prefs", 0).getBoolean("dark_mode", false)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (darkMode) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tabTitles = arrayOf(
            getString(R.string.tab_chat),
            getString(R.string.tab_settings),
            getString(R.string.tab_status),
            getString(R.string.tab_cron)
        )

        // 初始化 NodeRunner
        nodeRunner = NodeRunner(applicationContext)

        // 初始化 WebView 浏览器桥
        webViewBridge = WebViewBridge(applicationContext)
        webViewBridge.onLog = { message ->
            runOnUiThread {
                logCallback?.invoke(message)
            }
        }
        webViewBridge.init()
        webViewBridge.startServer()

        // 初始化设备控制 API (v1.3.0)
        deviceControlApi = DeviceControlApi(applicationContext)
        deviceControlApi.start()

        // 注册 NodeRunner 回调
        nodeRunner.onLog = { message ->
            runOnUiThread {
                logCallback?.invoke(message)
            }
        }

        nodeRunner.onStateChange = { state ->
            runOnUiThread {
                stateCallback?.invoke(state)
            }
        }

        // 设置 ViewPager2
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val adapter = ViewPagerAdapter(this)
        viewPager.adapter = adapter

        // 关联 TabLayout 和 ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        // 预加载所有页面，避免切换时重建
        viewPager.offscreenPageLimit = 2

        // 自动启动引擎
        startEngine()

        // Handle shortcut intents
        handleShortcutIntent(intent)

        // Request runtime permissions (location, camera, etc.)
        requestPermissions()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    /**
     * Request runtime permissions on first launch.
     * Android will show system permission dialogs; users can also manage via Settings.
     */
    private fun requestPermissions() {
        // Check which permissions are still needed
        val permissionsToRequest = mutableListOf<String>()

        // Location
        if (!PermissionManager.hasLocation(this)) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // Camera
        if (!PermissionManager.hasCamera(this)) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }
        // Notifications (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionManager.hasNotification(this)) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            Log.d("MainActivity", "Requested ${permissionsToRequest.size} permissions")
        } else {
            Log.d("MainActivity", "All permissions already granted")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.count { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            val total = grantResults.size
            Log.d("MainActivity", "Permission result: $granted/$total granted")
            // Permissions status is reflected in Settings page dynamically
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        when (intent?.action) {
            "ai.openclaw.poc.NEW_CHAT" -> {
                viewPager.currentItem = 0 // Switch to chat tab
                // ChatFragment will handle new session via its own logic
            }
            "ai.openclaw.poc.VOICE_INPUT" -> {
                viewPager.currentItem = 0
                // Signal to ChatFragment to start voice input
                shortcutAction = "voice"
            }
        }
    }

    var shortcutAction: String? = null

    /**
     * 启动引擎（供 Fragment 调用）
     */
    fun startEngine() {
        lifecycleScope.launch(Dispatchers.IO) {
            nodeRunner.start()
        }
        // Start foreground service to keep Gateway alive
        val serviceIntent = android.content.Intent(this, GatewayService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        // Initialize cron scheduler
        CronWorker.createNotificationChannel(this)
        CronWorker.scheduleAll(this)
    }

    /**
     * 停止引擎（供 Fragment 调用）
     */
    fun stopEngine() {
        nodeRunner.stop()
    }

    /**
     * 获取引擎状态（供 Fragment 调用）
     */
    fun getEngineState(): NodeRunner.State {
        return nodeRunner.state
    }

    /**
     * 注册日志回调（StatusFragment 使用）
     */
    fun registerLogCallback(callback: ((String) -> Unit)?) {
        logCallback = callback
    }

    /**
     * 注册状态变更回调（StatusFragment 使用）
     */
    fun registerStateCallback(callback: ((NodeRunner.State) -> Unit)?) {
        stateCallback = callback
    }

    override fun onDestroy() {
        super.onDestroy()
        webViewBridge.stop()
        deviceControlApi.stop()
        nodeRunner.stop()
    }
}
