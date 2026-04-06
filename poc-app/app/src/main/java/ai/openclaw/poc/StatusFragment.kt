package ai.openclaw.poc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class StatusFragment : Fragment() {

    companion object {
        private const val BASE_URL = "http://127.0.0.1:18789"
        private const val BROWSER_URL = "http://127.0.0.1:18790"
    }

    // 引擎状态
    private lateinit var tvEngineSummary: TextView
    private lateinit var tvEngineState: TextView
    private lateinit var tvNodeVersion: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvMemoryUsage: TextView
    private lateinit var tvSessions: TextView

    // 摘要
    private lateinit var tvToolsSummary: TextView
    private lateinit var tvSkillsSummary: TextView
    private lateinit var tvMemorySummary: TextView
    private lateinit var tvWorkspaceSummary: TextView
    private lateinit var tvBrowserSummary: TextView
    private lateinit var tvLogsSummary: TextView

    // 详情
    private lateinit var tvToolsTitle: TextView
    private lateinit var tvToolsList: TextView
    private lateinit var tvSkillsTitle: TextView
    private lateinit var tvSkillsList: TextView
    private lateinit var tvMemoryContent: TextView
    private lateinit var tvWorkspaceFiles: TextView
    private lateinit var tvBrowserUrl: TextView
    private lateinit var tvBrowserTitle: TextView
    private lateinit var tvBrowserPort: TextView
    private lateinit var tvBrowserEngine: TextView
    private lateinit var tvBrowserRequests: TextView
    private lateinit var tvBrowserActivity: TextView
    private lateinit var tvLogs: TextView

    // 按钮
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnClearEngine: MaterialButton

    // 折叠状态
    private data class Section(val header: View, val detail: View, val arrow: TextView, var expanded: Boolean)
    private val sections = mutableListOf<Section>()

    // 日志
    private val logBuffer = StringBuilder()

    // 自动刷新
    private val autoRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var browserSectionExpanded = false
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (isAdded && view != null) {
                loadEngineStatus()
                loadBrowserStatus()
            }
            // Faster polling when browser section is expanded
            val interval = if (browserSectionExpanded) 1500L else 5000L
            autoRefreshHandler.postDelayed(this, interval)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_status, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 引擎状态
        tvEngineSummary = view.findViewById(R.id.tvEngineSummary)
        tvEngineState = view.findViewById(R.id.tvEngineState)
        tvNodeVersion = view.findViewById(R.id.tvNodeVersion)
        tvUptime = view.findViewById(R.id.tvUptime)
        tvMemoryUsage = view.findViewById(R.id.tvMemoryUsage)
        tvSessions = view.findViewById(R.id.tvSessions)

        // 摘要
        tvToolsSummary = view.findViewById(R.id.tvToolsSummary)
        tvSkillsSummary = view.findViewById(R.id.tvSkillsSummary)
        tvMemorySummary = view.findViewById(R.id.tvMemorySummary)
        tvWorkspaceSummary = view.findViewById(R.id.tvWorkspaceSummary)
        tvBrowserSummary = view.findViewById(R.id.tvBrowserSummary)
        tvLogsSummary = view.findViewById(R.id.tvLogsSummary)

        // 详情
        tvToolsTitle = view.findViewById(R.id.tvToolsTitle)
        tvToolsList = view.findViewById(R.id.tvToolsList)
        tvSkillsTitle = view.findViewById(R.id.tvSkillsTitle)
        tvSkillsList = view.findViewById(R.id.tvSkillsList)
        tvMemoryContent = view.findViewById(R.id.tvMemoryContent)
        tvWorkspaceFiles = view.findViewById(R.id.tvWorkspaceFiles)
        tvBrowserUrl = view.findViewById(R.id.tvBrowserUrl)
        tvBrowserTitle = view.findViewById(R.id.tvBrowserTitle)
        tvBrowserPort = view.findViewById(R.id.tvBrowserPort)
        tvBrowserEngine = view.findViewById(R.id.tvBrowserEngine)
        tvBrowserRequests = view.findViewById(R.id.tvBrowserRequests)
        tvBrowserActivity = view.findViewById(R.id.tvBrowserActivity)
        tvLogs = view.findViewById(R.id.tvLogs)

        // 按钮
        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnClearEngine = view.findViewById(R.id.btnClearEngine)
        btnRefresh.setOnClickListener { refreshAll() }
        btnClearEngine.setOnClickListener { clearEngineCache() }

        // 注册折叠 sections
        fun sec(headerId: Int, detailId: Int, arrowId: Int, expanded: Boolean): Section {
            val header = view.findViewById<View>(headerId)
            val detail = view.findViewById<View>(detailId)
            val arrow = view.findViewById<TextView>(arrowId)
            detail.visibility = if (expanded) View.VISIBLE else View.GONE
            arrow.text = if (expanded) "▲" else "▼"
            val section = Section(header, detail, arrow, expanded)
            header.setOnClickListener { toggleSection(section) }
            return section
        }

        sections.add(sec(R.id.headerEngine,    R.id.detailEngine,    R.id.tvEngineArrow,    true))
        sections.add(sec(R.id.headerTools,      R.id.detailTools,     R.id.tvToolsArrow,     false))
        sections.add(sec(R.id.headerSkills,     R.id.detailSkills,    R.id.tvSkillsArrow,    false))
        sections.add(sec(R.id.headerMemory,     R.id.detailMemory,    R.id.tvMemoryArrow,    false))
        sections.add(sec(R.id.headerWorkspace,  R.id.detailWorkspace, R.id.tvWorkspaceArrow, false))
        sections.add(sec(R.id.headerBrowser,    R.id.detailBrowser,   R.id.tvBrowserArrow,   false))
        sections.add(sec(R.id.headerLogs,       R.id.detailLogs,      R.id.tvLogsArrow,      false))

        // 日志回调
        (activity as? MainActivity)?.registerLogCallback { msg ->
            requireActivity().runOnUiThread { appendLog(msg) }
        }

        // 加载数据
        refreshAll()
        autoRefreshHandler.postDelayed(autoRefreshRunnable, 5000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        (activity as? MainActivity)?.registerLogCallback(null)
    }

    private fun toggleSection(section: Section) {
        section.expanded = !section.expanded
        section.detail.visibility = if (section.expanded) View.VISIBLE else View.GONE
        section.arrow.text = if (section.expanded) "▲" else "▼"
        // Track browser section for faster polling
        if (sections.size > 5 && section === sections[5]) {
            browserSectionExpanded = section.expanded
            if (section.expanded) loadBrowserStatus() // Immediate refresh
        }
    }

    private fun refreshAll() {
        loadEngineStatus()
        loadTools()
        loadMemory()
        loadWorkspace()
        loadBrowserStatus()
    }

    private fun appendLog(message: String) {
        logBuffer.appendLine(message)
        if (logBuffer.length > 10000) logBuffer.delete(0, logBuffer.length - 8000)
        tvLogs.text = logBuffer.toString()
        tvLogsSummary.text = message.take(30)
    }

    // ─── Normalize engine status ─────────────────────────────────────────

    private fun normalizeStatus(raw: String): Triple<String, String, Int> {
        // Returns: (display text, status key, color)
        return when (raw.lowercase().trim()) {
            "ok", "running", "healthy" -> Triple(
                getString(R.string.status_running), "running", 0xFF4CAF50.toInt()
            )
            "starting", "initializing", "booting" -> Triple(
                getString(R.string.status_starting), "starting", 0xFFFF9800.toInt()
            )
            "error", "failed", "crashed" -> Triple(
                getString(R.string.status_error), "error", 0xFFF44336.toInt()
            )
            "stopped", "not_running", "offline" -> Triple(
                getString(R.string.status_not_running), "stopped", 0xFFF44336.toInt()
            )
            else -> Triple("● $raw", raw, 0xFF9E9E9E.toInt())
        }
    }

    // ─── Data Loaders ────────────────────────────────────────────────────

    private fun loadEngineStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/status") }
                val rawStatus = resp.optString("status", "unknown")
                val (displayText, _, color) = normalizeStatus(rawStatus)

                tvEngineSummary.text = displayText
                tvEngineSummary.setTextColor(color)

                tvEngineState.text = getString(R.string.status_state, displayText)
                tvNodeVersion.text = getString(R.string.status_version, resp.optString("version", "?"))

                val uptime = resp.optLong("uptime", resp.optLong("uptime_seconds", 0))
                tvUptime.text = getString(R.string.status_uptime, uptime / 3600, (uptime % 3600) / 60)

                val memObj = resp.optJSONObject("memory")
                val memMb = if (memObj != null) {
                    memObj.optDouble("rss", 0.0) / 1024.0 / 1024.0
                } else {
                    resp.optDouble("memory_mb", 0.0)
                }
                tvMemoryUsage.text = getString(R.string.status_memory, memMb)
                tvSessions.text = getString(R.string.status_sessions, resp.optInt("sessions", resp.optInt("session_count", 0)))

            } catch (_: Exception) {
                val (displayText, _, color) = normalizeStatus("not_running")
                tvEngineState.text = getString(R.string.status_not_connected)
                tvEngineSummary.text = displayText
                tvEngineSummary.setTextColor(color)
            }
        }
    }

    private fun loadTools() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/tools") }
                val arr = resp.optJSONArray("tools")

                val coreTools = mutableListOf<Pair<String, String>>()
                val skillTools = mutableListOf<Pair<String, String>>()

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i)
                        val name = t.optString("name", "?")
                        val desc = t.optString("description", "").take(40)
                        val category = t.optString("category", "core")
                        if (category == "skill") {
                            skillTools.add(name to desc)
                        } else {
                            coreTools.add(name to desc)
                        }
                    }
                }

                // Core tools
                tvToolsSummary.text = getString(R.string.n_items, coreTools.size)
                tvToolsTitle.text = getString(R.string.status_core_tools_registered, coreTools.size)
                tvToolsList.text = coreTools.joinToString("\n") { "• ${it.first} — ${it.second}" }

                // Skills (from tools + from /api/skills)
                loadSkills(skillTools)

            } catch (_: Exception) {
                tvToolsSummary.text = getString(R.string.n_items, 0)
                tvSkillsSummary.text = getString(R.string.n_items, 0)
            }
        }
    }

    private fun loadSkills(toolSkills: List<Pair<String, String>> = emptyList()) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/skills") }
                val skillsArr = resp.optJSONArray("skills")

                val sb = StringBuilder()
                var totalCount = 0

                // Preset skills (from tools that are actually skills)
                if (toolSkills.isNotEmpty()) {
                    sb.appendLine("── ${getString(R.string.status_skills_preset)} ──")
                    for ((name, desc) in toolSkills) {
                        sb.appendLine("• $name — $desc")
                        totalCount++
                    }
                }

                // Custom / installed skills
                if (skillsArr != null && skillsArr.length() > 0) {
                    if (sb.isNotEmpty()) sb.appendLine()
                    sb.appendLine("── ${getString(R.string.status_skills_custom)} ──")
                    for (i in 0 until skillsArr.length()) {
                        val s = skillsArr.getJSONObject(i)
                        val name = s.optString("name", "?")
                        val desc = s.optString("description", "").take(40)
                        sb.appendLine("• $name — $desc")
                        totalCount++
                    }
                }

                tvSkillsSummary.text = getString(R.string.n_items, totalCount)
                tvSkillsTitle.text = getString(R.string.status_skills_loaded, totalCount)
                tvSkillsList.text = if (sb.isNotEmpty()) sb.toString().trim() else getString(R.string.settings_no_skills)

            } catch (_: Exception) {
                val count = toolSkills.size
                tvSkillsSummary.text = getString(R.string.n_items, count)
                if (toolSkills.isNotEmpty()) {
                    tvSkillsList.text = toolSkills.joinToString("\n") { "• ${it.first} — ${it.second}" }
                }
            }
        }
    }

    private fun loadMemory() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/memory") }
                val content = resp.optString("content", "")
                tvMemoryContent.text = if (content.isEmpty()) getString(R.string.empty_content) else content.take(2000)
                tvMemorySummary.text = if (content.isEmpty()) getString(R.string.status_memory_empty) else getString(R.string.status_memory_loaded)
            } catch (_: Exception) {
                tvMemorySummary.text = getString(R.string.status_memory_not_loaded)
            }
        }
    }

    private fun loadWorkspace() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/workspace") }
                val arr = resp.optJSONArray("files")
                val wsPath = resp.optString("path", "")
                if (arr != null) {
                    tvWorkspaceSummary.text = getString(R.string.status_workspace_files, arr.length())
                    val sb = StringBuilder()
                    if (wsPath.isNotEmpty()) sb.appendLine("📂 $wsPath\n")
                    for (i in 0 until arr.length()) {
                        val filePath = arr.getString(i)
                        val icon = when {
                            filePath.endsWith(".md") -> "📝"
                            filePath.endsWith(".json") -> "⚙️"
                            filePath.endsWith(".txt") -> "📄"
                            filePath.contains("/") -> "📁"
                            else -> "📎"
                        }
                        sb.appendLine("$icon $filePath")
                    }
                    tvWorkspaceFiles.text = sb.toString().trim()
                } else {
                    tvWorkspaceSummary.text = getString(R.string.status_workspace_files, 0)
                }
            } catch (_: Exception) {
                tvWorkspaceSummary.text = getString(R.string.status_unknown)
            }
        }
    }

    private fun loadBrowserStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { httpGet("$BROWSER_URL/browser/activity?limit=10") }
                val browserUrl = resp.optString("url", "about:blank")
                val title = resp.optString("title", "")
                val loading = resp.optBoolean("loading", false)
                val requests = resp.optLong("requests", 0)
                val activities = resp.optJSONArray("activities")

                // Summary status with loading indicator
                val (summaryText, summaryColor) = when {
                    loading -> getString(R.string.status_browser_loading) to 0xFFFF9800.toInt()
                    browserUrl != "about:blank" -> getString(R.string.status_browser_ready) to 0xFF4CAF50.toInt()
                    else -> getString(R.string.status_browser_stopped) to 0xFF9E9E9E.toInt()
                }
                tvBrowserSummary.text = summaryText
                tvBrowserSummary.setTextColor(summaryColor)

                // Details
                tvBrowserUrl.text = getString(R.string.status_browser_url, browserUrl)
                tvBrowserTitle.text = getString(R.string.status_browser_title, title.ifEmpty { "-" })
                tvBrowserPort.text = getString(R.string.status_browser_port, WebViewBridge.PORT)
                tvBrowserEngine.text = getString(R.string.status_browser_engine)
                tvBrowserRequests.text = getString(R.string.status_browser_requests, requests)

                // Activity log
                if (activities != null && activities.length() > 0) {
                    val sb = StringBuilder()
                    val now = System.currentTimeMillis()
                    for (i in 0 until activities.length()) {
                        val act = activities.getJSONObject(i)
                        val time = act.optLong("time", 0)
                        val action = act.optString("action", "?")
                        val detail = act.optString("detail", "")
                        val ago = formatTimeAgo(now - time)
                        val icon = when (action) {
                            "navigate" -> "🌐"
                            "page_start" -> "⏳"
                            "page_loaded" -> "✅"
                            "eval" -> "⚡"
                            "click" -> "👆"
                            "type" -> "⌨️"
                            "content" -> "📄"
                            "screenshot" -> "📸"
                            "error" -> "❌"
                            else -> "●"
                        }
                        sb.appendLine("$icon $action  $ago")
                        if (detail.isNotEmpty()) sb.appendLine("   ${detail.take(50)}")
                    }
                    tvBrowserActivity.text = sb.toString().trim()
                } else {
                    tvBrowserActivity.text = getString(R.string.status_browser_no_activity)
                }

            } catch (_: Exception) {
                tvBrowserSummary.text = getString(R.string.status_browser_stopped)
                tvBrowserSummary.setTextColor(0xFF9E9E9E.toInt())
                tvBrowserUrl.text = getString(R.string.status_browser_url, "-")
                tvBrowserTitle.text = getString(R.string.status_browser_title, "-")
                tvBrowserPort.text = getString(R.string.status_browser_port, WebViewBridge.PORT)
                tvBrowserEngine.text = getString(R.string.status_browser_engine)
                tvBrowserRequests.text = getString(R.string.status_browser_requests, 0)
                tvBrowserActivity.text = getString(R.string.status_browser_no_activity)
            }
        }
    }

    private fun formatTimeAgo(ms: Long): String {
        return when {
            ms < 1000 -> "just now"
            ms < 60_000 -> "${ms / 1000}s ago"
            ms < 3600_000 -> "${ms / 60_000}m ago"
            else -> "${ms / 3600_000}h ago"
        }
    }

    private fun clearEngineCache() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_clear_cache))
            .setMessage(getString(R.string.settings_clear_cache_msg))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                try {
                    val vf = java.io.File(requireContext().filesDir, "openclaw-engine/.version")
                    if (vf.exists()) vf.delete()
                    appendLog(getString(R.string.status_cache_cleared))
                } catch (e: Exception) {
                    appendLog(getString(R.string.status_cache_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─── HTTP ────────────────────────────────────────────────────────────

    private fun httpGet(urlStr: String): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.readTimeout = 5000
        val code = conn.responseCode
        val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code")
        return JSONObject(body)
    }
}
