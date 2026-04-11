package ai.openclaw.poc

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import android.text.method.LinkMovementMethod
import android.text.TextPaint
import android.view.View
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Markdown 渲染器
 * 
 * 将 Markdown 文本转换为 Android Spanned，支持：
 * - 代码块（带语法高亮背景 + 复制按钮占位）
 * - 行内代码
 * - **加粗**
 * - [链接](url) + 裸链接
 * - 标题 (h1-h3)
 * - 列表（有序/无序）
 * - 搜索高亮
 * 
 * v1.6.0 从 MessageAdapter 抽取
 */
object MarkdownRenderer {

    data class RenderConfig(
        val codeBlockBg: Int,
        val codeBlockText: Int,
        val inlineCodeBg: Int,
        val inlineCodeText: Int,
        val linkColor: Int,
        val copyCodeLabel: String
    )

    fun defaultConfig(context: Context): RenderConfig = RenderConfig(
        codeBlockBg = ContextCompat.getColor(context, R.color.code_block_bg),
        codeBlockText = ContextCompat.getColor(context, R.color.code_block_text),
        inlineCodeBg = ContextCompat.getColor(context, R.color.code_bg),
        inlineCodeText = ContextCompat.getColor(context, R.color.code_text),
        linkColor = ContextCompat.getColor(context, R.color.md_theme_primary),
        copyCodeLabel = context.getString(R.string.copy_code)
    )

    /**
     * 完整渲染 Markdown → Spanned
     * 
     * 注意：此方法包含 ClickableSpan（代码块复制按钮），需要配合
     * LinkMovementMethod 使用。
     */
    fun render(context: Context, text: String, config: RenderConfig = defaultConfig(context)): CharSequence {
        val trimmed = text.trimStart('\n', '\r').trimEnd()
        if (trimmed.isEmpty()) return trimmed

        val ssb = SpannableStringBuilder()
        val lines = trimmed.split("\n")

        var inCodeBlock = false
        val codeBlockContent = StringBuilder()

        for (line in lines) {
            // Code block toggle
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    inCodeBlock = false
                    appendCodeBlock(ssb, codeBlockContent.toString().trimEnd(), config)
                    codeBlockContent.clear()
                } else {
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
            when {
                line.startsWith("### ") -> {
                    appendHeader(ssb, line.substring(4), 1.05f)
                    continue
                }
                line.startsWith("## ") -> {
                    appendHeader(ssb, line.substring(3), 1.1f)
                    continue
                }
                line.startsWith("# ") -> {
                    appendHeader(ssb, line.substring(2), 1.15f)
                    continue
                }
            }

            // Bullet list
            val trimmedLine = line.trimStart()
            if (trimmedLine.startsWith("- ") || trimmedLine.startsWith("• ") || trimmedLine.startsWith("* ")) {
                ssb.append("  • ")
                renderInline(ssb, trimmedLine.substring(2), config)
                ssb.append("\n")
                continue
            }

            // Numbered list
            val numMatch = Regex("^(\\d+)[.)\\s]\\s*(.*)").find(trimmedLine)
            if (numMatch != null) {
                ssb.append("  ${numMatch.groupValues[1]}. ")
                renderInline(ssb, numMatch.groupValues[2], config)
                ssb.append("\n")
                continue
            }

            // Normal line
            renderInline(ssb, line, config)
            ssb.append("\n")
        }

        // Handle unclosed code block
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            appendCodeBlock(ssb, codeBlockContent.toString().trimEnd(), config)
        }

        // Trim trailing newlines
        while (ssb.isNotEmpty() && ssb[ssb.length - 1] == '\n') {
            ssb.delete(ssb.length - 1, ssb.length)
        }

        return ssb
    }

    /**
     * 轻量渲染 — 仅渲染基本格式（加粗、行内代码），无 ClickableSpan
     * 
     * 用于 SSE 流式输出时的实时更新，避免 ClickableSpan 带来的性能开销。
     * 流式结束时再调用完整 render() 替换。
     */
    fun renderLite(text: String, config: RenderConfig): CharSequence {
        val trimmed = text.trimStart('\n', '\r').trimEnd()
        if (trimmed.isEmpty()) return trimmed

        val ssb = SpannableStringBuilder()
        val lines = trimmed.split("\n")

        var inCodeBlock = false
        val codeBlockContent = StringBuilder()

        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    inCodeBlock = false
                    val codeContent = codeBlockContent.toString().trimEnd()
                    val start = ssb.length
                    ssb.append(codeContent)
                    applyCodeBlockSpans(ssb, start, ssb.length, config)
                    codeBlockContent.clear()
                } else {
                    inCodeBlock = true
                    codeBlockContent.clear()
                }
                continue
            }
            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                continue
            }
            renderInline(ssb, line, config)
            ssb.append("\n")
        }

        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            val start = ssb.length
            ssb.append(codeBlockContent.toString().trimEnd())
            applyCodeBlockSpans(ssb, start, ssb.length, config)
        }

        while (ssb.isNotEmpty() && ssb[ssb.length - 1] == '\n') {
            ssb.delete(ssb.length - 1, ssb.length)
        }

        return ssb
    }

    /**
     * 搜索高亮 — 在已有 Spanned 基础上叠加搜索匹配
     */
    fun highlightSearch(original: CharSequence, query: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(original)
        if (query.isEmpty()) return ssb

        val text = original.toString()
        val lower = text.lowercase()
        val lowerQuery = query.lowercase()
        var start = 0
        while (true) {
            val idx = lower.indexOf(lowerQuery, start)
            if (idx < 0) break
            ssb.setSpan(
                BackgroundColorSpan(0x60FFEB3B),
                idx, idx + query.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = idx + query.length
        }
        return ssb
    }

    // ─── Internal ────────────────────────────────────────────────────────

    private fun appendCodeBlock(ssb: SpannableStringBuilder, content: String, config: RenderConfig) {
        val start = ssb.length
        ssb.append(content)
        val end = ssb.length
        applyCodeBlockSpans(ssb, start, end, config)
        ssb.append("\n")
        // Copy button
        val copyStart = ssb.length
        ssb.append("[${config.copyCodeLabel}]")
        val copyEnd = ssb.length
        ssb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                copyToClipboard(widget.context, content)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = config.linkColor
                ds.isUnderlineText = true
            }
        }, copyStart, copyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun applyCodeBlockSpans(ssb: SpannableStringBuilder, start: Int, end: Int, config: RenderConfig) {
        ssb.setSpan(BackgroundColorSpan(config.codeBlockBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(ForegroundColorSpan(config.codeBlockText), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(RelativeSizeSpan(0.88f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendHeader(ssb: SpannableStringBuilder, text: String, size: Float) {
        val start = ssb.length
        ssb.append(text)
        ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(RelativeSizeSpan(size), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.append("\n")
    }

    private fun renderInline(ssb: SpannableStringBuilder, text: String, config: RenderConfig) {
        if (text.isEmpty()) return
        var i = 0
        val len = text.length
        while (i < len) {
            when {
                // **bold**
                i + 1 < len && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        val start = ssb.length
                        ssb.append(text.substring(i + 2, end))
                        ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else { ssb.append(text[i]); i++ }
                }
                // `code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        val start = ssb.length
                        ssb.append(" ${text.substring(i + 1, end)} ")
                        ssb.setSpan(BackgroundColorSpan(config.inlineCodeBg), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(ForegroundColorSpan(config.inlineCodeText), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(TypefaceSpan("monospace"), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(RelativeSizeSpan(0.9f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else { ssb.append(text[i]); i++ }
                }
                // [link](url)
                text[i] == '[' -> {
                    val cb = text.indexOf(']', i + 1)
                    if (cb > 0 && cb + 1 < len && text[cb + 1] == '(') {
                        val cp = text.indexOf(')', cb + 2)
                        if (cp > 0) {
                            val url = text.substring(cb + 2, cp)
                            val start = ssb.length
                            ssb.append(text.substring(i + 1, cb))
                            ssb.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) { openLink(widget.context, url) }
                                override fun updateDrawState(ds: TextPaint) {
                                    ds.color = config.linkColor; ds.isUnderlineText = true
                                }
                            }, start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            i = cp + 1; continue
                        }
                    }
                    ssb.append(text[i]); i++
                }
                // bare http(s)
                text.substring(i).startsWith("http://") || text.substring(i).startsWith("https://") -> {
                    val end = text.indexOf(' ', i).takeIf { it > 0 } ?: len
                    val url = text.substring(i, end)
                    val start = ssb.length
                    ssb.append(url)
                    ssb.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { openLink(widget.context, url) }
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = config.linkColor; ds.isUnderlineText = true
                        }
                    }, start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end
                }
                else -> { ssb.append(text[i]); i++ }
            }
        }
    }

    private fun openLink(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.link_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("code", text))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }
}
