package ai.openclaw.poc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天消息数据类
 */
data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val model: String = "",
    val imageBase64: String? = null,
    val originalImageBase64: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val toolLog: String? = null,
    val steps: Int = 0,
    val sessionId: String? = null,
    val sendFailed: Boolean = false,
    val retryFileContent: String? = null,
    val retryOriginalImageBase64: String? = null
)

/**
 * 消息列表 RecyclerView 适配器
 *
 * v1.6.0 优化：
 * - 使用 MarkdownRenderer 替代内联渲染
 * - 新增 streaming 更新（不触发 full rebind）
 * - 简化 bind 逻辑，抽取辅助方法
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val expandedToolLogs = mutableSetOf<Int>()
    private var searchQuery: String = ""

    // Callbacks
    var onRetryClick: ((Int, ChatMessage) -> Unit)? = null
    var onTtsClick: ((String, Int) -> Unit)? = null
    var isTtsSpeaking = false
    var speakingPosition = -1

    // v1.6.0: 流式更新优化 — 直接更新 TextView 而不重新 bind 整个 ViewHolder
    @Volatile private var streamingViewHolder: MessageViewHolder? = null

    fun addMessage(message: ChatMessage) {
        val pos = messages.size
        messages.add(message)
        notifyItemInserted(pos)
    }

    // ─── v1.6.0 Streaming Update ─────────────────────────────────────────

    /**
     * 流式更新最后一条 AI 消息 — 直接更新 TextView，不触发 notifyItemChanged
     * 
     * 性能优化：SSE 每个 chunk 都调用此方法，避免全量 Markdown 解析 + ViewHolder 重绘
     * 使用 lite 渲染（仅加粗/行内代码，无 ClickableSpan）
     */
    fun updateLastAiMessageStreaming(content: String) {
        for (i in messages.indices.reversed()) {
            if (!messages[i].isUser) {
                messages[i] = messages[i].copy(content = content)
                // 直接更新 ViewHolder 的 TextView，不 notify
                val holder = streamingViewHolder
                if (holder != null && holder.bindingAdapterPosition == i) {
                    holder.updateStreamingContent(content)
                }
                return
            }
        }
    }

    /**
     * 标记流式结束 — 通知 ViewHolder 进行完整渲染
     */
    fun finishStreaming() {
        val holder = streamingViewHolder
        if (holder != null) {
            val pos = holder.bindingAdapterPosition
            if (pos >= 0 && pos < messages.size) {
                notifyItemChanged(pos)
            }
        }
        streamingViewHolder = null
    }

    // ─── Standard Updates ────────────────────────────────────────────────

    fun updateLastAiMessage(content: String) {
        for (i in messages.indices.reversed()) {
            if (!messages[i].isUser) {
                messages[i] = messages[i].copy(content = content)
                notifyItemChanged(i)
                return
            }
        }
    }

    fun updateLastAiMessageFull(content: String, toolLog: String? = null, steps: Int = 0) {
        for (i in messages.indices.reversed()) {
            if (!messages[i].isUser) {
                messages[i] = messages[i].copy(content = content, toolLog = toolLog, steps = steps)
                notifyItemChanged(i)
                return
            }
        }
    }

    fun setMessages(items: List<ChatMessage>) {
        messages.clear()
        messages.addAll(items)
        expandedToolLogs.clear()
        notifyDataSetChanged()
    }

    fun clearMessages() {
        val size = messages.size
        messages.clear()
        expandedToolLogs.clear()
        streamingViewHolder = null
        notifyItemRangeRemoved(0, size)
    }

    fun highlightSearch(query: String): Int {
        searchQuery = query.lowercase()
        notifyDataSetChanged()
        for (i in messages.indices.reversed()) {
            if (messages[i].content.lowercase().contains(searchQuery)) return i
        }
        return -1
    }

    fun clearHighlight() {
        searchQuery = ""
        notifyDataSetChanged()
    }

    fun getMessageCount(): Int = messages.size
    fun getAllMessages(): List<ChatMessage> = messages.toList()
    fun getMessageAt(position: Int): ChatMessage? = messages.getOrNull(position)

    fun removeMessageAt(position: Int) {
        if (position in messages.indices) {
            messages.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun markLastUserMessageFailed() {
        for (i in messages.indices.reversed()) {
            if (messages[i].isUser) {
                messages[i] = messages[i].copy(sendFailed = true)
                notifyItemChanged(i)
                return
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        // 记录最后一个 AI ViewHolder 用于流式更新
        if (!messages[position].isUser && holder.bindingAdapterPosition == position) {
            streamingViewHolder = holder
        }
        holder.bind(messages[position], position)
    }

    override fun getItemCount(): Int = messages.size

    // ─── ViewHolder ──────────────────────────────────────────────────────

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // User views
        private val layoutUser: View = itemView.findViewById(R.id.layoutUser)
        private val tvUserMessage: TextView = itemView.findViewById(R.id.tvUserMessage)
        private val tvUserTime: TextView = itemView.findViewById(R.id.tvUserTime)
        private val tvRetryHint: TextView = itemView.findViewById(R.id.tvRetryHint)
        private val ivUserImage: ImageView = itemView.findViewById(R.id.ivUserImage)
        private val cardUserImage: View = itemView.findViewById(R.id.cardUserImage)
        private val cardUserFile: View = itemView.findViewById(R.id.cardUserFile)
        private val cardUser: View = itemView.findViewById(R.id.cardUser)
        private val tvUserFileName: TextView = itemView.findViewById(R.id.tvUserFileName)
        private val tvUserFileSize: TextView = itemView.findViewById(R.id.tvUserFileSize)
        private val tvFileIcon: TextView = itemView.findViewById(R.id.tvFileIcon)
        private val tvDateSeparator: TextView = itemView.findViewById(R.id.tvDateSeparator)

        // AI views
        private val layoutAi: View = itemView.findViewById(R.id.layoutAi)
        private val tvAiLabel: TextView = itemView.findViewById(R.id.tvAiLabel)
        private val tvAiMessage: TextView = itemView.findViewById(R.id.tvAiMessage)
        private val tvAiTime: TextView = itemView.findViewById(R.id.tvAiTime)
        private val ivAiImage: ImageView = itemView.findViewById(R.id.ivAiImage)
        private val layoutToolLog: LinearLayout = itemView.findViewById(R.id.layoutToolLog)
        private val tvToolSummary: TextView = itemView.findViewById(R.id.tvToolSummary)
        private val tvToolDetail: TextView = itemView.findViewById(R.id.tvToolDetail)
        private val btnTts: android.widget.ImageButton = itemView.findViewById(R.id.btnTts)
        private val tvModelTag: TextView = itemView.findViewById(R.id.tvModelTag)
        private val cardAvatar: View = itemView.findViewById(R.id.cardAvatar)
        private val tvAvatarEmoji: TextView = itemView.findViewById(R.id.tvAvatarEmoji)

        // v1.6.0: 流式内容缓存
        private var lastStreamingContent: String? = null

        /**
         * v1.6.0: 流式更新 — 直接设置 TextView 文本，不做 Markdown 解析
         */
        fun updateStreamingContent(content: String) {
            if (content == lastStreamingContent) return
            lastStreamingContent = content
            // 使用 lite 渲染：仅加粗/行内代码，跳过 ClickableSpan（性能关键路径）
            tvAiMessage.text = MarkdownRenderer.renderLite(content, markdownConfig)
            tvAiMessage.movementMethod = LinkMovementMethod.getInstance()
        }

        fun bind(message: ChatMessage, position: Int) {
            bindDateSeparator(message, position)
            val timeStr = timeFormat.format(Date(message.timestamp))

            if (message.isUser) {
                bindUserMessage(message, timeStr, position)
            } else {
                bindAiMessage(message, timeStr, position)
            }
        }

        private fun bindDateSeparator(message: ChatMessage, position: Int) {
            val showDate = if (position == 0) true else {
                val prev = messages[position - 1]
                val prevDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date(prev.timestamp))
                val curDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date(message.timestamp))
                prevDate != curDate
            }
            if (showDate && message.timestamp > 0) {
                tvDateSeparator.visibility = View.VISIBLE
                val diff = System.currentTimeMillis() - message.timestamp
                tvDateSeparator.text = when {
                    diff < 86400000L -> itemView.context.getString(R.string.date_today)
                    diff < 172800000L -> itemView.context.getString(R.string.date_yesterday)
                    else -> java.text.SimpleDateFormat("MM月dd日", java.util.Locale.getDefault())
                        .format(java.util.Date(message.timestamp))
                }
            } else {
                tvDateSeparator.visibility = View.GONE
            }
        }

        private fun bindUserMessage(message: ChatMessage, timeStr: String, position: Int) {
            layoutUser.visibility = View.VISIBLE
            layoutAi.visibility = View.GONE
            tvUserTime.text = timeStr

            // Search highlight
            if (searchQuery.isNotEmpty() && message.content.lowercase().contains(searchQuery)) {
                tvUserMessage.text = MarkdownRenderer.highlightSearch(message.content, searchQuery)
            } else {
                tvUserMessage.text = message.content
            }

            // Image
            if (message.imageBase64 != null) {
                decodeAndShowImage(message.imageBase64, ivUserImage) { bmp ->
                    cardUserImage.visibility = View.VISIBLE
                    cardUserImage.setOnClickListener {
                        showFullImage(itemView.context, message.originalImageBase64 ?: message.imageBase64)
                    }
                } ?: run { cardUserImage.visibility = View.GONE }
            } else {
                cardUserImage.visibility = View.GONE
            }

            // File
            if (message.fileName != null) {
                tvUserFileName.text = message.fileName
                tvUserFileSize.text = if (message.fileSize > 0) formatFileSize(message.fileSize) else ""
                tvFileIcon.text = getFileEmoji(message.fileName)
                cardUserFile.visibility = View.VISIBLE
                cardUserFile.setOnClickListener { openCachedFile(itemView.context, message.fileName) }
                cardUser.visibility = if (message.content.startsWith("📄 ") || message.content.isEmpty()) View.GONE else View.VISIBLE
            } else {
                cardUserFile.visibility = View.GONE
                cardUser.visibility = View.VISIBLE
            }

            // Hide text bubble for image-only messages
            if (message.imageBase64 != null &&
                (message.content == itemView.context.getString(R.string.chat_image_label) || message.content.isEmpty())) {
                cardUser.visibility = View.GONE
            }

            // Retry
            if (message.sendFailed) {
                tvRetryHint.visibility = View.VISIBLE
                tvRetryHint.setOnClickListener { onRetryClick?.invoke(position, message) }
            } else {
                tvRetryHint.visibility = View.GONE
                tvRetryHint.setOnClickListener(null)
            }

            itemView.setOnLongClickListener {
                copyToClipboard(itemView.context, message.content)
                true
            }
        }

        private fun bindAiMessage(message: ChatMessage, timeStr: String, position: Int) {
            layoutUser.visibility = View.GONE
            layoutAi.visibility = View.VISIBLE
            tvAiTime.text = timeStr
            lastStreamingContent = null // 重置流式缓存

            // Markdown 渲染（完整版本）
            tvAiMessage.text = MarkdownRenderer.render(itemView.context, message.content)
            tvAiMessage.movementMethod = LinkMovementMethod.getInstance()

            // Bot name + emoji
            val prefs = itemView.context.getSharedPreferences("openclaw_prefs", 0)
            val sid = message.sessionId ?: ""
            val botName = prefs.getString(if (sid.isNotEmpty()) "bot_name_$sid" else "bot_name", null)
                ?: prefs.getString("bot_name", "OpenClaw") ?: "OpenClaw"
            val botEmoji = prefs.getString(if (sid.isNotEmpty()) "bot_emoji_$sid" else "bot_emoji", null)
                ?: prefs.getString("bot_emoji", "🦞") ?: "🦞"
            tvAiLabel.text = botName
            tvAvatarEmoji.text = botEmoji
            cardAvatar.setOnClickListener { showEditBotDialog(itemView.context, sid) }

            // Model tag
            if (message.model.isNotEmpty()) {
                tvModelTag.text = message.model
                tvModelTag.visibility = View.VISIBLE
            } else {
                tvModelTag.visibility = View.GONE
            }

            // TTS
            val hasText = message.content.isNotBlank() && !message.content.startsWith("📄 ")
            if (hasText) {
                btnTts.visibility = View.VISIBLE
                val isThisSpeaking = isTtsSpeaking && position == speakingPosition
                btnTts.setImageResource(
                    if (isThisSpeaking) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_lock_silent_mode_off
                )
                btnTts.setOnClickListener { onTtsClick?.invoke(message.content, position) }
            } else {
                btnTts.visibility = View.GONE
                btnTts.setOnClickListener(null)
            }

            // AI Image
            if (message.imageBase64 != null) {
                decodeAndShowImage(message.imageBase64, ivAiImage) {
                    ivAiImage.visibility = View.VISIBLE
                    ivAiImage.setOnClickListener { showFullImage(itemView.context, message.imageBase64) }
                } ?: run { ivAiImage.visibility = View.GONE }
            } else {
                ivAiImage.visibility = View.GONE
            }

            // Tool log
            bindToolLog(message, position)

            // Long press
            tvAiMessage.setOnLongClickListener {
                copyToClipboard(itemView.context, message.content)
                true
            }
        }

        private fun bindToolLog(message: ChatMessage, position: Int) {
            if (message.toolLog.isNullOrEmpty() || message.toolLog == "[]") {
                layoutToolLog.visibility = View.GONE
                return
            }

            layoutToolLog.visibility = View.VISIBLE
            val logArr = try { JSONArray(message.toolLog) } catch (_: Exception) { null }
            if (logArr == null) {
                layoutToolLog.visibility = View.GONE
                return
            }

            val toolCount = logArr.length()
            val skillCount = (0 until toolCount).count {
                logArr.optJSONObject(it)?.optString("category") == "skill"
            }
            val coreCount = toolCount - skillCount

            tvToolSummary.text = when {
                skillCount > 0 && coreCount > 0 -> "🔧 $coreCount + 📦 $skillCount | ${message.steps} steps"
                skillCount > 0 -> "📦 $skillCount skill calls | ${message.steps} steps"
                else -> itemView.context.getString(R.string.tool_calls_count, toolCount, message.steps)
            }

            tvToolDetail.text = buildToolDetail(message.toolLog)
            val isExpanded = expandedToolLogs.contains(position)
            tvToolDetail.visibility = if (isExpanded) View.VISIBLE else View.GONE

            layoutToolLog.setOnClickListener {
                if (expandedToolLogs.contains(position)) {
                    expandedToolLogs.remove(position)
                    tvToolDetail.visibility = View.GONE
                } else {
                    expandedToolLogs.add(position)
                    tvToolDetail.visibility = View.VISIBLE
                }
            }
        }

        // Lazy-loaded config (avoids allocation on every bind)
        private val markdownConfig by lazy { MarkdownRenderer.defaultConfig(itemView.context) }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun decodeAndShowImage(base64: String, imageView: ImageView, onSuccess: (Bitmap) -> Unit): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            imageView.setImageBitmap(bitmap)
            onSuccess(bitmap)
            bitmap
        } catch (_: Exception) { null }
    }

    private fun getFileEmoji(fileName: String): String = when {
        fileName.endsWith(".pdf") -> "📄"
        fileName.endsWith(".doc") || fileName.endsWith(".docx") -> "🗒️"
        fileName.endsWith(".xls") || fileName.endsWith(".xlsx") -> "📊"
        fileName.endsWith(".txt") || fileName.endsWith(".md") -> "📝"
        fileName.endsWith(".json") -> "💾"
        else -> "📄"
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1048576 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun buildToolDetail(toolLogJson: String): String {
        return try {
            val arr = JSONArray(toolLogJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val tool = obj.optString("tool", "?")
                val category = obj.optString("category", "core")
                val preview = obj.optString("result_preview", "").take(80)
                val icon = if (category == "skill") "📦" else "🔧"
                val label = if (category == "skill") "Skill" else "Tool"
                sb.append("$icon $label: $tool")
                if (preview.isNotEmpty()) sb.append("\n   $preview")
                if (i < arr.length() - 1) sb.append("\n")
            }
            sb.toString()
        } catch (_: Exception) { toolLogJson }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun showFullImage(context: Context, base64: String) {
        try {
            val intent = Intent(context, ImageViewerActivity::class.java).apply {
                putExtra("image_base64", base64)
            }
            if (context is android.app.Activity) {
                context.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.image_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCachedFile(context: Context, fileName: String) {
        try {
            val file = File(context.cacheDir, "docs/$fileName")
            val actualFile = if (file.exists()) file
            else File(context.cacheDir, "docs").listFiles()?.firstOrNull { it.name == fileName }

            if (actualFile == null) {
                Toast.makeText(context, context.getString(R.string.chat_file_not_found, fileName), Toast.LENGTH_LONG).show()
                return
            }

            if (fileName.endsWith(".pdf")) {
                val intent = Intent(context, PdfViewerActivity::class.java).apply {
                    putExtra("file_path", actualFile.absolutePath)
                }
                if (context is android.app.Activity) context.startActivity(intent)
                else { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent) }
                return
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", actualFile)
            val mime = when {
                fileName.endsWith(".txt") || fileName.endsWith(".md") -> "text/plain"
                fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".html") || fileName.endsWith(".htm") -> "text/html"
                fileName.endsWith(".doc") -> "application/msword"
                fileName.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                fileName.endsWith(".csv") -> "text/csv"
                else -> "*/*"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.chat_file_open_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ─── Bot Edit Dialog (extracted from bind for clarity) ───────────────

    private fun showEditBotDialog(context: Context, sessionId: String) {
        val prefs = context.getSharedPreferences("openclaw_prefs", 0)
        val nameKey = if (sessionId.isNotEmpty()) "bot_name_$sessionId" else "bot_name"
        val emojiKey = if (sessionId.isNotEmpty()) "bot_emoji_$sessionId" else "bot_emoji"
        val currentName = prefs.getString(nameKey, null) ?: prefs.getString("bot_name", "OpenClaw") ?: "OpenClaw"
        val currentEmoji = prefs.getString(emojiKey, null) ?: prefs.getString("bot_emoji", "🦞") ?: "🦞"

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 30, 60, 10)
        }

        val emojiLabel = android.widget.TextView(context).apply {
            text = context.getString(R.string.avatar_emoji_label); textSize = 12f; setTextColor(0xFF888888.toInt())
        }
        layout.addView(emojiLabel)

        val emojiOptions = arrayOf("🦞", "🤖", "👾", "🐱", "🐶", "🦊", "🐧", "🦉", "🐲", "🦄", "💀", "👻", "✨", "🔥", "🌟")
        val emojiRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 20)
        }

        var selectedEmoji = currentEmoji
        val emojiViews = mutableListOf<android.widget.TextView>()
        for (emoji in emojiOptions) {
            val tv = android.widget.TextView(context).apply {
                text = emoji; textSize = 24f; setPadding(12, 8, 12, 8)
                setOnClickListener {
                    selectedEmoji = emoji
                    emojiViews.forEach { v -> v.setBackgroundColor(0x00000000) }
                    setBackgroundColor(0x206750A4)
                }
                if (emoji == currentEmoji) setBackgroundColor(0x206750A4)
            }
            emojiViews.add(tv); emojiRow.addView(tv)
        }

        layout.addView(android.widget.HorizontalScrollView(context).apply { addView(emojiRow) })

        val nameLabel = android.widget.TextView(context).apply {
            text = context.getString(R.string.avatar_name_label); textSize = 12f; setTextColor(0xFF888888.toInt())
        }
        layout.addView(nameLabel)

        val nameInput = android.widget.EditText(context).apply {
            setText(currentName); setSelection(text.length); hint = context.getString(R.string.avatar_name_hint)
        }
        layout.addView(nameInput)

        val provLabel = android.widget.TextView(context).apply {
            text = "\n${context.getString(R.string.avatar_provider_label)}"; textSize = 12f; setTextColor(0xFF888888.toInt())
        }
        layout.addView(provLabel)

        val provKey = if (sessionId.isNotEmpty()) "session_provider_$sessionId" else "current_provider"
        val modelKey = if (sessionId.isNotEmpty()) "session_model_$sessionId" else "current_model"
        val currentProv = prefs.getString(provKey, null) ?: "bailian"
        val currentModel = prefs.getString(modelKey, null) ?: prefs.getString("current_model", "qwen3.5-plus") ?: "qwen3.5-plus"

        val provsJson = prefs.getString("configured_providers", null)
        val provs = if (provsJson != null) {
            try { val arr = JSONArray(provsJson); Array(arr.length()) { arr.getString(it) } }
            catch (_: Exception) { arrayOf("bailian", "openai", "anthropic", "deepseek") }
        } else arrayOf("bailian", "openai", "anthropic", "deepseek")

        val provsList = provs.toMutableList()
        if (currentProv.isNotEmpty() && currentProv !in provsList) provsList.add(currentProv)
        val finalProvs = provsList.toTypedArray()

        val provSpinner = android.widget.Spinner(context)
        provSpinner.adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, finalProvs)
        provSpinner.setSelection(finalProvs.indexOf(currentProv).coerceAtLeast(0))
        layout.addView(provSpinner)

        val modelLabel = android.widget.TextView(context).apply {
            text = "\n${context.getString(R.string.avatar_model_label)}"; textSize = 12f; setTextColor(0xFF888888.toInt())
        }
        layout.addView(modelLabel)

        val modelSpinner = android.widget.Spinner(context)
        layout.addView(modelSpinner)

        fun getModelsForProvider(provName: String): Array<String> {
            val json = prefs.getString("provider_models_$provName", null)
            val list = if (json != null) {
                try { val arr = JSONArray(json); Array(arr.length()) { arr.getString(it) } }
                catch (_: Exception) { emptyArray() }
            } else emptyArray()
            val result = list.toMutableList()
            if (provName == currentProv && currentModel.isNotEmpty() && currentModel !in result) result.add(currentModel)
            return if (result.isEmpty()) arrayOf("(none)") else result.toTypedArray()
        }

        fun updateModelSpinner(provName: String) {
            val provModels = getModelsForProvider(provName)
            modelSpinner.adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, provModels)
            modelSpinner.setSelection(provModels.indexOf(currentModel).coerceAtLeast(0))
        }
        updateModelSpinner(currentProv)

        provSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                updateModelSpinner(finalProvs[pos])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        android.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.avatar_edit_title))
            .setView(layout)
            .setPositiveButton(context.getString(R.string.save)) { _, _ ->
                val newName = nameInput.text.toString().trim()
                val selectedProvModels = getModelsForProvider(finalProvs[provSpinner.selectedItemPosition])
                val newModel = selectedProvModels[modelSpinner.selectedItemPosition]
                val newProv = finalProvs[provSpinner.selectedItemPosition]
                if (newName.isNotEmpty()) {
                    prefs.edit()
                        .putString(nameKey, newName).putString(emojiKey, selectedEmoji)
                        .putString(modelKey, newModel).putString(provKey, newProv)
                        .apply()
                    notifyDataSetChanged()
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }
}
