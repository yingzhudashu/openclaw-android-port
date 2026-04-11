package ai.openclaw.poc

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通用 HTTP 客户端，用于与 Gateway 通信
 * 所有网络请求统一通过此类，避免重复代码
 */
object GatewayApi {
    
    private const val BASE_URL = "http://127.0.0.1:18789"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 30_000
    
    data class Response(
        val code: Int,
        val body: String,
        val success: Boolean = code in 200..299
    )
    
    // ─── 底层方法 ─────────────────────────────────────────────
    
    fun get(endpoint: String): Response {
        return execute("GET", endpoint, null)
    }
    
    fun post(endpoint: String, body: JSONObject): Response {
        return execute("POST", endpoint, body)
    }
    
    fun put(endpoint: String, body: JSONObject): Response {
        return execute("PUT", endpoint, body)
    }
    
    fun delete(endpoint: String): Response {
        return execute("DELETE", endpoint, null)
    }
    
    // ─── JSON 便捷方法 ─────────────────────────────────────────
    
    fun getJson(endpoint: String): JSONObject {
        val resp = get(endpoint)
        return checkResponse(resp)
    }
    
    fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val resp = post(endpoint, body)
        return checkResponse(resp)
    }
    
    fun putJson(endpoint: String, body: JSONObject): JSONObject {
        val resp = put(endpoint, body)
        return checkResponse(resp)
    }
    
    fun deleteJson(endpoint: String): JSONObject {
        val resp = delete(endpoint)
        return checkResponse(resp)
    }
    
    // ─── 业务方法 ─────────────────────────────────────────────
    
    fun getConfig(): JSONObject {
        val resp = getJson("/api/config")
        return resp.optJSONObject("config") ?: resp
    }
    
    fun updateModel(model: String, provider: String? = null): JSONObject {
        return postJson("/api/config", JSONObject().apply {
            put("model", model)
            if (provider != null) put("default_provider", provider)
        })
    }
    
    fun updateProvider(name: String, apiKey: String, baseUrl: String, models: List<String>): JSONObject {
        return postJson("/api/config", JSONObject().apply {
            put("providers", JSONObject().apply {
                put(name, JSONObject().apply {
                    put("api_key", apiKey)
                    put("base_url", baseUrl)
                    put("models", JSONArray(models))
                })
            })
        })
    }
    
    fun deleteProvider(name: String): JSONObject {
        return postJson("/api/config", JSONObject().apply {
            put("providers", JSONObject().apply {
                put(name, JSONObject().apply { put("_delete", true) })
            })
        })
    }
    
    // ─── 文件操作 ─────────────────────────────────────────────
    
    private val FILE_ENDPOINTS = mapOf(
        "SOUL.md" to "/api/soul",
        "HEARTBEAT.md" to "/api/heartbeat",
        "USER.md" to "/api/user",
        "AGENTS.md" to "/api/agents",
        "TOOLS.md" to "/api/tools-md",
        "MEMORY.md" to "/api/memory"
    )
    
    fun getFileContent(fileName: String): String {
        val endpoint = FILE_ENDPOINTS[fileName] ?: throw IllegalArgumentException("Unknown file: $fileName")
        return getJson(endpoint).optString("content", "")
    }
    
    fun saveFileContent(fileName: String, content: String): JSONObject {
        val endpoint = FILE_ENDPOINTS[fileName] ?: throw IllegalArgumentException("Unknown file: $fileName")
        return postJson(endpoint, JSONObject().apply { put("content", content) })
    }
    
    // ─── Skills ──────────────────────────────────────────────
    
    fun getSkills(): JSONArray {
        return getJson("/api/skills").optJSONArray("skills") ?: JSONArray()
    }
    
    fun getSkillDetail(name: String): JSONObject {
        return getJson("/api/skills/$name")
    }
    
    fun updateSkill(name: String, content: String): JSONObject {
        return putJson("/api/skills/$name", JSONObject().apply { put("skill_md", content) })
    }
    
    fun deleteSkill(name: String): JSONObject {
        return deleteJson("/api/skills/$name")
    }
    
    fun installSkill(name: String, url: String? = null, content: String? = null): JSONObject {
        return postJson("/api/skills/install", JSONObject().apply {
            put("name", name)
            if (url != null) put("url", url)
            if (content != null) put("skill_md", content)
        })
    }
    
    // ─── Embedding ───────────────────────────────────────────
    
    fun getEmbeddingConfig(): JSONObject {
        return getJson("/api/embedding")
    }
    
    // ─── Tavily ──────────────────────────────────────────────
    
    fun updateTavilyKey(key: String): JSONObject {
        return postJson("/api/config", JSONObject().apply {
            put("tavily", JSONObject().apply { put("api_key", key) })
        })
    }
    
    // ─── Memory ──────────────────────────────────────────────
    
    fun updateMemoryMode(mode: String): JSONObject {
        return postJson("/api/config", JSONObject().apply { put("memory_mode", mode) })
    }
    
    fun updateMaxSteps(steps: Int): JSONObject {
        return postJson("/api/config", JSONObject().apply { put("max_agent_steps", steps) })
    }
    
    // ─── Health & Misc ───────────────────────────────────────
    
    fun health(): JSONObject {
        return getJson("/health")
    }
    
    fun getModels(): JSONArray {
        return getJson("/api/models").optJSONArray("models") ?: JSONArray()
    }
    
    fun getTools(): JSONObject {
        return getJson("/api/tools")
    }
    
    fun getSessions(): JSONArray {
        return getJson("/api/sessions").optJSONArray("sessions") ?: JSONArray()
    }
    
    fun getSession(sid: String): JSONObject {
        return getJson("/api/sessions/$sid")
    }
    
    fun deleteSession(sid: String): JSONObject {
        return deleteJson("/api/sessions/$sid")
    }
    
    fun clearSessions(): JSONObject {
        return deleteJson("/api/sessions")
    }
    
    fun createSession(title: String, model: String? = null, provider: String? = null, systemPrompt: String? = null): JSONObject {
        return postJson("/api/sessions", JSONObject().apply {
            put("title", title)
            if (model != null) put("model", model)
            if (provider != null) put("provider", provider)
            if (!systemPrompt.isNullOrEmpty()) put("system_prompt", systemPrompt)
        })
    }
    
    fun renameSession(sid: String, title: String): JSONObject {
        return postJson("/api/sessions/$sid/title", JSONObject().apply {
            put("title", title)
        })
    }
    
    fun clearSessionContext(sid: String): JSONObject {
        return deleteJson("/api/sessions/$sid/context")
    }
    
    fun saveSessionMessage(sid: String, role: String, content: String): JSONObject {
        return postJson("/api/sessions/$sid/messages", JSONObject().apply {
            put("role", role)
            put("content", content)
        })
    }
    
    fun sendChat(sessionId: String, message: String, model: String? = null, provider: String? = null): JSONObject {
        val body = JSONObject().apply {
            put("message", message)
            if (model != null) put("model", model)
            if (provider != null) put("provider", provider)
        }
        return postJson("/api/chat/$sessionId", body)
    }
    
    /**
     * Agent chat (multi-step with tools). Uses /api/agent/chat endpoint.
     */
    fun agentChat(message: String, sessionId: String? = null, model: String? = null, provider: String? = null, imageBase64: String? = null): JSONObject {
        return postJson("/api/agent/chat", JSONObject().apply {
            put("message", message)
            if (!sessionId.isNullOrEmpty()) put("session_id", sessionId)
            if (model != null) put("model", model)
            if (provider != null) put("provider", provider)
            if (imageBase64 != null) put("image_base64", imageBase64)
        })
    }
    
    /**
     * Agent chat with streaming. Returns HttpURLConnection for SSE reading.
     */
    fun getBackup(apiKey: String, filename: String = "gateway_settings.json"): java.io.InputStream {
        val url = URL("$BASE_URL/api/backup")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        if (apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader()?.readText()
            conn.disconnect()
            throw Exception("Backup download failed: HTTP $code ${errorBody ?: ""}")
        }
        return conn.inputStream
    }

    fun restoreBackup(apiKey: String, jsonContent: String, filename: String = "gateway_settings.json"): JSONObject {
        val url = URL("$BASE_URL/api/backup/restore")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        if (apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
        // Gateway expects the backup JSON directly, not wrapped in {filename, content}
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(jsonContent); it.flush() }
        val code = conn.responseCode
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) {
            throw Exception("Backup restore failed: HTTP $code $response")
        }
        return JSONObject(response)
    }

    fun agentChatStream(message: String, sessionId: String? = null, model: String? = null, provider: String? = null): HttpURLConnection {
        val url = URL("$BASE_URL/api/agent/chat")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = 600_000 // SSE 长连接
        conn.doInput = true
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("Accept", "text/event-stream")
        val body = JSONObject().apply {
            put("message", message)
            if (!sessionId.isNullOrEmpty()) put("session_id", sessionId)
            if (model != null) put("model", model)
            if (provider != null) put("provider", provider)
            put("stream", true)
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }
        return conn
    }
    
    // ─── Settings 操作 ───────────────────────────────────────
    
    fun saveConfig(body: JSONObject): JSONObject {
        return postJson("/api/config", body)
    }
    
    fun applyConfig(): JSONObject {
        return postJson("/api/apply", JSONObject())
    }
    
    fun saveEmbeddingConfig(body: JSONObject): JSONObject {
        return postJson("/api/embedding", body)
    }
    
    fun addEmbeddingProvider(name: String, baseUrl: String, models: List<String>): JSONObject {
        return postJson("/api/embedding/providers", JSONObject().apply {
            put("action", "add")
            put("name", name)
            put("base_url", baseUrl)
            put("models", JSONArray(models))
        })
    }
    
    fun deleteEmbeddingProvider(name: String): JSONObject {
        return postJson("/api/embedding/providers", JSONObject().apply {
            put("action", "delete")
            put("name", name)
        })
    }
    
    fun saveAgentsConfig(agents: JSONObject): JSONObject {
        return postJson("/api/config", JSONObject().apply {
            put("agents", agents)
        })
    }
    
    fun saveToolsConfig(tools: JSONObject): JSONObject {
        return postJson("/api/config", JSONObject().apply {
            put("tools", tools)
        })
    }
    
    // ─── SSE 流式聊天 ─────────────────────────────────────────
    
    /**
     * 发起 SSE 流式聊天请求。返回 HttpURLConnection 供调用方自行读取流。
     */
    fun streamChat(sessionId: String, message: String, model: String? = null, provider: String? = null): HttpURLConnection {
        val url = URL("$BASE_URL/api/chat/$sessionId")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = 300_000 // SSE 长连接
        conn.doInput = true
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("Accept", "text/event-stream")
        val body = JSONObject().apply {
            put("message", message)
            if (model != null) put("model", model)
            if (provider != null) put("provider", provider)
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
        return conn
    }
    
    // ─── 内部方法 ─────────────────────────────────────────────
    
    private fun execute(method: String, endpoint: String, body: JSONObject?): Response {
        val url = URL(if (endpoint.startsWith("http")) endpoint else "$BASE_URL$endpoint")
        val conn = url.openConnection() as HttpURLConnection
        
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.doInput = true
        
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            OutputStreamWriter(conn.outputStream, "UTF-8").use { 
                it.write(body.toString())
                it.flush()
            }
        }
        
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respBody = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        
        return Response(code, respBody)
    }
    
    private fun checkResponse(resp: Response): JSONObject {
        return if (resp.success) JSONObject(resp.body)
        else throw Exception("HTTP ${resp.code}: ${resp.body}")
    }
}
