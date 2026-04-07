package ai.openclaw.poc

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            getString(R.string.tab_status)
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
    }

    /**
     * 启动引擎（供 Fragment 调用）
     */
    fun startEngine() {
        lifecycleScope.launch(Dispatchers.IO) {
            nodeRunner.start()
        }
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
        nodeRunner.stop()
    }
}
