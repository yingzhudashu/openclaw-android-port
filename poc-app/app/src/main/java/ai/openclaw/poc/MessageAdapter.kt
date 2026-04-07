package ai.openclaw.poc

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import android.text.method.LinkMovementMethod
import android.text.TextPaint
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
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
    val imageBase64: String? = null,        // 缩略图（显示用）
    val originalImageBase64: String? = null, // 原图（点击放大用）
    val fileName: String? = null,
    val fileSize: Long = 0,
    val toolLog: String? = null,
    val steps: Int = 0,
    val sessionId: String? = null,
    val sendFailed: Boolean = false
)

/**
 * 消息列表 RecyclerView 适配器
 * 
 * 支持：Markdown 渲染、图片、文件附件、工具调用折叠
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val expandedToolLogs = mutableSetOf<Int>() // Track expanded tool logs
    var onRetryClick: ((Int, ChatMessage) -> Unit)? = null

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

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
        notifyItemRangeRemoved(0, size)
    }

    private var searchQuery: String = ""

    fun highlightSearch(query: String): Int {
        searchQuery = query.lowercase()
        notifyDataSetChanged()
        // Return first matching position
        for (i in messages.indices.reversed()) {
            if (messages[i].content.lowercase().contains(searchQuery)) return i
        }
        return -1
    }

    private fun highlightMatches(ssb: SpannableStringBuilder, text: String, query: String) {
        val lower = text.lowercase()
        var start = 0
        while (true) {
            val idx = lower.indexOf(query, start)
            if (idx < 0) break
            ssb.setSpan(
                BackgroundColorSpan(0x60FFEB3B),  // Semi-transparent yellow
                idx, idx + query.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = idx + query.length
        }
    }

    fun clearHighlight() {
        searchQuery = ""
        notifyDataSetChanged()
    }

    fun getMessageCount(): Int = messages.size
    fun getAllMessages(): List<ChatMessage> = messages.toList()

    fun getMessageAt(position: Int): ChatMessage? {
        return messages.getOrNull(position)
    }

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
        holder.bind(messages[position], position)
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // 用户消息
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

        // AI 消息
        private val layoutAi: View = itemView.findViewById(R.id.layoutAi)
        private val tvAiLabel: TextView = itemView.findViewById(R.id.tvAiLabel)
        private val tvAiMessage: TextView = itemView.findViewById(R.id.tvAiMessage)
        private val tvAiTime: TextView = itemView.findViewById(R.id.tvAiTime)
        private val ivAiImage: ImageView = itemView.findViewById(R.id.ivAiImage)
        private val layoutToolLog: LinearLayout = itemView.findViewById(R.id.layoutToolLog)
        private val tvToolSummary: TextView = itemView.findViewById(R.id.tvToolSummary)
        private val tvToolDetail: TextView = itemView.findViewById(R.id.tvToolDetail)
        private val tvModelTag: TextView = itemView.findViewById(R.id.tvModelTag)
        private val cardAvatar: View = itemView.findViewById(R.id.cardAvatar)
        private val tvAvatarEmoji: TextView = itemView.findViewById(R.id.tvAvatarEmoji)

        fun bind(message: ChatMessage, position: Int) {
            val timeStr = timeFormat.format(Date(message.timestamp))

            if (message.isUser) {
                layoutUser.visibility = View.VISIBLE
                layoutAi.visibility = View.GONE
                tvUserMessage.text = message.content
                // Search highlight for user messages
                if (searchQuery.isNotEmpty() && message.content.lowercase().contains(searchQuery)) {
                    val ssb = SpannableStringBuilder(message.content)
                    highlightMatches(ssb, message.content, searchQuery)
                    tvUserMessage.text = ssb
                }
                tvUserTime.text = timeStr

                // 图片（微信风格：圆角卡片，点击看原图）
                if (message.imageBase64 != null) {
                    try {
                        val bytes = Base64.decode(message.imageBase64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ivUserImage.setImageBitmap(bitmap)
                        cardUserImage.visibility = View.VISIBLE
                        cardUserImage.setOnClickListener {
                            showFullImage(itemView.context, message.originalImageBase64 ?: message.imageBase64)
                        }
                    } catch (_: Exception) {
                        cardUserImage.visibility = View.GONE
                    }
                } else {
                    cardUserImage.visibility = View.GONE
                }

                // 文件（微信风格：白底卡片 + 图标 + 文件名 + 大小）
                if (message.fileName != null) {
                    tvUserFileName.text = message.fileName
                    tvUserFileSize.text = if (message.fileSize > 0) formatFileSize(message.fileSize) else ""
                    // 根据文件类型设置图标
                    tvFileIcon.text = when {
                        message.fileName.endsWith(".pdf") -> "📄"
                        message.fileName.endsWith(".doc") || message.fileName.endsWith(".docx") -> "🗒️"
                        message.fileName.endsWith(".xls") || message.fileName.endsWith(".xlsx") -> "📊"
                        message.fileName.endsWith(".txt") || message.fileName.endsWith(".md") -> "📝"
                        message.fileName.endsWith(".json") -> "💾"
                        else -> "📄"
                    }
                    cardUserFile.visibility = View.VISIBLE
                    cardUserFile.setOnClickListener {
                        openCachedFile(itemView.context, message.fileName)
                    }
                    // 文件消息不显示文本气泡
                    if (message.content.startsWith("📄 ") || message.content.isEmpty()) {
                        cardUser.visibility = View.GONE
                    } else {
                        cardUser.visibility = View.VISIBLE
                    }
                } else {
                    cardUserFile.visibility = View.GONE
                    cardUser.visibility = View.VISIBLE
                }

                // 图片消息不显示文本气泡
                if (message.imageBase64 != null && (message.content == itemView.context.getString(R.string.chat_image_label) || message.content.isEmpty())) {
                    cardUser.visibility = View.GONE
                }

                // Retry hint for failed messages
                if (message.sendFailed) {
                    tvRetryHint.visibility = View.VISIBLE
                    tvRetryHint.setOnClickListener { onRetryClick?.invoke(position, message) }
                } else {
                    tvRetryHint.visibility = View.GONE
                    tvRetryHint.setOnClickListener(null)
                }

                // 长按复制
                itemView.setOnLongClickListener {
                    copyToClipboard(itemView.context, message.content)
                    true
                }

            } else {
                layoutUser.visibility = View.GONE
                layoutAi.visibility = View.VISIBLE

                // Markdown 渲染
                tvAiMessage.text = renderMarkdown(itemView.context, message.content)
                tvAiMessage.movementMethod = LinkMovementMethod.getInstance()
                tvAiTime.text = timeStr

                // AI 名字和头像（每个会话独立）
                val prefs = itemView.context.getSharedPreferences("openclaw_prefs", 0)
                val sessionId = message.sessionId ?: ""
                val botName = if (sessionId.isNotEmpty()) {
                    prefs.getString("bot_name_$sessionId", null)
                        ?: prefs.getString("bot_name", "OpenClaw") ?: "OpenClaw"
                } else {
                    prefs.getString("bot_name", "OpenClaw") ?: "OpenClaw"
                }
                val botEmoji = if (sessionId.isNotEmpty()) {
                    prefs.getString("bot_emoji_$sessionId", null)
                        ?: prefs.getString("bot_emoji", "🦞") ?: "🦞"
                } else {
                    prefs.getString("bot_emoji", "🦞") ?: "🦞"
                }
                tvAiLabel.text = botName
                tvAvatarEmoji.text = botEmoji

                // 点击头像修改名称和头像（per-session）
                cardAvatar.setOnClickListener {
                    showEditBotDialog(itemView.context, sessionId)
                }

                // 模型标注（气泡底部小字）
                if (message.model.isNotEmpty()) {
                    tvModelTag.text = message.model
                    tvModelTag.visibility = View.VISIBLE
                } else {
                    tvModelTag.visibility = View.GONE
                }

                // AI 图片（点击放大）
                if (message.imageBase64 != null) {
                    try {
                        val bytes = Base64.decode(message.imageBase64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ivAiImage.setImageBitmap(bitmap)
                        ivAiImage.visibility = View.VISIBLE
                        ivAiImage.setOnClickListener {
                            showFullImage(itemView.context, message.imageBase64)
                        }
                    } catch (_: Exception) {
                        ivAiImage.visibility = View.GONE
                    }
                } else {
                    ivAiImage.visibility = View.GONE
                }

                // 工具调用日志
                if (message.toolLog != null && message.toolLog.isNotEmpty() && message.toolLog != "[]") {
                    layoutToolLog.visibility = View.VISIBLE
                    val toolCount = try {
                        val logArr = org.json.JSONArray(message.toolLog)
                        logArr.length()
                    } catch (_: Exception) { 0 }
                    val skillCount = try {
                        val logArr = org.json.JSONArray(message.toolLog)
                        (0 until logArr.length()).count { logArr.optJSONObject(it)?.optString("category") == "skill" }
                    } catch (_: Exception) { 0 }
                    val coreCount = toolCount - skillCount

                    tvToolSummary.text = if (skillCount > 0 && coreCount > 0) {
                        "🔧 $coreCount + 📦 $skillCount | ${message.steps} steps"
                    } else if (skillCount > 0) {
                        "📦 $skillCount skill calls | ${message.steps} steps"
                    } else {
                        itemView.context.getString(R.string.tool_calls_count, toolCount, message.steps)
                    }

                    // 构建详情
                    val detail = buildToolDetail(message.toolLog)
                    tvToolDetail.text = detail

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
                } else {
                    layoutToolLog.visibility = View.GONE
                }

                // 长按复制
                tvAiMessage.setOnLongClickListener {
                    copyToClipboard(itemView.context, message.content)
                    true
                }
            }
        }
    }

    // ─── Markdown 渲染 ───────────────────────────────────────────────────

    private fun renderMarkdown(context: Context, text: String): CharSequence {
        val trimmed = text.trimStart('\n', '\r').trimEnd()
        if (trimmed.isEmpty()) return trimmed

        val ssb = SpannableStringBuilder()
        val lines = trimmed.split("\n")
        val codeBlockBg = ContextCompat.getColor(context, R.color.code_block_bg)
        val codeBlockText = ContextCompat.getColor(context, R.color.code_block_text)
        val inlineCodeBg = ContextCompat.getColor(context, R.color.code_bg)
        val inlineCodeText = ContextCompat.getColor(context, R.color.code_text)
        val linkColor = ContextCompat.getColor(context, R.color.md_theme_primary)

        var inCodeBlock = false
        val codeBlockContent = StringBuilder()

        for ((idx, line) in lines.withIndex()) {
            // Code block toggle
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    // End code block
                    inCodeBlock = false
                    val codeContent = codeBlockContent.toString().trimEnd()
                    val start = ssb.length
                    ssb.append(codeContent)
                    val end = ssb.length
                    ssb.setSpan(BackgroundColorSpan(codeBlockBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(ForegroundColorSpan(codeBlockText), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(RelativeSizeSpan(0.88f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    codeBlockContent.clear()
                    ssb.append("\n")
                    // 复制按钮
                    val copyStart = ssb.length
                    ssb.append("[${context.getString(R.string.copy_code)}]")
                    val copyEnd = ssb.length
                    ssb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            copyToClipboard(context, codeContent)
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.color = ContextCompat.getColor(context, R.color.md_theme_primary)
                            ds.isUnderlineText = true
                        }
                    }, copyStart, copyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    // Start code block
                    inCodeBlock = true
                    codeBlockContent.clear()
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                continue
            }

            // Headers
            if (line.startsWith("### ")) {
                val start = ssb.length
                ssb.append(line.substring(4))
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(RelativeSizeSpan(1.05f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.append("\n")
                continue
            }
            if (line.startsWith("## ")) {
                val start = ssb.length
                ssb.append(line.substring(3))
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(RelativeSizeSpan(1.1f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.append("\n")
                continue
            }
            if (line.startsWith("# ")) {
                val start = ssb.length
                ssb.append(line.substring(2))
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(RelativeSizeSpan(1.15f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.append("\n")
                continue
            }

            // Bullet list
            val trimmed = line.trimStart()
            if (trimmed.startsWith("- ") || trimmed.startsWith("• ") || trimmed.startsWith("* ")) {
                ssb.append("  • ")
                renderInline(ssb, trimmed.substring(2), inlineCodeBg, inlineCodeText, linkColor)
                ssb.append("\n")
                continue
            }

            // Numbered list
            val numMatch = Regex("^(\\d+)[.)\\s]\\s*(.*)").find(trimmed)
            if (numMatch != null) {
                ssb.append("  ${numMatch.groupValues[1]}. ")
                renderInline(ssb, numMatch.groupValues[2], inlineCodeBg, inlineCodeText, linkColor)
                ssb.append("\n")
                continue
            }

            // Normal line with inline formatting
            renderInline(ssb, line, inlineCodeBg, inlineCodeText, linkColor)
            ssb.append("\n")
        }

        // Handle unclosed code block
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            val codeContent = codeBlockContent.toString().trimEnd()
            val start = ssb.length
            ssb.append(codeContent)
            val end = ssb.length
            ssb.setSpan(BackgroundColorSpan(codeBlockBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append("\n")
            // 复制按钮（未闭合代码块）
            val copyStart = ssb.length
            ssb.append("[${context.getString(R.string.copy_code)}]")
            val copyEnd = ssb.length
            ssb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    copyToClipboard(context, codeContent)
                }
                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(context, R.color.md_theme_primary)
                    ds.isUnderlineText = true
                }
            }, copyStart, copyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append("\n")
        }

        // Trim trailing newlines
        while (ssb.isNotEmpty() && ssb[ssb.length - 1] == '\n') {
            ssb.delete(ssb.length - 1, ssb.length)
        }

        return ssb
    }

    /**
     * 渲染行内格式：**加粗**、`代码`、_斜体_、[链接](url)
     */
    private fun renderInline(ssb: SpannableStringBuilder, text: String, codeBg: Int, codeText: Int, linkColor: Int) {
        var i = 0
        while (i < text.length) {
            when {
                // 加粗 **text**
                i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        val start = ssb.length
                        ssb.append(text.substring(i + 2, end))
                        ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        ssb.append(text[i])
                        i++
                    }
                }
                // 行内代码 `code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        val start = ssb.length
                        ssb.append(" ${text.substring(i + 1, end)} ")
                        ssb.setSpan(BackgroundColorSpan(codeBg), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(ForegroundColorSpan(codeText), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(TypefaceSpan("monospace"), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(RelativeSizeSpan(0.9f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        ssb.append(text[i])
                        i++
                    }
                }
                // Markdown 链接 [text](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > 0 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > 0) {
                            val linkText = text.substring(i + 1, closeBracket)
                            val url = text.substring(closeBracket + 2, closeParen)
                            val start = ssb.length
                            ssb.append(linkText)
                            ssb.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    openLink(widget.context, url)
                                }
                                override fun updateDrawState(ds: TextPaint) {
                                    super.updateDrawState(ds)
                                    ds.color = linkColor
                                    ds.isUnderlineText = true
                                }
                            }, start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = closeParen + 1
                            continue
                        }
                    }
                    ssb.append(text[i])
                    i++
                }
                // 裸链接 http:// 或 https://
                text.substring(i).startsWith("http://") || text.substring(i).startsWith("https://") -> {
                    val end = text.indexOf(' ', i).takeIf { it > 0 } ?: text.length
                    val url = text.substring(i, end)
                    val start = ssb.length
                    ssb.append(url)
                    ssb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            openLink(widget.context, url)
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.color = linkColor
                            ds.isUnderlineText = true
                        }
                    }, start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end
                }
                else -> {
                    ssb.append(text[i])
                    i++
                }
            }
        }
    }

    /**
     * 打开链接
     */
    private fun openLink(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.link_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ─── 工具日志 ────────────────────────────────────────────────────────

    private fun buildToolDetail(toolLogJson: String): String {
        return try {
            val arr = org.json.JSONArray(toolLogJson)
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

    // ─── 剪贴板 ─────────────────────────────────────────────────────────

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    /**
     * 全屏查看图片
     */
    private fun showFullImage(context: Context, base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val iv = ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF000000.toInt())
                setOnClickListener { dialog.dismiss() }
            }
            dialog.setContentView(iv)
            dialog.show()
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.image_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 打开缓存的文件
     */
    private fun openCachedFile(context: Context, fileName: String) {
        try {
            val file = File(context.cacheDir, "docs/$fileName")
            if (!file.exists()) {
                // 尝试找一下实际存在的文件
                val docsDir = File(context.cacheDir, "docs")
                val existing = docsDir.listFiles()?.firstOrNull { it.name == fileName }
                if (existing == null) {
                    Toast.makeText(context, context.getString(R.string.chat_file_not_found, fileName), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.chat_file_found, existing.absolutePath), Toast.LENGTH_SHORT).show()
                }
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = when {
                fileName.endsWith(".txt") || fileName.endsWith(".md") -> "text/plain"
                fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".html") || fileName.endsWith(".htm") -> "text/html"
                fileName.endsWith(".pdf") -> "application/pdf"
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

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1048576 -> String.format("%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /**
     * 点击头像弹出修改名称和头像的对话框
     */
    private fun showEditBotDialog(context: android.content.Context, sessionId: String) {
        val prefs = context.getSharedPreferences("openclaw_prefs", 0)
        val nameKey = if (sessionId.isNotEmpty()) "bot_name_$sessionId" else "bot_name"
        val emojiKey = if (sessionId.isNotEmpty()) "bot_emoji_$sessionId" else "bot_emoji"
        val currentName = prefs.getString(nameKey, null)
            ?: prefs.getString("bot_name", "OpenClaw") ?: "OpenClaw"
        val currentEmoji = prefs.getString(emojiKey, null)
            ?: prefs.getString("bot_emoji", "🦞") ?: "🦞"

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 30, 60, 10)
        }

        // Emoji 选择
        val emojiLabel = android.widget.TextView(context).apply {
            text = context.getString(R.string.avatar_emoji_label)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(emojiLabel)

        val emojiOptions = arrayOf("🦞", "🤖", "👾", "🐱", "🐶", "🦊", "🐧", "🦉", "🐲", "🦄", "💀", "👻", "✨", "🔥", "🌟")
        val emojiRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 20)
        }

        var selectedEmoji = currentEmoji
        val emojiViews = mutableListOf<android.widget.TextView>()

        for (emoji in emojiOptions) {
            val tv = android.widget.TextView(context).apply {
                text = emoji
                textSize = 24f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    selectedEmoji = emoji
                    emojiViews.forEach { v -> v.setBackgroundColor(0x00000000) }
                    setBackgroundColor(0x206750A4)
                }
                if (emoji == currentEmoji) setBackgroundColor(0x206750A4)
            }
            emojiViews.add(tv)
            emojiRow.addView(tv)
        }

        val scrollView = android.widget.HorizontalScrollView(context)
        scrollView.addView(emojiRow)
        layout.addView(scrollView)

        // 名称输入
        val nameLabel = android.widget.TextView(context).apply {
            text = context.getString(R.string.avatar_name_label)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(nameLabel)

        val nameInput = android.widget.EditText(context).apply {
            setText(currentName)
            setSelection(text.length)
            hint = context.getString(R.string.avatar_name_hint)
        }
        layout.addView(nameInput)

        // 供应商选择（先选供应商，模型跟着变）
        val provLabel = android.widget.TextView(context).apply {
            text = "\n${context.getString(R.string.avatar_provider_label)}"; textSize = 12f; setTextColor(0xFF888888.toInt())
        }
        layout.addView(provLabel)

        val provKey = if (sessionId.isNotEmpty()) "session_provider_$sessionId" else "current_provider"
        val modelKey = if (sessionId.isNotEmpty()) "session_model_$sessionId" else "current_model"
        val currentProv = prefs.getString(provKey, null) ?: "bailian"
        val currentModel = prefs.getString(modelKey, null)
            ?: prefs.getString("current_model", "qwen3.5-plus") ?: "qwen3.5-plus"

        val provsJson = prefs.getString("configured_providers", null)
        val provs = if (provsJson != null) {
            try {
                val arr = org.json.JSONArray(provsJson)
                Array(arr.length()) { arr.getString(it) }
            } catch (_: Exception) { arrayOf("bailian", "openai", "anthropic", "deepseek") }
        } else {
            arrayOf("bailian", "openai", "anthropic", "deepseek")
        }
        val provsList = provs.toMutableList()
        if (currentProv.isNotEmpty() && currentProv !in provsList) provsList.add(currentProv)
        val finalProvs = provsList.toTypedArray()

        val provSpinner = android.widget.Spinner(context)
        provSpinner.adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, finalProvs)
        provSpinner.setSelection(finalProvs.indexOf(currentProv).coerceAtLeast(0))
        layout.addView(provSpinner)

        // 模型选择（根据所选供应商动态更新）
        val modelLabel = android.widget.TextView(context).apply {
            text = "\n${context.getString(R.string.avatar_model_label)}"; textSize = 12f; setTextColor(0xFF888888.toInt())
        }
        layout.addView(modelLabel)

        val modelSpinner = android.widget.Spinner(context)
        layout.addView(modelSpinner)

        fun getModelsForProvider(provName: String): Array<String> {
            val json = prefs.getString("provider_models_$provName", null)
            val list = if (json != null) {
                try {
                    val arr = org.json.JSONArray(json)
                    Array(arr.length()) { arr.getString(it) }
                } catch (_: Exception) { emptyArray() }
            } else { emptyArray() }
            val result = list.toMutableList()
            if (provName == currentProv && currentModel.isNotEmpty() && currentModel !in result) result.add(currentModel)
            return if (result.isEmpty()) arrayOf("(none)") else result.toTypedArray()
        }

        fun updateModelSpinner(provName: String) {
            val provModels = getModelsForProvider(provName)
            modelSpinner.adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, provModels)
            val idx = provModels.indexOf(currentModel).coerceAtLeast(0)
            modelSpinner.setSelection(idx)
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
                        .putString(nameKey, newName)
                        .putString(emojiKey, selectedEmoji)
                        .putString(modelKey, newModel)
                        .putString(provKey, newProv)
                        .apply()
                    notifyDataSetChanged()
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }
}
