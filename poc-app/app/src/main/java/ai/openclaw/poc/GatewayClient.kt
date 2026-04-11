package ai.openclaw.poc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException

/**
 * 统一 Gateway HTTP 客户端
 * 
 * 封装所有与本地 Gateway (127.0.0.1:18789) 的通信，消除各 Fragment 中的重复 HTTP 代码。
 * 
 * 设计原则：
 * - 零外部依赖（继续使用 HttpURLConnection）
 * - 统一超时策略（连接 10s / 读取 30s，SSE 单独配置）
 * - 统一错误处理（返回 Result 而非抛异常）
 * - 支持 GET / POST / DELETE
 * - SSE 流式专用接口
 * 
 * v1.6.0 新增
 */
object GatewayClient {

    private const val BASE_URL = "http://127.0.0.1:18789"
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val READ_TIMEOUT_MS = 30_000L
    private const val SSE_READ_TIMEOUT_MS = 300_000L // SSE 流需要更长超时

    // ─── GET ─────────────────────────────────────────────────────────────

    /**
     * 发起 GET 请求，返回 JSON 对象
     * @throws GatewayException 网络错误或解析失败
     */
    suspend fun get(endpoint: String, timeoutMs: Long = READ_TIMEOUT_MS): JSONObject {
        return withContext(Dispatchers.IO) {
            val url = URL("$BASE_URL$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.configureGet(timeoutMs)
            conn.parseResponse()
        }
    }

    /**
     * 发起 GET 请求，安全版本（返回 Result）
     */
    suspend fun getSafe(endpoint: String): Result<JSONObject> {
        return try {
            Result.success(get(endpoint))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── POST ────────────────────────────────────────────────────────────

    /**
     * 发起 POST 请求，发送 JSON body，返回 JSON 对象
     */
    suspend fun post(endpoint: String, body: JSONObject? = null, timeoutMs: Long = READ_TIMEOUT_MS): JSONObject {
        return withContext(Dispatchers.IO) {
            val url = URL("$BASE_URL$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.configurePost(timeoutMs)
            body?.let {
                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(it.toString())
                }
            }
            conn.parseResponse()
        }
    }

    /**
     * 发起 POST 请求，安全版本
     */
    suspend fun postSafe(endpoint: String, body: JSONObject? = null): Result<JSONObject> {
        return try {
            Result.success(post(endpoint, body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── DELETE ──────────────────────────────────────────────────────────

    /**
     * 发起 DELETE 请求
     */
    suspend fun delete(endpoint: String, timeoutMs: Long = READ_TIMEOUT_MS): JSONObject {
        return withContext(Dispatchers.IO) {
            val url = URL("$BASE_URL$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.configureDelete(timeoutMs)
            conn.parseResponse()
        }
    }

    /**
     * 发起 DELETE 请求，安全版本
     */
    suspend fun deleteSafe(endpoint: String): Result<JSONObject> {
        return try {
            Result.success(delete(endpoint))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── SSE Streaming ───────────────────────────────────────────────────

    /**
     * SSE 流式聊天
     * 
     * @param message 用户消息
     * @param sessionId 会话 ID
     * @param model 模型名称
     * @param provider 供应商名称
     * @param onChunk 每个 SSE 数据块回调（运行在 IO 线程）
     * @param onDone 完成回调，包含 model 和 reasoning
     * @param onError 错误回调
     */
    suspend fun streamChat(
        message: String,
        sessionId: String,
        model: String = "",
        provider: String = "",
        onChunk: (String) -> Unit,
        onDone: (model: String, reasoning: String?) -> Unit,
        onError: (Exception) -> Unit
    ) {
        var conn: HttpURLConnection? = null
        try {
            withContext(Dispatchers.IO) {
                val url = URL("$BASE_URL/api/sessions/$sessionId/chat/stream")
                conn = url.openConnection() as HttpURLConnection
                conn.configurePost(SSE_READ_TIMEOUT_MS)

                val body = JSONObject().apply {
                    put("message", message)
                    if (model.isNotEmpty()) put("model", model)
                    if (provider.isNotEmpty()) put("provider", provider)
                    put("stream", true)
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val errorBody = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, "UTF-8"))
                        .use { it.readText() }
                    throw GatewayException("HTTP $code", errorBody)
                }

                // 逐行读取 SSE
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                    while (true) {
                        val l = reader.readLine() ?: break
                        val trimmed = l.trim()
                        if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue

                        val data = trimmed.removePrefix("data:").trim()
                        if (data == "[DONE]") {
                            // 最后一行，解析 done 事件
                            break
                        }

                        try {
                            val json = JSONObject(data)
                            val type = json.optString("type", "")
                            when (type) {
                                "content" -> {
                                    json.optString("content", "").takeIf { it.isNotEmpty() }?.let(onChunk)
                                }
                                "done" -> {
                                    val respModel = json.optString("model", "")
                                    val reasoning = json.optString("reasoning").takeIf { it.isNotEmpty() }
                                    // 回调需要在 Main 线程？不，让调用方决定
                                    onDone(respModel, reasoning)
                                }
                                "error" -> {
                                    throw GatewayException(json.optString("message", "stream error"))
                                }
                            }
                        } catch (e: GatewayException) {
                            throw e
                        } catch (_: Exception) {
                            // 跳过无法解析的 SSE 行
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onError(e)
        } finally {
            conn?.disconnect()
        }
    }

    // ─── File Upload ─────────────────────────────────────────────────────

    /**
     * 上传文件进行分析
     */
    suspend fun analyzeFile(
        fileName: String,
        fileContent: String,
        message: String,
        sessionId: String
    ): JSONObject {
        val fullMessage = "文件: $fileName\n\n$fileContent\n\n用户问题: $message"
        return post("/api/sessions/$sessionId/chat", JSONObject().apply {
            put("message", fullMessage)
        })
    }

    // ─── Health Check ────────────────────────────────────────────────────

    /**
     * 健康检查，超时较短
     */
    suspend fun healthCheck(): Boolean {
        return try {
            get("/health", timeoutMs = 5_000L)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ─── Internal Helpers ────────────────────────────────────────────────

    private fun HttpURLConnection.configureGet(timeoutMs: Long) {
        requestMethod = "GET"
        connectTimeout = CONNECT_TIMEOUT_MS.toInt()
        readTimeout = timeoutMs.toInt()
        setRequestProperty("Accept", "application/json")
    }

    private fun HttpURLConnection.configurePost(timeoutMs: Long) {
        requestMethod = "POST"
        connectTimeout = CONNECT_TIMEOUT_MS.toInt()
        readTimeout = timeoutMs.toInt()
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        setRequestProperty("Accept", "application/json")
    }

    private fun HttpURLConnection.configureDelete(timeoutMs: Long) {
        requestMethod = "DELETE"
        connectTimeout = CONNECT_TIMEOUT_MS.toInt()
        readTimeout = timeoutMs.toInt()
        setRequestProperty("Accept", "application/json")
    }

    private fun HttpURLConnection.parseResponse(): JSONObject {
        val code = responseCode
        val inputStream = if (code in 200..299) inputStream else errorStream ?: inputStream
        val body = BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { it.readText() }
        disconnect()
        if (code !in 200..299) {
            throw GatewayException("HTTP $code", body)
        }
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw GatewayException("JSON parse error: ${e.message}", body)
        }
    }

    /**
     * Gateway 自定义异常
     */
    class GatewayException(message: String, val responseBody: String? = null) : Exception(message) {
        override val message: String
            get() = if (responseBody != null) "${super.message ?: ""}: $responseBody" else super.message ?: ""
    }

    /**
     * 判断是否为可重试的网络错误
     */
    fun isRetryable(e: Exception): Boolean {
        return e is SocketTimeoutException ||
               e is java.net.ConnectException ||
               e is java.net.UnknownHostException ||
               (e.cause is SocketTimeoutException) ||
               (e.cause is java.net.ConnectException) ||
               (e.cause is java.net.UnknownHostException) ||
               (e is GatewayException && e.responseBody?.contains("connection") == true)
    }
}
