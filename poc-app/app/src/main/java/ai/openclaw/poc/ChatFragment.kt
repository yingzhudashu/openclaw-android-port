package ai.openclaw.poc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
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
    private lateinit var tvSessionId: TextView
    private lateinit var tvSessionTitle: TextView
    private lateinit var tvToolCount: TextView
    private lateinit var btnNewSession: ImageButton
    private lateinit var btnSessionList: ImageButton
    private lateinit var btnAttach: ImageButton

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
        etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        btnSessionList.setOnClickListener { showSessionList() }
        btnNewSession.setOnClickListener { startNewSession() }
        btnAttach.setOnClickListener { showAttachOptions() }

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
                createNewSession()
            }
        }
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

    private fun createNewSession() {
        viewLifecycleOwner.lifecycleScope.launch {
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
                            put("title", getString(R.string.chat_new_session))
                            if (currentModel.isNotEmpty()) put("model", currentModel)
                            if (currentProvider.isNotEmpty()) put("provider", currentProvider)
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
                    return@launch  // 成功，退出
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
        }
    }

    private fun startNewSession() {
        messageAdapter.clearMessages()
        messageAdapter.addMessage(ChatMessage(content = getString(R.string.chat_welcome), isUser = false, model = "", sessionId = sessionId))
        currentSessionTitle = getString(R.string.chat_new_session)
        // Refresh default model+provider from gateway before creating session
        viewLifecycleOwner.lifecycleScope.launch {
            fetchModelInfoSync()
            createNewSession()
        }
        Snackbar.make(requireView(), getString(R.string.chat_new_session_snack), Snackbar.LENGTH_SHORT).show()
    }

    private fun switchToSession(session: Session) {
        // Reset sending state to allow messages in new session
        isSending = false
        fabSend.isEnabled = true
        // Switch
        sessionId = session.id
        currentSessionTitle = session.title
        messageAdapter.clearMessages()
        updateTopBar()
        refreshSessionModelProvider()
        loadSessionHistory(session.id)
    }

    // ─── Send Message ────────────────────────────────────────────────────────

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty() || isSending) return

        refreshSessionModelProvider()

        messageAdapter.addMessage(ChatMessage(content = text, isUser = true))
        scrollToBottom()
        etMessage.text.clear()
        isSending = true
        fabSend.isEnabled = false
        val requestSessionId = sessionId

        messageAdapter.addMessage(ChatMessage(content = getString(R.string.chat_thinking), isUser = false, model = currentModel, sessionId = requestSessionId))
        scrollToBottom()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (pendingFileName != null && pendingFileContent != null) {
                        val msg = getString(R.string.chat_analyze_file_prompt, pendingFileName ?: "", pendingFileContent ?: "", text)
                        pendingFileName = null; pendingFileContent = null
                        postChat(msg, requestSessionId)
                    } else {
                        postChat(text, requestSessionId)
                    }
                }
                if (sessionId != requestSessionId) return@launch
                val response = result.optString("content", getString(R.string.chat_no_reply_text))
                val toolLog = result.optJSONArray("tool_log")
                val steps = result.optInt("steps", 0)
                val model = result.optString("model", currentModel)
                messageAdapter.updateLastAiMessageFull(response, toolLog?.toString(), steps)
                scrollToBottom()
                if (model.isNotEmpty() && model != currentModel) currentModel = model
            } catch (e: Exception) {
                if (sessionId == requestSessionId) {
                    messageAdapter.updateLastAiMessage("❌ ${e.message ?: getString(R.string.chat_unknown_error)}")
                    scrollToBottom()
                }
            } finally {
                isSending = false
                fabSend.isEnabled = true
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

    private fun loadSessionHistory(sid: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/sessions/$sid") }
                if (sessionId != sid) return@launch
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
                if (sessionId != sid) return@launch
                messageAdapter.clearMessages()
                messages.forEach { messageAdapter.addMessage(it) }
                scrollToBottom()
            } catch (_: Exception) {
                if (sessionId == sid) {
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
                getString(R.string.chat_delete)
            )) { _, which ->
                when (which) {
                    0 -> showRenameSessionDialog(session, onDone)
                    1 -> showDeleteSessionConfirm(session, onDone)
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
                        originalImageBase64 = originalBase64
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
                isSending = false
                fabSend.isEnabled = true
            }
        }
    }

    // ─── HTTP ────────────────────────────────────────────────────────────────

    private fun postChat(message: String, sid: String): JSONObject {
        val url = URL("$BASE_URL/api/agent/chat")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.connectTimeout = 60000; conn.readTimeout = 180000; conn.doOutput = true
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
        if (code !in 200..299) throw Exception("HTTP $code: $resp")
        return JSONObject(resp)
    }

    private fun postImage(base64: String, sid: String): JSONObject {
        val url = URL("$BASE_URL/api/agent/chat")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.connectTimeout = 60000; conn.readTimeout = 180000; conn.doOutput = true
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
        if (code !in 200..299) throw Exception("HTTP $code: $resp")
        return JSONObject(resp)
    }

    private fun scrollToBottom() {
        val count = messageAdapter.getMessageCount()
        if (count > 0) rvMessages.smoothScrollToPosition(count - 1)
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
}
