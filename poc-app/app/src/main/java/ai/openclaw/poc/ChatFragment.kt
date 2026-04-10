package ai.openclaw.poc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class ChatFragment : Fragment() {

    companion object {
        private const val BASE_URL = "http://127.0.0.1:18789"
    }

    // ─── Views ───────────────────────────────────────────────────────────────
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var fabSend: FloatingActionButton
    private lateinit var searchBar: View
    private lateinit var etSearch: EditText
    private lateinit var tvSessionId: TextView
    private lateinit var tvSessionTitle: TextView
    private lateinit var tvToolCount: TextView
    private lateinit var btnNewSession: ImageButton
    private lateinit var btnSessionList: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var tvNetworkBanner: TextView

    // ─── State ───────────────────────────────────────────────────────────────
    private val messageAdapter = MessageAdapter()
    private var sessionId: String = ""
    private var currentSessionTitle: String = ""
    private var currentModel: String = "qwen3.5-plus"
    private var currentProvider: String = "bailian"
    private var isSending = false
    private var pendingFileName: String? = null
    private var pendingFileContent: String? = null
    // 原始图片 base64（发送给模型用）
    private var pendingOriginalImageBase64: String? = null
    // Network monitoring
    private var isNetworkAvailable = true
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // Gateway health check
    private val healthCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var healthFailCount = 0
    private var isEngineHealthy = true

    // Fix #5: Use a flag to prevent rescheduling after view is destroyed
    @Volatile
    private var healthCheckRunning = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (!healthCheckRunning) return
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resp = withContext(Dispatchers.IO) { httpGet("$BASE_URL/health") }
                    healthFailCount = 0
                    if (!isEngineHealthy) {
                        isEngineHealthy = true
                    }
                } catch (_: Exception) {
                    healthFailCount++
                    if (healthFailCount >= 3 && isEngineHealthy) {
                        isEngineHealthy = false
                        Toast.makeText(context, getString(R.string.engine_disconnected), Toast.LENGTH_LONG).show()
                    }
                }
            }
            // Only reschedule if still running
            if (healthCheckRunning) {
                healthCheckHandler.postDelayed(this, 30000)
            }
        }
    }

    private fun isRetryableException(e: Exception): Boolean {
        return e is java.net.ConnectException ||
               e is java.net.SocketTimeoutException ||
               e is java.net.UnknownHostException ||
               (e.cause is java.net.ConnectException) ||
               (e.cause is java.net.SocketTimeoutException) ||
               (e.cause is java.net.UnknownHostException)
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvMessages = view.findViewById(R.id.rvMessages)
        etMessage = view.findViewById(R.id.etMessage)
        fabSend = view.findViewById(R.id.fabSend)
        tvSessionId = view.findViewById(R.id.tvSessionId)
        tvSessionTitle = view.findViewById(R.id.tvSessionTitle)
        tvToolCount = view.findViewById(R.id.tvToolCount)
        btnNewSession = view.findViewById(R.id.btnNewSession)
        btnSessionList = view.findViewById(R.id.btnSessionList)
        btnAttach = view.findViewById(R.id.btnAttach)

        rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        rvMessages.adapter = messageAdapter

        fabSend.setOnClickListener { sendMessage() }

        // Tap model name to switch model
        tvToolCount.setOnClickListener { showModelPicker() }

        // 动态切换：空→语音，有字→发送
        fabSend.alpha = 0.4f
        etMessage.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                fabSend.alpha = if (hasText) 1.0f else 0.4f
            }
        })
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        btnSessionList.setOnClickListener { showSessionList() }
        btnNewSession.setOnClickListener { showNewSessionPicker() }
        btnAttach.setOnClickListener { showAttachOptions() }

        // Network status banner
        tvNetworkBanner = view.findViewById(R.id.tvNetworkBanner)
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activity?.runOnUiThread {
                    isNetworkAvailable = true
                    tvNetworkBanner.animate().alpha(0f).setDuration(300).withEndAction {
                        tvNetworkBanner.visibility = View.GONE
                        tvNetworkBanner.alpha = 1f
                    }.start()
                }
            }
            override fun onLost(network: Network) {
                activity?.runOnUiThread {
                    isNetworkAvailable = false
                    tvNetworkBanner.visibility = View.VISIBLE
                }
            }
        }
        cm.registerNetworkCallback(netRequest, networkCallback!!)
        val activeNet = cm.activeNetwork
        val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        isNetworkAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!isNetworkAvailable) tvNetworkBanner.visibility = View.VISIBLE

        // Retry callback for failed messages
        messageAdapter.onRetryClick = { position, message ->
            messageAdapter.removeMessageAt(position)
            if (messageAdapter.getMessageCount() > position) {
                val nextMsg = messageAdapter.getMessageAt(position)
                if (nextMsg != null && !nextMsg.isUser && nextMsg.content.startsWith("\u274c")) {
                    messageAdapter.removeMessageAt(position)
                }
            }
            // Fix #7/#8: Restore pending file/image from snapshot saved in ChatMessage, then auto-send
            if (message.retryFileContent != null) {
                pendingFileName = message.fileName
                pendingFileContent = message.retryFileContent
            }
            if (message.retryOriginalImageBase64 != null) {
                pendingOriginalImageBase64 = message.retryOriginalImageBase64
            }
            etMessage.setText(message.content)
            sendMessage()
        }

        // TTS initialization
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                Log.d("TTS_TEST", "TTS engine ready")
            }
        }

        messageAdapter.onTtsClick = { text, position -> handleTtsClick(text, position) }


        // Start gateway health check
        healthCheckRunning = true  // Fix #5: Enable health check
        healthCheckHandler.postDelayed(healthCheckRunnable, 10000)

        fetchToolCount()

        // Check if we're recovering from a language change
        val prefs = requireContext().getSharedPreferences("openclaw_prefs", 0)
        val languageChanged = prefs.getBoolean("language_just_changed", false)
        val savedSessionId = prefs.getString("current_session_id", "") ?: ""
        val savedSessionTitle = prefs.getString("current_session_title", "") ?: ""

        if (languageChanged && savedSessionId.isNotEmpty()) {
            // Restore previous session after language switch
            prefs.edit().putBoolean("language_just_changed", false).apply()
            sessionId = savedSessionId
            currentSessionTitle = savedSessionTitle
            updateTopBar()
            fetchModelInfo()
            loadSessionHistory(savedSessionId)
        } else {
            prefs.edit().putBoolean("language_just_changed", false).apply()
            // Await model+provider info before creating first session
            viewLifecycleOwner.lifecycleScope.launch {
                fetchModelInfoSync()
                val success = createNewSession()
                if (success) {
                    val welcomeMsg = getString(R.string.chat_welcome)
                    messageAdapter.addMessage(ChatMessage(content = welcomeMsg, isUser = false, model = "", sessionId = sessionId))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.stop()
        tts?.shutdown()
        tts = null
        // Fix #9: Disable health check to prevent rescheduling
        healthCheckRunning = false
        networkCallback?.let {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        networkCallback = null
        healthCheckHandler.removeCallbacks(healthCheckRunnable)
    }

    // ─── Top Bar ─────────────────────────────────────────────────────────────

    private fun updateTopBar() {
        tvSessionId.text = "#${if (sessionId.isBlank()) "--------" else sessionId.take(8)}"
        tvSessionTitle.text = currentSessionTitle
        // Persist current session for recovery (e.g. language switch)
        context?.getSharedPreferences("openclaw_prefs", 0)?.edit()
            ?.putString("current_session_id", sessionId)
            ?.putString("current_session_title", currentSessionTitle)
            ?.apply()
    }

    // ─── Session Management ──────────────────────────────────────────────────

    // Fix #1: Return Boolean to signal success/failure
    private suspend fun createNewSession(agentName: String? = null, systemPrompt: String? = null): Boolean {
        // 引擎可能还没启动，最多重试 3 次（间隔 2s）
        var retries = 3
        while (retries > 0) {
            try {
                val data = withContext(Dispatchers.IO) {
                    val url = URL("$BASE_URL/api/sessions")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.connectTimeout = 5000; conn.readTimeout = 5000; conn.doOutput = true
                    val body = JSONObject().apply {
                        put("title", agentName ?: getString(R.string.chat_new_session))
                        if (currentModel.isNotEmpty()) put("model", currentModel)
                        if (currentProvider.isNotEmpty()) put("provider", currentProvider)
                        if (!systemPrompt.isNullOrEmpty()) put("system_prompt", systemPrompt)
                    }
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
                    val code = conn.responseCode
                    val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    conn.disconnect()
                    if (code !in 200..299) throw Exception("HTTP $code")
                    JSONObject(resp)
                }
                sessionId = data.getJSONObject("session").getString("id")
                currentSessionTitle = data.getJSONObject("session").optString("title", getString(R.string.chat_new_session))
                updateTopBar()
                // Save welcome message to gateway so it persists across session switches
                try {
                    withContext(Dispatchers.IO) {
                        val welcomeBody = JSONObject().apply {
                            put("role", "assistant")
                            put("content", getString(R.string.chat_welcome))
                        }
                        val conn = URL("$BASE_URL/api/sessions/$sessionId/messages").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.outputStream.write(welcomeBody.toString().toByteArray())
                        conn.responseCode
                        conn.disconnect()
                    }
                } catch (_: Exception) {}
                return true  // 成功
            } catch (_: Exception) {
                retries--
                if (retries > 0) {
                    delay(2000)  // 等引擎启动
                }
            }
        }
        // 全部失败
        sessionId = ""
        updateTopBar()
        return false
    }

    // Agent templates for sub-agent simulation
    private data class AgentTemplate(val name: String, val emoji: String, val systemPrompt: String)
    private val agentTemplates = listOf(
        AgentTemplate("普通会话", "💬", ""),
        AgentTemplate("代码助手", "💻", "你是一个专业的编程助手\u3002\u4f60擅长 Kotlin/Java/Python/JavaScript/TypeScript\u3002\u7528代码说话\uff0c简洁高效\u3002代码块标注语言\u3002"),
        AgentTemplate("研究助手", "🔍", "你是一个研究分析助手\u3002\u4f60擅长深度搜索\u3001资料整理\u3001报告撰写\u3002\u6bcf次回答都给出信息来源\u3002"),
        AgentTemplate("翻译官", "🌐", "你是一个专业翻译\u3002\u652f持中英日韩互译\u3002\u7ffb译自然流畅\uff0c保留专业术语\u3002\u4e0d加额外解释\uff0c直接给译文\u3002"),
        AgentTemplate("写作助手", "✍️", "你是一个写作助手\u3002\u64c5长各种文体\uff1a博客\u3001报告\u3001邮件\u3001文案\u3002\u98ce格灵活\uff0c可正式可轻松\u3002")
    )

    private fun showModelPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/models") }
                val modelsArr = response.optJSONArray("models") ?: return@launch
                val models = mutableListOf<Pair<String, String>>() // model, provider
                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.getJSONObject(i)
                    models.add(obj.optString("id") to obj.optString("provider"))
                }
                if (models.isEmpty()) return@launch

                val items = models.map { (m, p) ->
                    val prefix = when {
                        m.contains("qwen") -> "🤖"
                        m.contains("gpt") -> "🟢"
                        m.contains("claude") -> "🟠"
                        m.contains("deepseek") -> "🔵"
                        else -> "⭐"
                    }
                    val current = if (m == currentModel) " ✔" else ""
                    "$prefix $m ($p)$current"
                }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.model_pick_title)
                    .setItems(items) { _, which ->
                        val (model, provider) = models[which]
                        currentModel = model
                        currentProvider = provider
                        // Save per-session
                        requireContext().getSharedPreferences("openclaw_prefs", 0).edit()
                            .putString("session_model_$sessionId", model)
                            .putString("session_provider_$sessionId", provider)
                            .apply()
                        tvToolCount.text = items[which].replace(" ✔", "")
                        Toast.makeText(requireContext(), getString(R.string.model_switched, model), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "Failed to load models", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNewSessionPicker() {
        val items = agentTemplates.map { "${it.emoji} ${it.name}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.agent_pick_title)
            .setItems(items) { _, which ->
                val template = agentTemplates[which]
                if (template.systemPrompt.isEmpty()) {
                    startNewSession()
                } else {
                    startNewSession(template.name, template.systemPrompt)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startNewSession(agentName: String? = null, systemPrompt: String? = null) {
        // Save current draft before starting new session
        if (sessionId.isNotEmpty()) {
            val draft = etMessage.text.toString()
            requireContext().getSharedPreferences("openclaw_drafts", 0).edit()
                .putString("draft_$sessionId", draft).apply()
        }
        messageAdapter.clearMessages()
        currentSessionTitle = agentName ?: getString(R.string.chat_new_session)
        // Fix #1: Create session FIRST, then show welcome message only after success
        viewLifecycleOwner.lifecycleScope.launch {
            fetchModelInfoSync()
            val success = createNewSession(agentName, systemPrompt)
            if (success) {
                val welcomeMsg = if (agentName != null) getString(R.string.agent_welcome, agentName) else getString(R.string.chat_welcome)
                messageAdapter.addMessage(ChatMessage(content = welcomeMsg, isUser = false, model = "", sessionId = sessionId))
            } else {
                Toast.makeText(requireContext(), "会话创建失败", Toast.LENGTH_SHORT).show()
            }
        }
        Snackbar.make(requireView(), getString(R.string.chat_new_session_snack), Snackbar.LENGTH_SHORT).show()
    }

    // Fix #8: Track loading request to prevent stale data overwriting
    private var loadingSessionId: String? = null

    private fun switchToSession(session: Session) {
        // Save current draft before switching
        if (sessionId.isNotEmpty()) {
            val draft = etMessage.text.toString()
            requireContext().getSharedPreferences("openclaw_drafts", 0).edit()
                .putString("draft_$sessionId", draft).apply()
        }
        // Reset sending state to allow messages in new session
        isSending = false
        fabSend.isEnabled = true
        // Mark this as the latest loading request
        loadingSessionId = session.id
        // Switch
        sessionId = session.id
        currentSessionTitle = session.title
        messageAdapter.clearMessages()
        updateTopBar()
        refreshSessionModelProvider()
        loadSessionHistory(session.id)
        // Restore draft for new session
        val draft = requireContext().getSharedPreferences("openclaw_drafts", 0)
            .getString("draft_${session.id}", "") ?: ""
        etMessage.setText(draft)
        etMessage.setSelection(draft.length)
    }

    // ─── Send Message ────────────────────────────────────────────────────────



    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty() || isSending) return

        if (!isNetworkAvailable) {
            Toast.makeText(requireContext(), getString(R.string.network_offline_hint), Toast.LENGTH_SHORT).show()
            return
        }

        refreshSessionModelProvider()

        // Fix #7: Snapshot pending state BEFORE it gets cleared in finally, for retry restoration
        val snapFileName = pendingFileName
        val snapFileContent = pendingFileContent
        val snapImageBase64 = pendingOriginalImageBase64
        messageAdapter.addMessage(ChatMessage(
            content = text, isUser = true,
            retryFileContent = snapFileContent,
            retryOriginalImageBase64 = snapImageBase64
        ))
        scrollToBottom()
        etMessage.text.clear()
        requireContext().getSharedPreferences("openclaw_drafts", 0).edit()
            .remove("draft_$sessionId").apply()
        isSending = true
        fabSend.isEnabled = false
        val requestSessionId = sessionId

        messageAdapter.addMessage(ChatMessage(content = getString(R.string.chat_thinking), isUser = false, model = currentModel, sessionId = requestSessionId))
        scrollToBottom()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (pendingFileName != null && pendingFileContent != null) {
                    val msg = getString(R.string.chat_analyze_file_prompt, pendingFileName ?: "", pendingFileContent ?: "", text)
                    val result = withContext(Dispatchers.IO) {
                        postChat(msg, requestSessionId)
                    }
                    if (sessionId != requestSessionId) return@launch
                    val response = result.optString("content", getString(R.string.chat_no_reply_text))
                    val toolLog = result.optJSONArray("tool_log")
                    val steps = result.optInt("steps", 0)
                    val model = result.optString("model", currentModel)
                    messageAdapter.updateLastAiMessageFull(response, toolLog?.toString(), steps)
                    scrollToBottom()
                    if (model.isNotEmpty() && model != currentModel) currentModel = model
                    pendingFileName = null
                    pendingFileContent = null
                } else {
                    withContext(Dispatchers.IO) {
                        streamChat(
                            message = text,
                            sid = requestSessionId,
                            onChunk = { content ->
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                    if (sessionId == requestSessionId) {
                                        messageAdapter.updateLastAiMessage(content)
                                        scrollToBottom()
                                    }
                                }
                            },
                            onDone = { respSessionId, model, reasoning ->
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                    if (sessionId == requestSessionId && model.isNotEmpty()) {
                                        currentModel = model
                                    }
                                }
                            },
                            onError = { e ->
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                    if (sessionId == requestSessionId) {
                                        messageAdapter.updateLastAiMessage("❌ ${e.message ?: getString(R.string.chat_unknown_error)}")
                                        messageAdapter.markLastUserMessageFailed()
                                        scrollToBottom()
                                    }
                                }
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                if (sessionId == requestSessionId) {
                    messageAdapter.updateLastAiMessage("❌ ${e.message ?: getString(R.string.chat_unknown_error)}")
                    messageAdapter.markLastUserMessageFailed()
                    scrollToBottom()
                }
            } finally {
                // Fix #2: Always reset sending state AND clear pending files on error
                isSending = false
                fabSend.isEnabled = true
                if (sessionId == requestSessionId) {
                    pendingFileName = null
                    pendingFileContent = null
                    pendingOriginalImageBase64 = null
                }
            }
        }
    }

    // ─── Fetch Info ──────────────────────────────────────────────────────────

    private suspend fun fetchModelInfoSync() {
        try {
            val response = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/config") }
            val cfg = response.optJSONObject("config") ?: response
            val model = cfg.optString("model", "")
            currentModel = if (model.isEmpty()) "qwen3.5-plus" else model
            val prov = cfg.optString("default_provider", "")
            if (prov.isNotEmpty()) currentProvider = prov
        } catch (_: Exception) {
            currentModel = "qwen3.5-plus"
            currentProvider = "bailian"
        }
    }

    /**
     * Read session-level model/provider overrides (set by avatar tap).
     * Falls through to gateway defaults if no session override exists.
     */
    private fun refreshSessionModelProvider() {
        val prefs = requireContext().getSharedPreferences("openclaw_prefs", 0)
        if (sessionId.isNotEmpty()) {
            val sessionModel = prefs.getString("session_model_$sessionId", null)
            if (sessionModel != null) currentModel = sessionModel
            val sessionProv = prefs.getString("session_provider_$sessionId", null)
            if (sessionProv != null) currentProvider = sessionProv
        }
    }

        private fun fetchModelInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            fetchModelInfoSync()
        }
    }

    private fun fetchToolCount() {
        // Show current model name instead of tool count
        tvToolCount.text = currentModel.let {
            when {
                it.contains("qwen") -> "🤖 $it"
                it.contains("gpt") -> "🟢 $it"
                it.contains("claude") -> "🟠 $it"
                it.contains("deepseek") -> "🔵 $it"
                else -> "🤖 $it"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/tools") }
                tvToolCount.text = "${response.optInt("count", 0)} tools"
            } catch (_: Exception) {
                tvToolCount.text = ""
            }
        }
    }

    // ─── Session List ────────────────────────────────────────────────────────

    private fun showSessionList() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/sessions") }
                val arr = response.getJSONArray("sessions")
                val sessions = mutableListOf<Session>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    sessions.add(Session(
                        obj.getString("id"),
                        obj.optString("title", getString(R.string.chat_untitled)),
                        obj.optInt("message_count", 0),
                        formatTime(obj.optString("updated_at", ""))
                    ))
                }
                showSessionBottomSheet(sessions)
            } catch (_: Exception) {
                view?.let { Snackbar.make(it, getString(R.string.chat_load_sessions_failed), Snackbar.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showSessionBottomSheet(sessions: List<Session>) {
        val dialog = BottomSheetDialog(requireContext())
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_sessions, null)
        dialog.setContentView(view)
        val rv = view.findViewById<RecyclerView>(R.id.rvSessions)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = SessionAdapter(
            sessions = sessions,
            onItemClick = { switchToSession(it); dialog.dismiss() },
            onItemLongClick = { session ->
                showSessionContextMenu(session) { dialog.dismiss(); showSessionList() }
            }
        )
        dialog.show()
    }

    // Fix #4 + #8: Show loading indicator + prevent stale data from overwriting newer session
    private fun loadSessionHistory(sid: String) {
        // Show loading state immediately
        messageAdapter.clearMessages()
        messageAdapter.addMessage(ChatMessage(content = getString(R.string.chat_loading_history), isUser = false, model = "", sessionId = sid))

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/sessions/$sid") }
                // Fix #8: Only apply if this is still the latest loading request
                if (loadingSessionId != sid || sessionId != sid) return@launch
                val sessionObj = data.getJSONObject("session")
                currentSessionTitle = sessionObj.optString("title", currentSessionTitle)
                updateTopBar()
                val arr = sessionObj.getJSONArray("messages")
                val messages = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    val msg = arr.getJSONObject(i)
                    val content = msg.optString("content", "")
                    val role = msg.optString("role", "assistant")
                    if (content.isNotEmpty()) {
                        messages.add(ChatMessage(
                            content = content,
                            isUser = role == "user",
                            model = if (role != "user") sessionObj.optString("model", "") else "",
                            sessionId = sid
                        ))
                    }
                }
                if (loadingSessionId != sid || sessionId != sid) return@launch
                messageAdapter.clearMessages()
                messages.forEach { messageAdapter.addMessage(it) }
                scrollToBottom()
            } catch (_: Exception) {
                if (loadingSessionId == sid && sessionId == sid) {
                    view?.let { Snackbar.make(it, getString(R.string.chat_load_history_failed), Snackbar.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun showSessionContextMenu(session: Session, onDone: () -> Unit) {
        val shortTitle = session.title.ifEmpty { "#${session.id.take(8)}" }
        AlertDialog.Builder(requireContext())
            .setTitle(shortTitle)
            .setItems(arrayOf(
                getString(R.string.chat_edit_title),
                getString(R.string.chat_delete),
                getString(R.string.chat_clear_context),
                getString(R.string.chat_export)
            )) { _, which ->
                when (which) {
                    0 -> showRenameSessionDialog(session, onDone)
                    1 -> showDeleteSessionConfirm(session, onDone)
                    2 -> showClearContextConfirm(session)
                    3 -> exportSession(session)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteSessionConfirm(session: Session, onDone: () -> Unit) {
        val shortTitle = session.title.ifEmpty { "#${session.id.take(8)}" }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.chat_delete_session_title))
            .setMessage(getString(R.string.chat_delete_session_msg, shortTitle))
            .setPositiveButton(getString(R.string.chat_delete)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val url = URL("$BASE_URL/api/sessions/${session.id}")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "DELETE"
                            conn.connectTimeout = 5000; conn.readTimeout = 5000
                            conn.responseCode; conn.disconnect()
                        }
                        if (session.id == sessionId) {
                            createNewSession()
                        }
                        view?.let { Snackbar.make(it, getString(R.string.chat_session_deleted), Snackbar.LENGTH_SHORT).show() }
                        onDone()
                    } catch (e: Exception) {
                        view?.let { Snackbar.make(it, "❌ ${e.message}", Snackbar.LENGTH_SHORT).show() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Fix #6: Export the TARGET session's messages from API, not the adapter (which may be a different session)
    private fun exportSession(session: Session) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fetch messages from API for the target session
                val data = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/sessions/${session.id}") }
                val arr = data.getJSONObject("session").getJSONArray("messages")
                val sb = StringBuilder()
                sb.appendLine("# ${session.title}")
                sb.appendLine("*Exported on ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date())}*")
                sb.appendLine()
                for (i in 0 until arr.length()) {
                    val msg = arr.getJSONObject(i)
                    val content = msg.optString("content", "")
                    val role = msg.optString("role", "assistant")
                    if (content.isNotEmpty()) {
                        if (role == "user") {
                            sb.appendLine("## 👤 User")
                        } else {
                            sb.appendLine("## 🤖 AI")
                        }
                        sb.appendLine(content)
                        sb.appendLine()
                    }
                }
                val fileName = "${session.title.replace(Regex("[^\\w\\u4e00-\\u9fff]"), "_")}.md"
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "OpenClaw")
                dir.mkdirs()
                val file = java.io.File(dir, fileName)
                file.writeText(sb.toString())
                Toast.makeText(context, getString(R.string.chat_exported, file.absolutePath), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showClearContextConfirm(session: Session) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.chat_clear_context))
            .setMessage(getString(R.string.chat_clear_context_confirm))
            .setPositiveButton(getString(R.string.chat_clear_context)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val url = URL("$BASE_URL/api/sessions/${session.id}")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "DELETE"
                            conn.connectTimeout = 5000; conn.readTimeout = 5000
                            conn.responseCode; conn.disconnect()
                        }
                        // Start a new session with same ID effectively clears context
                        startNewSession()
                        Toast.makeText(context, getString(R.string.chat_context_cleared), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameSessionDialog(session: Session, onDone: () -> Unit) {
        val input = EditText(requireContext()).apply {
            setText(session.title); setSelection(text.length); setPadding(60, 30, 60, 30)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.chat_edit_title))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val url = URL("$BASE_URL/api/sessions/${session.id}/title")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                            conn.doOutput = true
                            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                                it.write(JSONObject().apply { put("title", title) }.toString())
                            }
                            conn.responseCode; conn.disconnect()
                        }
                        if (session.id == sessionId) { currentSessionTitle = title; updateTopBar() }
                        onDone()
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─── Attachments ─────────────────────────────────────────────────────────

    private fun showAttachOptions() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.chat_attach_title))
            .setItems(arrayOf(getString(R.string.chat_attach_image), getString(R.string.chat_attach_document))) { _, which ->
                when (which) { 0 -> pickImage(); 1 -> pickDocument() }
            }
            .show()
    }

    private fun pickImage() {
        imagePickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" })
    }

    private fun pickDocument() {
        docPickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain", "text/markdown", "text/html", "text/csv",
                "application/json", "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ))
        })
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val inputStream = requireContext().contentResolver.openInputStream(uri)
                    val originalBytes = inputStream?.readBytes() ?: return@let
                    inputStream.close()

                    // 原图 base64（发送给模型）
                    val originalBase64 = android.util.Base64.encodeToString(originalBytes, android.util.Base64.NO_WRAP)

                    // 缩略图 base64（显示在聊天框）
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                    val thumbMaxDim = 300
                    val scale = minOf(thumbMaxDim.toFloat() / bitmap.width, thumbMaxDim.toFloat() / bitmap.height, 1f)
                    val thumbW = (bitmap.width * scale).toInt()
                    val thumbH = (bitmap.height * scale).toInt()
                    val thumbBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true)
                    val baos = java.io.ByteArrayOutputStream()
                    thumbBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                    val thumbBase64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

                    // 显示缩略图，存储原图以便点击查看
                    messageAdapter.addMessage(ChatMessage(
                        content = getString(R.string.chat_image_label),
                        isUser = true,
                        imageBase64 = thumbBase64,
                        originalImageBase64 = originalBase64,
                        retryOriginalImageBase64 = originalBase64
                    ))
                    scrollToBottom()

                    // 发送原图给模型
                    sendImageMessage(originalBase64)
                } catch (e: Exception) {
                    view?.let { Snackbar.make(it, getString(R.string.chat_image_process_failed, e.message ?: ""), Snackbar.LENGTH_LONG).show() }
                }
            }
        }
    }

    private val docPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    var fileName = "document"
                    var fileSize = 0L
                    requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0) fileName = cursor.getString(nameIdx)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                        }
                    }

                    // 读取文件内容
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.readBytes() ?: return@let

                    // 保存到缓存目录（点击时可以打开）
                    val docsDir = java.io.File(requireContext().cacheDir, "docs")
                    docsDir.mkdirs()
                    val cachedFile = java.io.File(docsDir, fileName)
                    cachedFile.writeBytes(bytes)

                    // 根据文件类型提取文本内容
                    val textContent = extractTextFromFile(fileName, bytes)

                    messageAdapter.addMessage(ChatMessage(
                        content = "",
                        isUser = true,
                        fileName = fileName,
                        fileSize = fileSize
                    ))
                    scrollToBottom()

                    if (textContent != null && textContent.isNotEmpty()) {
                        pendingFileName = fileName
                        pendingFileContent = if (textContent.length > 50000) textContent.take(50000) else textContent
                        Snackbar.make(requireView(), getString(R.string.chat_file_parsed), Snackbar.LENGTH_LONG).show()
                    } else {
                        // Binary file cannot extract text
                        pendingFileName = fileName
                        pendingFileContent = getString(R.string.chat_file_binary_info, fileName, fileSize, fileName.substringAfterLast(".", "unknown"))
                        Snackbar.make(requireView(), getString(R.string.chat_file_binary_hint), Snackbar.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    view?.let { Snackbar.make(it, getString(R.string.chat_file_read_failed), Snackbar.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun sendImageMessage(originalBase64: String) {
        refreshSessionModelProvider()
        isSending = true
        fabSend.isEnabled = false
        val requestSessionId = sessionId

        messageAdapter.addMessage(ChatMessage(content = getString(R.string.chat_analyzing_image), isUser = false, model = currentModel, sessionId = requestSessionId))
        scrollToBottom()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { postImage(originalBase64, requestSessionId) }
                if (sessionId != requestSessionId) return@launch
                val response = result.optString("content", getString(R.string.chat_no_reply_text))
                val toolLog = result.optJSONArray("tool_log")
                val steps = result.optInt("steps", 0)
                messageAdapter.updateLastAiMessageFull(response, toolLog?.toString(), steps)
                scrollToBottom()
            } catch (e: Exception) {
                if (sessionId == requestSessionId) {
                    messageAdapter.updateLastAiMessage(getString(R.string.chat_analyze_failed, e.message ?: ""))
                }
            } finally {
                // Fix #6: Always reset sending state AND clear pending image
                isSending = false
                fabSend.isEnabled = true
                pendingOriginalImageBase64 = null
            }
        }
    }

    // ─── HTTP ────────────────────────────────────────────────────────────────

    private fun postChat(message: String, sid: String): JSONObject {
        val retryDelays = longArrayOf(1000L, 3000L)
        var lastException: Exception? = null
        for (attempt in 0..retryDelays.size) {
            try {
                val url = URL("$BASE_URL/api/agent/chat")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 60000; conn.readTimeout = 600000; conn.doOutput = true
                val body = JSONObject().apply {
                    put("message", message)
                    if (sid.isNotEmpty()) put("session_id", sid)
                    if (currentModel.isNotEmpty()) put("model", currentModel)
                    if (currentProvider.isNotEmpty()) put("provider", currentProvider)
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
                conn.disconnect()
                if (code !in 200..299) {
                    if (resp.trimStart().startsWith("<")) {
                        throw Exception("HTTP $code: Provider returned HTML error (API key invalid or rate limited)")
                    }
                    throw Exception("HTTP $code: $resp")
                }
                return JSONObject(resp)
            } catch (e: Exception) {
                lastException = e
                if (attempt < retryDelays.size && isRetryableException(e)) {
                    Thread.sleep(retryDelays[attempt])
                    continue
                }
                throw e
            }
        }
        throw lastException ?: Exception("Retry exhausted")
    }

    private fun streamChat(
        message: String,
        sid: String,
        onChunk: (String) -> Unit,
        onDone: (sessionId: String, model: String, reasoning: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val retryDelays = longArrayOf(1000L, 3000L)
        var lastException: Exception? = null
        for (attempt in 0..retryDelays.size) {
            try {
                val url = URL("$BASE_URL/api/agent/chat")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 60000; conn.readTimeout = 600000; conn.doOutput = true
                val body = JSONObject().apply {
                    put("message", message)
                    if (sid.isNotEmpty()) put("session_id", sid)
                    if (currentModel.isNotEmpty()) put("model", currentModel)
                    if (currentProvider.isNotEmpty()) put("provider", currentProvider)
                    put("stream", true)
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }

                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                        val resp = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
                        conn.disconnect()
                        if (resp.trimStart().startsWith("<")) {
                            throw Exception("HTTP $code: Provider returned HTML error (API key invalid or rate limited)")
                        }
                        throw Exception("HTTP $code: $resp")
                    }
                    
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    val accumulated = StringBuilder()
                    var receivedCount = 0
                    var lastScrollTime = System.currentTimeMillis()
                    var currentSessionId = sid
                    var currentModel = ""
                    var currentReasoning = ""
                    
                    var line: String?
                    var currentEvent = "" // Track SSE event type
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim() ?: continue
                        
                        if (trimmed.isEmpty()) {
                            currentEvent = "" // Reset event on blank line
                            continue
                        }
                        
                        if (trimmed.startsWith("event: ")) {
                            currentEvent = trimmed.substringAfter("event: ").trim()
                            continue
                        }
                        
                        if (trimmed == "data: [DONE]") {
                            break
                        }
                        
                        if (trimmed.startsWith("data: ")) {
                            val data = trimmed.substringAfter("data: ").trim()
                            
                            try {
                                val json = JSONObject(data)
                                
                                // Handle agent stream events
                                when (currentEvent) {
                                    "tool_call" -> {
                                        val toolName = json.optString("name", "")
                                        if (toolName.isNotEmpty()) {
                                            accumulated.append("\n🔧 调用 `$toolName`...")
                                            onChunk(accumulated.toString())
                                        }
                                        continue
                                    }
                                    "tool_result" -> {
                                        val toolName = json.optString("name", "")
                                        val preview = json.optString("preview", "").take(100)
                                        if (toolName.isNotEmpty()) {
                                            // Replace the "calling" line with result
                                            val s = accumulated.toString()
                                            val callLine = "\n🔧 调用 `$toolName`..."
                                            if (s.endsWith(callLine)) {
                                                accumulated.setLength(accumulated.length - callLine.length)
                                            }
                                            accumulated.append("\n✅ `$toolName` 完成\n")
                                            onChunk(accumulated.toString())
                                        }
                                        continue
                                    }
                                    "done" -> {
                                        currentSessionId = json.optString("session_id", currentSessionId)
                                        currentModel = json.optString("model", currentModel)
                                        val steps = json.optInt("steps", 0)
                                        if (steps > 0) {
                                            // Clean up tool log lines for final display
                                            val content = accumulated.toString()
                                            val cleanContent = content.replace(Regex("\n[🔧✅][^\n]*"), "").trimStart()
                                            if (cleanContent.isNotBlank()) {
                                                accumulated.setLength(0)
                                                accumulated.append(cleanContent)
                                            }
                                            // If cleanContent is empty, keep original accumulated (with tool lines)
                                            // so user sees what tools were called instead of blank
                                            onChunk(accumulated.toString())
                                        }
                                        break
                                    }
                                }
                                
                                if (json.has("session_id")) {
                                    currentSessionId = json.optString("session_id", currentSessionId)
                                }
                                if (json.has("model")) {
                                    currentModel = json.optString("model", currentModel)
                                }
                                if (json.has("reasoning")) {
                                    currentReasoning = json.optString("reasoning", currentReasoning)
                                }
                                
                                // Handle error from Gateway
                                if (json.has("error")) {
                                    val errorCode = json.optString("error", "unknown")
                                    val errorMsg = when (errorCode) {
                                        "missing_api_key" -> "未配置 API Key，请在设置中添加"
                                        "unknown_model" -> "未找到模型，请检查配置"
                                        "rate_limited" -> "请求频率过高，请稍后重试"
                                        else -> json.optString("message", errorCode)
                                    }
                                    throw Exception(errorMsg)
                                }
                                
                                if (json.has("choices")) {
                                    val choices = json.getJSONArray("choices")
                                    if (choices.length() > 0) {
                                        val firstChoice = choices.getJSONObject(0)
                                        if (firstChoice.has("delta")) {
                                            val delta = firstChoice.getJSONObject("delta")
                                            if (delta.has("content")) {
                                                val content = delta.optString("content")
                                                if (content.isNotEmpty()) {
                                                    accumulated.append(content)
                                                    receivedCount++
                                                    
                                                    val now = System.currentTimeMillis()
                                                    if (receivedCount % 5 == 0 || now - lastScrollTime >= 200) {
                                                        onChunk(accumulated.toString())
                                                        lastScrollTime = now
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (json.has("content")) {
                                    val content = json.optString("content", "")
                                    if (content.isNotEmpty()) {
                                        accumulated.append(content)
                                        receivedCount++
                                        
                                        val now = System.currentTimeMillis()
                                        if (receivedCount % 5 == 0 || now - lastScrollTime >= 200) {
                                            onChunk(accumulated.toString())
                                            lastScrollTime = now
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                    
                    onChunk(accumulated.toString())
                    onDone(currentSessionId, currentModel, currentReasoning)
                    conn.disconnect()
                    return  // Success, exit retry loop
            } catch (e: Exception) {
                lastException = e
                if (attempt < retryDelays.size && isRetryableException(e)) {
                    Thread.sleep(retryDelays[attempt])
                    continue
                }
                onError(e)
                return
            }
        }
        onError(lastException ?: Exception("Retry exhausted"))
    }

    private fun postImage(base64: String, sid: String): JSONObject {
        val url = URL("$BASE_URL/api/agent/chat")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.connectTimeout = 60000; conn.readTimeout = 600000; conn.doOutput = true // 10 min for multi-step agent
        val body = JSONObject().apply {
            put("message", getString(R.string.chat_analyze_image_prompt))
            put("image_base64", base64)
            if (sid.isNotEmpty()) put("session_id", sid)
            if (currentModel.isNotEmpty()) put("model", currentModel)
            if (currentProvider.isNotEmpty()) put("provider", currentProvider)
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) {
            if (resp.trimStart().startsWith("<")) {
                throw Exception("HTTP $code: Provider returned HTML error (API key invalid or rate limited)")
            }
            throw Exception("HTTP $code: $resp")
        }
        return JSONObject(resp)
    }

    // Fix #3: Only scroll if user is already near bottom (don't interrupt reading history)
    private fun scrollToBottom() {
        val count = messageAdapter.getMessageCount()
        if (count <= 0) return
        val layoutManager = rvMessages.layoutManager as LinearLayoutManager
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val isNearBottom = (count - 1 - lastVisible) <= 3
        if (isNearBottom) {
            rvMessages.scrollToPosition(count - 1)
        }
    }

    private fun httpGet(urlStr: String): JSONObject {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 10000; conn.readTimeout = 10000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code: $body")
        return JSONObject(body)
    }

    private fun formatTime(raw: String): String {
        if (raw.isEmpty()) return getString(R.string.chat_just_now)
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
            val out = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            out.format(fmt.parse(raw)!!)
        } catch (_: Exception) { raw }
    }

    /**
     * 根据文件类型提取文本内容
     * 支持: txt, md, json, csv, html, xml —— 直接读取
     * 支持: docx —— 解压 ZIP 提取 word/document.xml 中的文本
     */
    private fun extractTextFromFile(fileName: String, bytes: ByteArray): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            // 纯文本格式
            "txt", "md", "markdown", "json", "csv", "html", "htm", "xml", "yaml", "yml",
            "log", "ini", "cfg", "conf", "properties", "sh", "bat", "py", "js", "kt",
            "java", "c", "cpp", "h", "swift", "go", "rs", "rb", "php", "css", "sql" -> {
                try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { null }
            }
            // docx: ZIP 包含 word/document.xml
            "docx" -> extractDocxText(bytes)
            // doc: 旧格式，尝试提取可见文本
            "doc" -> extractOldDocText(bytes)
            else -> null
        }
    }

    /**
     * 从 docx (ZIP) 中提取文本
     */
    private fun extractDocxText(bytes: ByteArray): String? {
        return try {
            val zipInput = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
            val sb = StringBuilder()
            var entry = zipInput.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xmlBytes = zipInput.readBytes()
                    val xmlStr = String(xmlBytes, Charsets.UTF_8)
                    // 提取 <w:t> 标签中的文本
                    val pattern = Regex("<w:t[^>]*>([^<]*)</w:t>")
                    val matches = pattern.findAll(xmlStr)
                    var lastWasParagraph = false
                    for (match in matches) {
                        sb.append(match.groupValues[1])
                    }
                    // 用段落标记添加换行
                    val paraPattern = Regex("</w:p>")
                    val fullText = paraPattern.replace(xmlStr, "\n")
                    val textPattern = Regex("<w:t[^>]*>([^<]*)</w:t>")
                    val result = StringBuilder()
                    var currentPara = StringBuilder()
                    for (line in fullText.split("\n")) {
                        val paraTexts = textPattern.findAll(line)
                        val paraContent = paraTexts.joinToString("") { it.groupValues[1] }
                        if (paraContent.isNotEmpty()) {
                            result.appendLine(paraContent)
                        }
                    }
                    zipInput.close()
                    return result.toString().trim().ifEmpty { sb.toString().trim() }
                }
                entry = zipInput.nextEntry
            }
            zipInput.close()
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从旧版 .doc 中尝试提取可见 ASCII 文本（best effort）
     */
    private fun extractOldDocText(bytes: ByteArray): String? {
        return try {
            // .doc 是 OLE2 格式，简单提取所有可打印字符序列
            val sb = StringBuilder()
            var consecutiveReadable = 0
            val tempSb = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 32..126 || c == 10 || c == 13 || c == 9) {
                    tempSb.append(c.toChar())
                    consecutiveReadable++
                } else {
                    if (consecutiveReadable > 20) {
                        sb.append(tempSb)
                        sb.append('\n')
                    }
                    tempSb.clear()
                    consecutiveReadable = 0
                }
            }
            if (consecutiveReadable > 20) sb.append(tempSb)
            val text = sb.toString().trim()
            if (text.length > 100) text else null
        } catch (_: Exception) { null }
    }

    private fun handleTtsClick(text: String, position: Int) {
        if (!ttsReady || tts == null) {
            Toast.makeText(requireContext(), "TTS not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val cleanText = text.replace(Regex("[*_`#]"), "").trim()
        if (cleanText.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.tts_no_text), Toast.LENGTH_SHORT).show()
            return
        }

        if (messageAdapter.isTtsSpeaking && messageAdapter.speakingPosition == position) {
            tts?.stop()
            messageAdapter.isTtsSpeaking = false
            messageAdapter.speakingPosition = -1
            messageAdapter.notifyItemChanged(position)
            return
        }
        if (messageAdapter.isTtsSpeaking) {
            tts?.stop()
            messageAdapter.isTtsSpeaking = false
            if (messageAdapter.speakingPosition >= 0) messageAdapter.notifyItemChanged(messageAdapter.speakingPosition)
            messageAdapter.speakingPosition = -1
        }

        messageAdapter.isTtsSpeaking = true
        messageAdapter.speakingPosition = position
        messageAdapter.notifyItemChanged(position)

        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                requireActivity().runOnUiThread {
                    messageAdapter.isTtsSpeaking = false
                    messageAdapter.speakingPosition = -1
                    messageAdapter.notifyItemChanged(position)
                }
            }
            override fun onError(utteranceId: String) {
                requireActivity().runOnUiThread {
                    messageAdapter.isTtsSpeaking = false
                    messageAdapter.speakingPosition = -1
                    messageAdapter.notifyItemChanged(position)
                    Toast.makeText(requireContext(), getString(R.string.tts_failed), Toast.LENGTH_LONG).show()
                }
            }
        })

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "utterance_$position")
        Log.d("TTS_TEST", "speak result=$result")

        Toast.makeText(requireContext(), getString(R.string.tts_started), Toast.LENGTH_SHORT).show()
    }

}
