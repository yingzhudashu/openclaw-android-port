package ai.openclaw.poc

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsFragment : Fragment() {

    companion object {
        private const val TAG = "SettingsFragment"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY = 2000L
    }

    private lateinit var tvModelProviderSummary: TextView
    private lateinit var tvDefaultModelSummary: TextView
    private lateinit var tvSoulSummary: TextView
    private lateinit var tvUserSummary: TextView
    private lateinit var tvHeartbeatSummary: TextView
    private lateinit var tvAgentsSummary: TextView
    private lateinit var tvToolsSummary: TextView
    private lateinit var tvMemorySummary: TextView
    private lateinit var tvSkillsCount: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvLangSummary: TextView
    private lateinit var tvMaxStepsSummary: TextView
    private lateinit var tvEmbeddingSummary: TextView
    private lateinit var tvMemoryModeSummary: TextView
    private lateinit var tvPermissionSummary: TextView
    private lateinit var tvTavilySummary: TextView

    private var cachedMaxSteps = 25
    private var cachedEmbeddingModel = ""
    private var cachedMemoryMode = "basic"

    private var cachedModel = ""
    private val providerConfigs = mutableMapOf<String, Pair<String, String>>()
    private val fileCache = mutableMapOf<String, String>()
    private var cachedSkills = JSONArray()

    private val defaultProviders = arrayOf("bailian", "openai", "anthropic", "deepseek", "siliconflow")
    private val defaultModels = listOf(
        "qwen3.6-plus", "gpt-4o",
        "claude-sonnet-4-6", "deepseek-chat", "qwen3.5-plus"
    )
    // Preset base URLs for known providers
    private val defaultBaseUrls = mapOf(
        "bailian" to "https://coding.dashscope.aliyuncs.com/v1",
        "openai" to "https://api.openai.com/v1",
        "anthropic" to "https://api.anthropic.com/v1",
        "deepseek" to "https://api.deepseek.com/v1",
        "siliconflow" to "https://api.siliconflow.cn/v1"
    )

    // File editing endpoints are now centralized in GatewayApi.FILE_ENDPOINTS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvModelProviderSummary = view.findViewById(R.id.tvModelProviderSummary)
        tvDefaultModelSummary = view.findViewById(R.id.tvDefaultModelSummary)
        tvSoulSummary = view.findViewById(R.id.tvSoulSummary)
        tvUserSummary = view.findViewById(R.id.tvUserSummary)
        tvHeartbeatSummary = view.findViewById(R.id.tvHeartbeatSummary)
        tvAgentsSummary = view.findViewById(R.id.tvAgentsSummary)
        tvToolsSummary = view.findViewById(R.id.tvToolsSummary)
        tvMemorySummary = view.findViewById(R.id.tvMemorySummary)
        tvSkillsCount = view.findViewById(R.id.tvSkillsCount)
        tvVersion = view.findViewById(R.id.tvVersion)
        tvLangSummary = view.findViewById(R.id.tvLangSummary)

        view.findViewById<View>(R.id.cellModelProvider).setOnClickListener { showModelProviderEditor() }
        view.findViewById<View>(R.id.cellDefaultModel).setOnClickListener { showDefaultModelPicker() }
        view.findViewById<View>(R.id.cellSoul).setOnClickListener { editFile("SOUL.md", "👤 SOUL") }
        view.findViewById<View>(R.id.cellUser).setOnClickListener { editFile("USER.md", "🧑 USER") }
        view.findViewById<View>(R.id.cellHeartbeat).setOnClickListener { editFile("HEARTBEAT.md", "💓 HEARTBEAT") }
        view.findViewById<View>(R.id.cellAgents).setOnClickListener { editFile("AGENTS.md", "🤝 AGENTS") }
        view.findViewById<View>(R.id.cellTools).setOnClickListener { editFile("TOOLS.md", "🔧 TOOLS") }
        view.findViewById<View>(R.id.cellMemory).setOnClickListener { editFile("MEMORY.md", "🧠 MEMORY") }
        view.findViewById<View>(R.id.cellSkillsList).setOnClickListener { showSkillsList() }
        view.findViewById<View>(R.id.cellSkillInstall).setOnClickListener { showSkillInstaller() }
        view.findViewById<View>(R.id.cellClearCache).setOnClickListener { clearEngineCache() }
        view.findViewById<View>(R.id.cellEmbedding).setOnClickListener { showEmbeddingEditor() }
        view.findViewById<View>(R.id.cellTavily).setOnClickListener { showTavilyEditor() }
        view.findViewById<View>(R.id.cellMemoryMode).setOnClickListener { showMemoryModePicker() }
        view.findViewById<View>(R.id.cellBackup).setOnClickListener { performBackup() }
        view.findViewById<View>(R.id.cellRestore).setOnClickListener { pickRestoreFile() }

        // Dark mode toggle
        val switchDarkMode = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchDarkMode)
        val nightMode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        switchDarkMode.isChecked = nightMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                       else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            requireContext().getSharedPreferences("openclaw_prefs", 0).edit()
                .putBoolean("dark_mode", isChecked).apply()
        }
        view.findViewById<View>(R.id.cellLanguage).setOnClickListener { showLanguagePicker() }
        view.findViewById<View>(R.id.cellMaxSteps).setOnClickListener { showMaxStepsEditor() }
        view.findViewById<View>(R.id.cellPermissions).setOnClickListener { showPermissionManager() }

        // Heartbeat toggle
        val switchHeartbeat = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchHeartbeat)
        switchHeartbeat.isChecked = HeartbeatWorker.isEnabled(requireContext())
        switchHeartbeat.setOnCheckedChangeListener { _, isChecked ->
            HeartbeatWorker.setEnabled(requireContext(), isChecked)
            snack(if (isChecked) getString(R.string.heartbeat_enabled) else getString(R.string.heartbeat_disabled))
        }

        tvMaxStepsSummary = view.findViewById(R.id.tvMaxStepsSummary)
        tvEmbeddingSummary = view.findViewById(R.id.tvEmbeddingSummary)
        tvMemoryModeSummary = view.findViewById(R.id.tvMemoryModeSummary)
        tvPermissionSummary = view.findViewById(R.id.tvPermissionSummary)
        tvTavilySummary = view.findViewById(R.id.tvTavilySummary)

        try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            tvVersion.text = "v${info.versionName}"
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getPackageInfo failed", e)
        }

        // (comment)
        val lang = LocaleHelper.getLanguage(requireContext())
        tvLangSummary.text = if (lang == "en") getString(R.string.settings_language_en) else getString(R.string.settings_language_zh)

        loadAllData()
    }

    private fun loadAllData() {
        viewLifecycleOwner.lifecycleScope.launch {
            retryLoad { loadConfig() }
            retryLoad { loadAllFiles() }
            retryLoad { loadSkills() }
            updatePermissionSummary()
        }
    }

    private fun updatePermissionSummary() {
        tvPermissionSummary.text = PermissionManager.getSummary(requireContext())
    }

    private fun showPermissionManager() {
        val status = PermissionManager.getAllPermissionStatus(requireContext())
        val items = status.map { s ->
            "${if (s.granted) "✅" else "❌"} ${s.name}"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("🔒 权限管理")
            .setItems(items) { _, which ->
                val s = status[which]
                if (s.granted) {
                    // Already granted - offer to go to settings
                    AlertDialog.Builder(requireContext())
                        .setTitle(s.name)
                        .setMessage("${s.name} 已授权。\n如需修改，请前往系统设置。")
                        .setPositiveButton("去系统设置") { _, _ ->
                            PermissionManager.openAppSettings(requireContext())
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    // Not granted - request permission
                    AlertDialog.Builder(requireContext())
                        .setTitle(s.name)
                        .setMessage("${s.rationale}\n\n即将请求权限，请在系统弹窗中允许。")
                        .setPositiveButton("去授权") { _, _ ->
                            val type = when (which) {
                                0 -> "location"
                                1 -> "camera"
                                2 -> "audio"
                                3 -> "notification"
                                4 -> "storage"
                                else -> ""
                            }
                            if (type.isNotEmpty()) {
                                PermissionManager.requestPermission(requireActivity(), type)
                            }
                        }
                        .setNeutralButton("系统设置") { _, _ ->
                            PermissionManager.openAppSettings(requireContext())
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNeutralButton("打开系统设置") { _, _ ->
                PermissionManager.openAppSettings(requireContext())
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private suspend fun retryLoad(block: suspend () -> Unit) {
        var retries = MAX_RETRIES
        while (retries > 0) {
            try { block(); return } catch (_: Exception) { retries--; if (retries > 0) delay(RETRY_DELAY) }
        }
    }

    private suspend fun loadConfig() {
        val config = withContext(Dispatchers.IO) { GatewayApi.getConfig() }
        cachedModel = config.optString("model", "")

        providerConfigs.clear()
        val providersObj = config.optJSONObject("providers")
        var activeProvider = ""
        if (providersObj != null) {
            val keys = providersObj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val obj = providersObj.optJSONObject(name) ?: continue
                val apiKey = obj.optString("api_key", "")
                val baseUrl = obj.optString("base_url", "")
                if (apiKey.isNotEmpty() || baseUrl.isNotEmpty()) {
                    providerConfigs[name] = Pair(apiKey, baseUrl)
                    if (apiKey.isNotEmpty() && activeProvider.isEmpty()) activeProvider = name
                }
            }
        }
        // (comment)
        val configured = providerConfigs.filter { it.value.first.isNotEmpty() }.size
        tvModelProviderSummary.text = getString(R.string.n_configured, configured)
        tvDefaultModelSummary.text = cachedModel.ifEmpty { getString(R.string.not_set) }

        // Max agent steps
        cachedMaxSteps = config.optInt("max_agent_steps", 25)
        tvMaxStepsSummary.text = getString(R.string.settings_max_steps_current, cachedMaxSteps)

        // Embedding model
        val embObj = config.optJSONObject("embedding")
        cachedEmbeddingModel = embObj?.optString("model", "") ?: ""
        cachedEmbeddingProvider = embObj?.optString("provider", "") ?: ""
        tvEmbeddingSummary.text = if (cachedEmbeddingModel.isNotEmpty()) {
            getString(R.string.settings_embedding_configured, cachedEmbeddingModel)
        } else {
            getString(R.string.settings_embedding_not_set)
        }

        // Memory mode
        cachedMemoryMode = config.optString("memory_mode", "basic")
        tvMemoryModeSummary.text = if (cachedMemoryMode == "vector") {
            getString(R.string.settings_memory_mode_vector)
        } else {
            getString(R.string.settings_memory_mode_basic)
        }

        // Tavily API Key
        val tavilyObj = config.optJSONObject("tavily")
        val tavilyKey = tavilyObj?.optString("api_key", "") ?: ""
        updateTavilySummary(tavilyKey)

        // Sync to SharedPreferences for MessageAdapter
        syncModelProviderListsToPrefs(config)
    }

    private fun syncModelProviderListsToPrefs(config: JSONObject) {
        val prefs = requireContext().getSharedPreferences("openclaw_prefs", 0)
        val allModels = mutableListOf<String>()
        val allProviders = mutableListOf<String>()
        val editor = prefs.edit()

        val providersObj = config.optJSONObject("providers")
        if (providersObj != null) {
            val keys = providersObj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                allProviders.add(name)
                val obj = providersObj.optJSONObject(name) ?: continue
                val modelsArr = obj.optJSONArray("models")
                val provModels = mutableListOf<String>()
                if (modelsArr != null) {
                    for (i in 0 until modelsArr.length()) {
                        val m = modelsArr.optString(i, "")
                        if (m.isNotEmpty()) {
                            provModels.add(m)
                            if (m !in allModels) allModels.add(m)
                        }
                    }
                }
                // Save per-provider model list
                editor.putString("provider_models_$name", JSONArray(provModels).toString())
            }
        }
        if (cachedModel.isNotEmpty() && cachedModel !in allModels) allModels.add(cachedModel)

        editor.putString("configured_models", JSONArray(allModels).toString())
            .putString("configured_providers", JSONArray(allProviders).toString())
            .apply()
    }

    private suspend fun loadAllFiles() {
        val tvMap = mapOf(
            "SOUL.md" to tvSoulSummary, "USER.md" to tvUserSummary,
            "HEARTBEAT.md" to tvHeartbeatSummary, "AGENTS.md" to tvAgentsSummary, "TOOLS.md" to tvToolsSummary,
            "MEMORY.md" to tvMemorySummary
        )
        for ((fileName, tv) in tvMap) {
            try {
                val content = withContext(Dispatchers.IO) { GatewayApi.getFileContent(fileName) }
                fileCache[fileName] = content
                tv.text = if (content.isEmpty()) getString(R.string.empty_content)
                    else content.trimStart().lines().firstOrNull()?.take(20)?.replace("#", "")?.trim()?.ifEmpty { "..." } ?: "..."
            } catch (_: Exception) { tv.text = getString(R.string.not_configured) }
        }
    }

    private suspend fun loadSkills() {
        try {
            cachedSkills = withContext(Dispatchers.IO) { GatewayApi.getSkills() }
            tvSkillsCount.text = getString(R.string.n_items, cachedSkills.length())
        } catch (_: Exception) { tvSkillsCount.text = getString(R.string.n_items, 0) }
    }

    // Model + Provider settings

    private fun updateModelProviderSummary() {
        val configured = providerConfigs.filter { it.value.first.isNotEmpty() }.size
        tvModelProviderSummary.text = getString(R.string.n_configured, configured)
        tvDefaultModelSummary.text = cachedModel.ifEmpty { getString(R.string.not_set) }
    }

    private fun showDefaultModelPicker() {
        // Always fetch fresh from API
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cfgObj = withContext(Dispatchers.IO) { GatewayApi.getConfig() }
                // Sync to SharedPreferences first
                syncModelProviderListsToPrefs(cfgObj)

                val providersObj = cfgObj.optJSONObject("providers") ?: JSONObject()
                val provNames = mutableListOf<String>()
                val provModelMap = mutableMapOf<String, Array<String>>()
                val keys = providersObj.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    provNames.add(name)
                    val prov = providersObj.optJSONObject(name) ?: continue
                    val arr = prov.optJSONArray("models")
                    val models = mutableListOf<String>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val m = arr.getString(i)
                        if (m.isNotEmpty()) models.add(m)
                    }
                    provModelMap[name] = models.toTypedArray()
                }

                if (provNames.isEmpty()) {
                    snack(getString(R.string.settings_no_providers))
                    return@launch
                }

                val prefs = requireContext().getSharedPreferences("openclaw_prefs", 0)
                val currentProv = prefs.getString("current_provider", provNames.first()) ?: provNames.first()
                val currentModel = cachedModel.ifEmpty { "qwen3.5-plus" }

                val layout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(60, 30, 60, 10)
                }

                // Provider spinner
                layout.addView(TextView(requireContext()).apply {
                    text = getString(R.string.avatar_provider_label); textSize = 12f; setTextColor(0xFF888888.toInt())
                })
                val finalProvs = provNames.toTypedArray()
                val provSpinner = Spinner(requireContext())
                provSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, finalProvs)
                provSpinner.setSelection(finalProvs.indexOf(currentProv).coerceAtLeast(0))
                layout.addView(provSpinner)

                // Model spinner
                layout.addView(TextView(requireContext()).apply {
                    text = "\n${getString(R.string.avatar_model_label)}"; textSize = 12f; setTextColor(0xFF888888.toInt())
                })
                val modelSpinner = Spinner(requireContext())
                layout.addView(modelSpinner)

                fun updateModelSpinner(provName: String) {
                    val models = provModelMap[provName] ?: emptyArray()
                    val list = models.toMutableList()
                    if (list.isEmpty()) list.add("(none)")
                    modelSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, list.toTypedArray())
                    val idx = list.indexOf(currentModel).coerceAtLeast(0)
                    modelSpinner.setSelection(idx)
                }
                updateModelSpinner(currentProv)

                provSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        updateModelSpinner(finalProvs[pos])
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("⚡ ${getString(R.string.settings_default_model)}")
                    .setView(layout)
                    .setPositiveButton(R.string.save) { _, _ ->
                        val selectedProv = finalProvs[provSpinner.selectedItemPosition]
                        val models = provModelMap[selectedProv] ?: emptyArray()
                        val list = models.toMutableList()
                        if (list.isEmpty()) list.add("(none)")
                        val selectedModel = list[modelSpinner.selectedItemPosition]
                        if (selectedModel != "(none)") {
                            saveModel(selectedModel, selectedProv)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            } catch (e: Exception) {
                snack("❌ ${e.message}")
            }
        }
    }

    private fun showModelProviderEditor() {
        showProviderEditor()
    }

    private fun saveModel(model: String, provider: String? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { GatewayApi.updateModel(model, provider) }
                cachedModel = model; updateModelProviderSummary()
                val prefs = requireContext().getSharedPreferences("openclaw_prefs", 0)
                val editor = prefs.edit().putString("current_model", model)
                if (provider != null) editor.putString("current_provider", provider)
                editor.apply()
                snack(getString(R.string.settings_model_switched, model))
            } catch (e: Exception) { snack(getString(R.string.settings_save_failed, e.message ?: "")) }
        }
    }

    // Model + Provider settings

    private fun showProviderEditor() {
        val allProviders = (defaultProviders.toList() + providerConfigs.keys).distinct().sorted()
        val items = allProviders.map { name ->
            val cfg = providerConfigs[name]
            if (cfg != null && cfg.first.isNotEmpty()) "$name ✅" else name
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("🏢 ${getString(R.string.settings_provider_manage)} (${getString(R.string.n_configured, providerConfigs.filter { it.value.first.isNotEmpty() }.size)})")
            .setItems(items) { _, which -> editSingleProvider(allProviders[which]) }
            .setNeutralButton(getString(R.string.settings_add_custom)) { _, _ ->
                val input = EditText(requireContext()).apply {
                    hint = getString(R.string.settings_enter_provider); setPadding(60, 30, 60, 30)
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_custom_provider)).setView(input)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) editSingleProvider(name)
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun editSingleProvider(providerName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val provModels = mutableListOf<String>()
            var existingKey = providerConfigs[providerName]?.first ?: ""
            var existingUrl = providerConfigs[providerName]?.second ?: ""
            try {
                val cfgObj = withContext(Dispatchers.IO) { GatewayApi.getConfig() }
                val provObj = cfgObj.optJSONObject("providers")?.optJSONObject(providerName)
                if (provObj != null) {
                    val arr = provObj.optJSONArray("models")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val m = arr.getString(i)
                        if (m.isNotEmpty()) provModels.add(m)
                    }
                    if (existingUrl.isEmpty()) existingUrl = provObj.optString("base_url", "")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "loadProviderConfig failed", e)
            }
            showProviderEditDialog(providerName, existingKey, existingUrl, provModels)
        }
    }

    private fun showProviderEditDialog(providerName: String, currentKey: String, currentUrl: String, models: MutableList<String>) {
        val scrollView = android.widget.ScrollView(requireContext())
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(60, 30, 60, 10)
        }
        scrollView.addView(layout)

        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_provider_label, providerName); textSize = 14f; setTextColor(0xFF212121.toInt())
        })

        layout.addView(TextView(requireContext()).apply { text = "\n${getString(R.string.settings_api_key)}"; textSize = 12f; setTextColor(0xFF888888.toInt()) })
        val keyInput = EditText(requireContext()).apply {
            setText(currentKey); hint = "sk-..."
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(keyInput)

        layout.addView(TextView(requireContext()).apply { text = "\n${getString(R.string.settings_base_url)}"; textSize = 12f; setTextColor(0xFF888888.toInt()) })
        val presetUrl = defaultBaseUrls[providerName] ?: ""
        val urlHint = if (presetUrl.isNotEmpty()) presetUrl else "https://api.example.com/v1"
        val urlInput = EditText(requireContext()).apply {
            setText(currentUrl.ifEmpty { presetUrl })
            hint = urlHint
        }
        layout.addView(urlInput)

        layout.addView(TextView(requireContext()).apply {
            text = "\n${getString(R.string.settings_provider_model_list)}"; textSize = 13f; setTextColor(0xFF333333.toInt())
        })

        val modelsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(modelsContainer)

        fun refreshModelChips() {
            modelsContainer.removeAllViews()
            for ((idx, model) in models.withIndex()) {
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val isCurrent = model == cachedModel
                row.addView(TextView(requireContext()).apply {
                    text = if (isCurrent) "✅ $model" else "○ $model"
                    textSize = 14f
                    setTextColor(if (isCurrent) 0xFF4CAF50.toInt() else 0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { saveModel(model) }
                })
                row.addView(TextView(requireContext()).apply {
                    text = "✖"; textSize = 16f; setTextColor(0xFFE53935.toInt())
                    setPadding(16, 0, 16, 0)
                    setOnClickListener { models.removeAt(idx); refreshModelChips() }
                })
                modelsContainer.addView(row)
            }
        }
        refreshModelChips()

        val addModelRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val addModelInput = EditText(requireContext()).apply {
            hint = getString(R.string.settings_provider_model_hint); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addModelRow.addView(addModelInput)
        addModelRow.addView(TextView(requireContext()).apply {
            text = "  ➕"; textSize = 18f; setTextColor(0xFF4CAF50.toInt())
            setPadding(16, 0, 0, 0)
            setOnClickListener {
                val m = addModelInput.text.toString().trim()
                if (m.isNotEmpty() && m !in models) { models.add(m); addModelInput.text.clear(); refreshModelChips() }
            }
        })
        layout.addView(addModelRow)

        AlertDialog.Builder(requireContext())
            .setTitle("🏢 ${getString(R.string.settings_provider_config, providerName)}")
            .setView(scrollView)
            .setPositiveButton(R.string.save) { _, _ ->
                val apiKey = keyInput.text.toString().trim()
                val baseUrl = urlInput.text.toString().trim()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val body = JSONObject().apply {
                            put("providers", JSONObject().apply {
                                put(providerName, JSONObject().apply {
                                    put("api_key", apiKey)
                                    put("base_url", baseUrl)
                                    put("models", JSONArray(models))
                                })
                            })
                        }
                        withContext(Dispatchers.IO) { GatewayApi.updateProvider(providerName, apiKey, baseUrl, models) }
                        providerConfigs[providerName] = Pair(apiKey, baseUrl)
                        updateModelProviderSummary()
                        try {
                            val cfgObj = withContext(Dispatchers.IO) { GatewayApi.getConfig() }
                            syncModelProviderListsToPrefs(cfgObj)
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "syncModelProviderListsToPrefs failed", e)
                        }
                        snack(getString(R.string.settings_provider_saved, providerName))
                    } catch (_: Exception) { snack(getString(R.string.settings_save_failed, "")) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(getString(R.string.chat_delete)) { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_provider_delete_title))
                    .setMessage(getString(R.string.settings_provider_delete_msg, providerName))
                    .setPositiveButton(getString(R.string.chat_delete)) { _, _ -> deleteProvider(providerName) }
                    .setNegativeButton(R.string.cancel, null).show()
            }.show()
    }

    private fun deleteProvider(providerName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val body = JSONObject().apply {
                    put("providers", JSONObject().apply {
                        put(providerName, JSONObject().apply { put("_delete", true) })
                    })
                }
                withContext(Dispatchers.IO) { GatewayApi.deleteProvider(providerName) }
                providerConfigs.remove(providerName)
                updateModelProviderSummary()
                // Clean up per-provider prefs and re-sync
                val prefs = requireContext().getSharedPreferences("openclaw_prefs", 0)
                prefs.edit().remove("provider_models_$providerName").apply()
                try {
                    val cfgObj = withContext(Dispatchers.IO) { GatewayApi.getConfig() }
                    syncModelProviderListsToPrefs(cfgObj)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "syncModelProviderListsToPrefs on delete failed", e)
                }
                snack(getString(R.string.settings_provider_deleted, providerName))
            } catch (_: Exception) { snack(getString(R.string.settings_save_failed, "")) }
        }
    }

    private fun editFile(fileName: String, title: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            var content = fileCache[fileName]
            if (content == null) {
                try {
                    content = withContext(Dispatchers.IO) { GatewayApi.getFileContent(fileName) }
                    fileCache[fileName] = content
                } catch (_: Exception) { content = "" }
            }

            val editText = EditText(requireContext()).apply {
                setText(content); textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE
                setPadding(40, 20, 40, 20); minLines = 10; gravity = android.view.Gravity.TOP
            }

            AlertDialog.Builder(requireContext())
                .setTitle(title).setView(editText)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newContent = editText.text.toString()
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { GatewayApi.saveFileContent(fileName, newContent) }
                            fileCache[fileName] = newContent
                            val summary = if (newContent.isEmpty()) getString(R.string.empty_content)
                                else newContent.trimStart().lines().firstOrNull()?.take(20)?.replace("#", "")?.trim()?.ifEmpty { "..." } ?: "..."
                            when (fileName) {
                                "SOUL.md" -> tvSoulSummary.text = summary
                                "USER.md" -> tvUserSummary.text = summary
                                "HEARTBEAT.md" -> tvHeartbeatSummary.text = summary
                                "AGENTS.md" -> tvAgentsSummary.text = summary
                                "TOOLS.md" -> tvToolsSummary.text = summary
                            "MEMORY.md" -> tvMemorySummary.text = summary
                            }
                            snack(getString(R.string.settings_file_saved, fileName))
                        } catch (e: Exception) { snack(getString(R.string.settings_file_save_failed, e.message ?: "")) }
                    }
                }
                .setNeutralButton(R.string.reload) { _, _ -> fileCache.remove(fileName); editFile(fileName, title) }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    // (settings handler)

    private fun showSkillsList() {
        viewLifecycleOwner.lifecycleScope.launch {
            try { retryLoad { loadSkills() } } catch (e: Exception) {
                android.util.Log.w(TAG, "loadSkills failed", e)
            }
            if (cachedSkills.length() == 0) { snack(getString(R.string.settings_no_skills)); return@launch }

            val names = mutableListOf<String>()
            val descs = mutableListOf<String>()
            for (i in 0 until cachedSkills.length()) {
                val s = cachedSkills.getJSONObject(i)
                names.add(s.optString("name", "?"))
                descs.add(s.optString("description", getString(R.string.settings_no_desc)).take(50))
            }

            AlertDialog.Builder(requireContext())
                .setTitle("📦 ${getString(R.string.settings_installed_skills)} (${names.size})")
                .setItems(names.mapIndexed { i, n -> if (descs[i].isNotEmpty()) "$n - ${descs[i]}" else n }.toTypedArray()) { _, which -> showSkillDetail(names[which]) }
                .setNegativeButton(R.string.close, null).show()
        }
    }

    private fun showSkillDetail(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { GatewayApi.getSkillDetail(name) }
                val content = resp.optString("content", "")
                val desc = resp.optString("description", "")
                val files = resp.optJSONArray("files")
                val fileList = buildString {
                    if (files != null) for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        appendLine("  • ${f.optString("name")} (${f.optInt("size")} bytes)")
                    }
                }
                val detail = "📝 $desc\n\n📁 ${getString(R.string.settings_skill_files)}\n$fileList\n─── SKILL.md ───\n${content.take(2000)}"
                AlertDialog.Builder(requireContext())
                    .setTitle("📦 $name").setMessage(detail)
                    .setPositiveButton(R.string.edit) { _, _ -> editSkill(name, content) }
                    .setNeutralButton(R.string.delete_btn) { _, _ -> confirmUninstall(name) }
                    .setNegativeButton(R.string.close, null).show()
            } catch (e: Exception) { snack("❌ ${e.message}") }
        }
    }

    private fun editSkill(name: String, content: String) {
        val et = EditText(requireContext()).apply {
            setText(content); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setPadding(40, 20, 40, 20); minLines = 12; gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_edit_skill, name)).setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { GatewayApi.updateSkill(name, et.text.toString()) }
                        snack(getString(R.string.settings_skill_updated, name))
                    } catch (_: Exception) { snack(getString(R.string.settings_save_failed, "")) }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmUninstall(name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_confirm_uninstall))
            .setMessage(getString(R.string.settings_confirm_uninstall_msg, name))
            .setPositiveButton(R.string.delete_btn) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { GatewayApi.deleteSkill(name) }
                        snack(getString(R.string.settings_skill_uninstalled, name)); retryLoad { loadSkills() }
                    } catch (_: Exception) { snack(getString(R.string.settings_save_failed, "")) }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun showSkillInstaller() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(60, 30, 60, 10)
        }
        layout.addView(TextView(requireContext()).apply { text = getString(R.string.settings_skill_name); textSize = 12f; setTextColor(0xFF888888.toInt()) })
        val nameInput = EditText(requireContext()).apply { hint = getString(R.string.settings_skill_name_hint) }
        layout.addView(nameInput)
        layout.addView(TextView(requireContext()).apply { text = "\n${getString(R.string.settings_skill_url)}"; textSize = 12f; setTextColor(0xFF888888.toInt()) })
        val urlInput = EditText(requireContext()).apply { hint = getString(R.string.settings_skill_url_hint) }
        layout.addView(urlInput)
        layout.addView(TextView(requireContext()).apply { text = "\n${getString(R.string.settings_skill_paste)}"; textSize = 12f; setTextColor(0xFF888888.toInt()) })
        val contentInput = EditText(requireContext()).apply {
            hint = "# Skill Name\n..."; minLines = 5; gravity = android.view.Gravity.TOP
            textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(contentInput)

        AlertDialog.Builder(requireContext())
            .setTitle("➕ ${getString(R.string.settings_install_skill)}").setView(layout)
            .setPositiveButton(R.string.install) { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                val content = contentInput.text.toString()
                if (name.isEmpty()) { snack(getString(R.string.settings_enter_name)); return@setPositiveButton }
                if (url.isEmpty() && content.trim().isEmpty()) { snack(getString(R.string.settings_skill_empty_content)); return@setPositiveButton }
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (url.isNotEmpty()) GatewayApi.installSkill(name, url = url)
                            else GatewayApi.installSkill(name, content = content.takeIf { it.trim().isNotEmpty() })
                        }
                        snack(getString(R.string.settings_skill_installed, name)); retryLoad { loadSkills() }
                    } catch (e: Exception) { snack(getString(R.string.settings_skill_install_failed, e.message ?: "")) }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // (settings handler)

    private fun showMemoryModePicker() {
        val modes = arrayOf(
            "${if (cachedMemoryMode == "basic") "✅ " else ""}${getString(R.string.settings_memory_mode_basic)}\n${getString(R.string.settings_memory_mode_basic_desc)}",
            "${if (cachedMemoryMode == "vector") "✅ " else ""}${getString(R.string.settings_memory_mode_vector)}\n${getString(R.string.settings_memory_mode_vector_desc)}"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("💾 ${getString(R.string.settings_memory_mode)}")
            .setItems(modes) { _, which ->
                val newMode = if (which == 0) "basic" else "vector"
                if (newMode == "vector" && cachedEmbeddingModel.isEmpty()) {
                    snack(getString(R.string.settings_memory_need_embedding))
                    return@setItems
                }
                saveMemoryMode(newMode)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun saveMemoryMode(mode: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { GatewayApi.updateMemoryMode(mode) }
                cachedMemoryMode = mode
                val label = if (mode == "vector") getString(R.string.settings_memory_mode_vector) else getString(R.string.settings_memory_mode_basic)
                tvMemoryModeSummary.text = label
                snack(getString(R.string.settings_memory_mode_saved, label))
            } catch (e: Exception) {
                snack(getString(R.string.settings_save_failed, e.message ?: ""))
            }
        }
    }

    // Model + Provider settings

    private fun showEmbeddingEditor() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { GatewayApi.getEmbeddingConfig() }
                val providers = resp.optJSONObject("providers") ?: JSONObject()
                val currentProvider = resp.optString("provider", "")
                val names = providers.keys().asSequence().toList()

                val labels = names.map { name ->
                    val isCurrent = name == currentProvider
                    val status = if (isCurrent) getString(R.string.settings_provider_connected) else getString(R.string.settings_provider_not_connected)
                    "$name  $status"
                }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_embedding))
                    .setItems(labels) { _, which ->
                        showEmbeddingProviderDetail(names[which], providers.getJSONObject(names[which]))
                    }
                    .setNeutralButton(getString(R.string.custom)) { _, _ ->
                        showEmbeddingCustomForm()
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            } catch (e: Exception) {
                snack("❌ ${e.message}")
            }
        }
    }

    private fun showEmbeddingProviderDetail(name: String, prov: JSONObject) {
        val modelArr = prov.optJSONArray("models")
        val modelList = mutableListOf<String>()
        if (modelArr != null) for (i in 0 until modelArr.length()) modelList.add(modelArr.getString(i))
        val baseUrl = prov.optString("base_url", "")

        val scrollView = android.widget.ScrollView(requireContext())
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8)
        }
        scrollView.addView(layout)

        // API Key
        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_provider_api_key); textSize = 12f; setTextColor(0xFF888888.toInt())
        })
        val etKey = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            hint = getString(R.string.settings_provider_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 14f
        }
        layout.addView(etKey)

        layout.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16) })

        // Base URL
        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_provider_base_url); textSize = 12f; setTextColor(0xFF888888.toInt())
        })
        val etUrl = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(baseUrl); textSize = 14f
        }
        layout.addView(etUrl)

        layout.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16) })

        // Model list with delete
        layout.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_provider_model_list); textSize = 13f; setTextColor(0xFF333333.toInt())
        })

        val modelsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(modelsContainer)

        fun refreshModelChips() {
            modelsContainer.removeAllViews()
            for ((idx, model) in modelList.withIndex()) {
                val isSelected = model == cachedEmbeddingModel && name == (cachedEmbeddingProvider ?: "")
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(requireContext()).apply {
                    text = if (isSelected) "✅ $model" else "○ $model"; textSize = 14f
                    setTextColor(if (isSelected) 0xFF4CAF50.toInt() else 0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(requireContext()).apply {
                    text = "✖"; textSize = 16f; setTextColor(0xFFE53935.toInt())
                    setPadding(16, 0, 16, 0)
                    setOnClickListener { modelList.removeAt(idx); refreshModelChips() }
                })
                modelsContainer.addView(row)
            }
        }
        refreshModelChips()

        // Add model
        val addRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val addInput = EditText(requireContext()).apply {
            hint = getString(R.string.settings_provider_model_hint); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addRow.addView(addInput)
        addRow.addView(TextView(requireContext()).apply {
            text = "  ➕"; textSize = 18f; setTextColor(0xFF4CAF50.toInt())
            setPadding(16, 0, 0, 0)
            setOnClickListener {
                val m = addInput.text.toString().trim()
                if (m.isNotEmpty() && m !in modelList) { modelList.add(m); addInput.text.clear(); refreshModelChips() }
            }
        })
        layout.addView(addRow)

        AlertDialog.Builder(requireContext())
            .setTitle("🧠 $name")
            .setView(scrollView)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = etKey.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val model = modelList.firstOrNull() ?: ""
                if (key.isEmpty()) { snack("❌ API Key 不能为空"); return@setPositiveButton }
                // Also update embedding_providers models
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            GatewayApi.addEmbeddingProvider(name, url.ifEmpty { baseUrl }, modelList)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "addEmbeddingProvider failed", e)
                    }
                }
                saveEmbeddingConfig(name, model, key, url.ifEmpty { baseUrl })
            }
            .setNeutralButton(getString(R.string.chat_delete)) { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_embedding_delete_title))
                    .setMessage(getString(R.string.settings_embedding_delete_msg, name))
                    .setPositiveButton(getString(R.string.chat_delete)) { _, _ ->
                        // Delete from providers list AND clear active config
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    GatewayApi.deleteEmbeddingProvider(name)
                                }
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "deleteEmbeddingProvider failed", e)
                            }
                        }
                        if (name == cachedEmbeddingProvider) deleteEmbeddingConfig()
                        else snack(getString(R.string.settings_provider_deleted, name))
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun showEmbeddingCustomForm() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etProvider = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            hint = getString(R.string.settings_embedding_provider); textSize = 14f
        }
        val etModel = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            hint = getString(R.string.settings_embedding_model); textSize = 14f
        }
        val etUrl = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            hint = getString(R.string.settings_provider_base_url); textSize = 14f
        }
        layout.addView(etProvider); layout.addView(etModel); layout.addView(etUrl)

        AlertDialog.Builder(requireContext())
            .setTitle("🧠 ${getString(R.string.custom)}")
            .setView(layout)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val provider = etProvider.text.toString().trim()
                val model = etModel.text.toString().trim()
                val baseUrl = etUrl.text.toString().trim()
                if (provider.isEmpty() || model.isEmpty()) {
                    snack("❌ 供应商和模型名不能为空")
                    return@setPositiveButton
                }
                // Add to embedding_providers list via API, then re-open editor
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            GatewayApi.addEmbeddingProvider(provider, baseUrl, listOf(model))
                        }
                        snack("✅ $provider 已添加到列表")
                        showEmbeddingEditor()
                    } catch (e: Exception) {
                        snack("❌ ${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private var cachedEmbeddingProvider: String? = null

    private fun saveEmbeddingConfig(provider: String, model: String, apiKey: String, baseUrl: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GatewayApi.saveEmbeddingConfig(JSONObject().apply {
                        put("provider", provider)
                        put("model", model)
                        put("api_key", apiKey)
                        put("base_url", baseUrl)
                    })
                }
                cachedEmbeddingModel = model
                cachedEmbeddingProvider = provider
                tvEmbeddingSummary.text = if (model.isNotEmpty()) {
                    getString(R.string.settings_embedding_configured, model)
                } else {
                    getString(R.string.settings_embedding_not_set)
                }
                snack(getString(R.string.settings_embedding_saved))
            } catch (e: Exception) {
                snack(getString(R.string.settings_save_failed, e.message ?: ""))
            }
        }
    }

    private fun deleteEmbeddingConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GatewayApi.saveEmbeddingConfig(JSONObject().apply {
                        put("provider", "")
                        put("model", "")
                        put("api_key", "")
                        put("base_url", "")
                    })
                }
                cachedEmbeddingModel = ""
                cachedEmbeddingProvider = ""
                tvEmbeddingSummary.text = getString(R.string.settings_embedding_not_set)
                snack(getString(R.string.settings_embedding_deleted))
            } catch (e: Exception) {
                snack(getString(R.string.settings_save_failed, e.message ?: ""))
            }
        }
    }

    // ==================== Tavily Search API ====================

    private fun showTavilyEditor() {
        val input = EditText(requireContext()).apply {
            hint = "tvly-..."
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(60, 30, 60, 30)
        }

        // 加载当前配置
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cfgObj = withContext(Dispatchers.IO) { GatewayApi.getConfig() }
                val tavilyObj = cfgObj.optJSONObject("tavily")
                val currentKey = tavilyObj?.optString("api_key", "") ?: ""
                if (currentKey.isNotEmpty()) {
                    input.setText(currentKey)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "loadTavilyConfig failed", e)
            }

            AlertDialog.Builder(requireContext())
                .setTitle("🔍 ${getString(R.string.settings_tavily_api_key)}")
                .setMessage(getString(R.string.settings_tavily_summary))
                .setView(input)
                .setPositiveButton(R.string.save) { _, _ ->
                    val apiKey = input.text.toString().trim()
                    saveTavilyConfig(apiKey)
                }
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(getString(R.string.chat_delete)) { _, _ ->
                    saveTavilyConfig("")
                }
                .show()
        }
    }

    private fun saveTavilyConfig(apiKey: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { GatewayApi.updateTavilyKey(apiKey) }
                updateTavilySummary(apiKey)
                snack(if (apiKey.isNotEmpty()) getString(R.string.settings_tavily_saved) else getString(R.string.settings_tavily_cleared))
            } catch (e: Exception) {
                snack(getString(R.string.settings_save_failed, e.message ?: ""))
            }
        }
    }

    private fun updateTavilySummary(apiKey: String? = null) {
        val key = apiKey ?: ""
        tvTavilySummary.text = if (key.isNotEmpty()) {
            "tvly-${key.takeLast(4)}"
        } else {
            getString(R.string.not_set)
        }
    }

    // ==================== Max Steps ====================

    private fun showMaxStepsEditor() {
        val presets = arrayOf(10, 15, 20, 25, 30, 50)
        val labels = presets.map { steps ->
            val label = when (steps) {
                10 -> "$steps (${getString(R.string.settings_max_steps_conservative)})"
                25 -> "$steps (${getString(R.string.settings_max_steps_default)})"
                50 -> "$steps (${getString(R.string.settings_max_steps_unlimited)})"
                else -> "$steps"
            }
            if (steps == cachedMaxSteps) "✅ $label" else label
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("🔄 ${getString(R.string.settings_max_steps)}\n${getString(R.string.settings_max_steps_current, cachedMaxSteps)}")
            .setItems(labels) { _, which ->
                saveMaxSteps(presets[which])
            }
            .setNeutralButton(R.string.custom) { _, _ ->
                val input = EditText(requireContext()).apply {
                    setText(cachedMaxSteps.toString())
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setPadding(60, 30, 60, 30)
                    hint = "5-100"
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_max_steps))
                    .setMessage(getString(R.string.settings_max_steps_desc))
                    .setView(input)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        val value = input.text.toString().toIntOrNull() ?: 25
                        saveMaxSteps(value.coerceIn(5, 100))
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun saveMaxSteps(steps: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { GatewayApi.updateMaxSteps(steps) }
                cachedMaxSteps = steps
                tvMaxStepsSummary.text = getString(R.string.settings_max_steps_current, steps)
                snack(getString(R.string.settings_max_steps_saved, steps))
            } catch (e: Exception) {
                snack(getString(R.string.settings_save_failed, e.message ?: ""))
            }
        }
    }

    // (settings handler)

    private fun performBackup() {
        snack(getString(R.string.settings_backup_creating))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val backupJson = withContext(Dispatchers.IO) {
                    val apiKey = "" // Gateway doesn't require auth for local backup
                    val inputStream: java.io.InputStream = GatewayApi.getBackup(apiKey)
                    inputStream.bufferedReader().use { it.readText() }
                }

                val backup = JSONObject(backupJson)
                val fileCount = backup.optInt("file_count", 0)
                val totalBytes = backup.optLong("total_bytes", 0)
                val sizeStr = when {
                    totalBytes > 1024 * 1024 -> "%.1f MB".format(totalBytes / 1024.0 / 1024.0)
                    totalBytes > 1024 -> "%.1f KB".format(totalBytes / 1024.0)
                    else -> "$totalBytes B"
                }

                val filename = "openclaw-backup-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.json"
                val savedPath = withContext(Dispatchers.IO) {
                    saveBackupToDownloads(filename, backupJson)
                }

                // Show result dialog with path and actions
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_backup_success, fileCount, sizeStr))
                    .setMessage(getString(R.string.settings_backup_location, savedPath))
                    .setPositiveButton(getString(R.string.settings_backup_open_folder)) { _, _ ->
                        openBackupFolder()
                    }
                    .setNeutralButton(getString(R.string.settings_backup_share)) { _, _ ->
                        shareBackupFile(savedPath, filename)
                    }
                    .setNegativeButton(R.string.confirm, null)
                    .show()

            } catch (e: Exception) {
                snack(getString(R.string.settings_backup_failed, e.message ?: ""))
            }
        }
    }

    private fun saveBackupToDownloads(filename: String, content: String): String {
        val ctx = requireContext()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/OpenClaw")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Cannot create file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            return "Download/OpenClaw/$filename"
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "OpenClaw")
            dir.mkdirs()
            val file = java.io.File(dir, filename)
            file.writeText(content)
            return file.absolutePath
        }
    }

    private fun shareBackupFile(savedPath: String, filename: String) {
        try {
            val ctx = requireContext()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_TEXT, getString(R.string.settings_backup_saved, savedPath))
            }
            // Try to attach the file
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val cursor = ctx.contentResolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Downloads._ID),
                    "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(filename), null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(0)
                        val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        intent.putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    }
                }
            }
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.settings_backup)))
        } catch (_: Exception) {
            snack(getString(R.string.settings_backup_saved, savedPath))
        }
    }

    private fun openBackupFolder() {
        // Build URI pointing to Download/OpenClaw
        val folderUri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FOpenClaw")
        try {
            // Try DocumentsUI to open the exact folder
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                // Fallback: open file manager at Download/OpenClaw
                val treeUri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FOpenClaw")
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    data = treeUri
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    // Last fallback: just open Downloads
                    @Suppress("DEPRECATION")
                    val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.fromFile(dir), "resource/folder")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    snack(getString(R.string.settings_backup_folder_path))
                }
            }
        }
    }

    // (settings handler)
    private val restoreFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            loadAndRestore(uri)
        }
    }

    private fun pickRestoreFile() {
        try {
            // Use OpenDocument to let user pick from Downloads/OpenClaw
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/json"
                // Open to Download/OpenClaw subfolder
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val initialUri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FOpenClaw")
                    putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
            }
            restoreDocLauncher.launch(intent)
        } catch (_: Exception) {
            // Fallback to simple content picker
            try {
                restoreFileLauncher.launch("application/json")
            } catch (_: Exception) {
                snack(getString(R.string.settings_restore_pick_file))
            }
        }
    }

    private val restoreDocLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadAndRestore(uri) }
        }
    }

    private fun loadAndRestore(uri: android.net.Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw Exception("Cannot read file")
                }

                val backup = JSONObject(content)

                // Validate
                val format = backup.optString("format", "")
                if (format != "openclaw-android-backup") {
                    snack(getString(R.string.settings_restore_invalid))
                    return@launch
                }

                val fileCount = backup.optInt("file_count", 0)
                val createdAt = backup.optString("created_at", "?")
                val engineVersion = backup.optString("engine_version", "?")

                // Confirm dialog
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_restore_confirm))
                    .setMessage(getString(R.string.settings_restore_confirm_msg, createdAt, fileCount, engineVersion))
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        executeRestore(content)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()

            } catch (e: Exception) {
                snack(getString(R.string.settings_restore_failed, e.message ?: ""))
            }
        }
    }

    private fun executeRestore(backupJson: String) {
        snack(getString(R.string.settings_restore_restoring))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    GatewayApi.restoreBackup(apiKey = "", jsonContent = backupJson)
                }

                val restored = result.optInt("files_restored", 0)
                val total = result.optInt("files_total", 0)
                snack(getString(R.string.settings_restore_success, restored, total))

                // Reload settings display
                loadConfig()

            } catch (e: Exception) {
                snack(getString(R.string.settings_restore_failed, e.message ?: ""))
            }
        }
    }

    // (settings handler)

    private fun showLanguagePicker() {
        val options = arrayOf(getString(R.string.settings_language_zh), getString(R.string.settings_language_en))
        val codes = arrayOf("zh", "en")
        val current = LocaleHelper.getLanguage(requireContext())
        val currentIdx = codes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("🌐 ${getString(R.string.settings_language)}")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val newLang = codes[which]
                if (newLang != current) {
                    LocaleHelper.setLanguage(requireContext(), newLang)
                    dialog.dismiss()
                    // (comment)
                    activity?.let { LocaleHelper.restartActivity(it) }
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // (settings handler)

    private fun clearEngineCache() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_clear_cache))
            .setMessage(getString(R.string.settings_clear_cache_msg))
            .setPositiveButton(R.string.confirm) { _, _ ->
                try {
                    val vf = java.io.File(requireContext().filesDir, "openclaw-engine/.version")
                    if (vf.exists()) vf.delete()
                    snack(getString(R.string.settings_cache_cleared))
                } catch (_: Exception) { snack(getString(R.string.settings_clear_failed)) }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // (settings handler)

    private fun snack(msg: String) { view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show() } }
}
