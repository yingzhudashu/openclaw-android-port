package ai.openclaw.poc

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SettingDetailFragment : Fragment() {

    companion object {
        private const val BASE_URL = "http://127.0.0.1:18789"

        private val HINTS = mapOf(
            "SOUL.md" to R.string.settings_hint_soul,
            "USER.md" to R.string.settings_hint_user,
            "HEARTBEAT.md" to R.string.settings_hint_heartbeat,
            "AGENTS.md" to R.string.settings_hint_agents,
            "TOOLS.md" to R.string.settings_hint_tools,
        )

        fun newInstance(fileName: String, title: String): SettingDetailFragment {
            return SettingDetailFragment().apply {
                arguments = Bundle().apply {
                    putString("file_name", fileName)
                    putString("title", title)
                }
            }
        }
    }

    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnSave: MaterialButton
    private lateinit var cardHint: View
    private lateinit var tvHint: TextView
    private lateinit var etContent: EditText
    private lateinit var tvCharCount: TextView
    private lateinit var tvFileInfo: TextView

    private var fileName = ""
    private var originalContent = ""
    private var hasChanges = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_setting_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fileName = arguments?.getString("file_name", "") ?: ""
        val title = arguments?.getString("title", fileName) ?: fileName

        tvTitle = view.findViewById(R.id.tvDetailTitle)
        btnBack = view.findViewById(R.id.btnBack)
        btnSave = view.findViewById(R.id.btnSave)
        cardHint = view.findViewById(R.id.cardHint)
        tvHint = view.findViewById(R.id.tvHint)
        etContent = view.findViewById(R.id.etContent)
        tvCharCount = view.findViewById(R.id.tvCharCount)
        tvFileInfo = view.findViewById(R.id.tvFileInfo)

        tvTitle.text = title
        tvFileInfo.text = fileName

        // Show hint if available
        val hintRes = HINTS[fileName]
        if (hintRes != null) {
            cardHint.visibility = View.VISIBLE
            tvHint.text = getString(hintRes)
        }

        // Back button
        btnBack.setOnClickListener {
            if (hasChanges) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_unsaved_title))
                    .setMessage(getString(R.string.settings_unsaved_msg))
                    .setPositiveButton(getString(R.string.settings_discard)) { _, _ ->
                        parentFragmentManager.popBackStack()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        // Save button
        btnSave.setOnClickListener { saveFile() }
        btnSave.isEnabled = false

        // Track changes
        etContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString() ?: ""
                hasChanges = current != originalContent
                btnSave.isEnabled = hasChanges
                btnSave.setTextColor(if (hasChanges) 0xFF4CAF50.toInt() else 0xFFBDBDBD.toInt())
                tvCharCount.text = getString(R.string.settings_editor_chars, current.length)
            }
        })

        // Load content
        loadFile()
    }

    private fun loadFile() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { httpGet("$BASE_URL/api/files/$fileName") }
                val content = resp.optString("content", "")
                originalContent = content
                etContent.setText(content)
                tvCharCount.text = getString(R.string.settings_editor_chars, content.length)
                hasChanges = false
                btnSave.isEnabled = false
            } catch (e: Exception) {
                etContent.setText("")
                originalContent = ""
            }
        }
    }

    private fun saveFile() {
        val content = etContent.text.toString()
        btnSave.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    httpPost("$BASE_URL/api/files/$fileName", JSONObject().apply { put("content", content) })
                }
                originalContent = content
                hasChanges = false
                btnSave.isEnabled = false
                view?.let { Snackbar.make(it, getString(R.string.settings_saved), Snackbar.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                btnSave.isEnabled = true
                view?.let { Snackbar.make(it, "❌ ${e.message}", Snackbar.LENGTH_SHORT).show() }
            }
        }
    }

    private fun httpGet(urlStr: String): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.readTimeout = 5000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = if (stream != null) BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() } else "HTTP $code"
        conn.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code: $body")
        return JSONObject(body)
    }

    private fun httpPost(urlStr: String, json: JSONObject) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true; conn.connectTimeout = 5000; conn.readTimeout = 10000
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        stream?.close()
        conn.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code")
    }
}
