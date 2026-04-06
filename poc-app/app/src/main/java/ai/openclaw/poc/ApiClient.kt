package ai.openclaw.poc

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP API 客户端 — 封装所有对 Node.js Gateway 的调用
 */
object ApiClient {
    private const val TAG = "ApiClient"
    private const val BASE_URL = "http://127.0.0.1:18789"
    private const val TIMEOUT = 30000

    /** 健康检查 */
    suspend fun health(): Result<JSONObject> = get("/health")

    /** 引擎状态 */
    suspend fun status(): Result<JSONObject> = get("/api/status")

    /** 发送聊天消息 */
    suspend fun chat(message: String, sessionId: String? = null): Result<JSONObject> {
        val body = JSONObject().apply {
            put("message", message)
            if (sessionId != null) put("session_id", sessionId)
        }
        return post("/api/chat", body)
    }

    /** 获取配置 */
    suspend fun getConfig(): Result<JSONObject> = get("/api/config")

    /** 更新配置 */
    suspend fun updateConfig(config: JSONObject): Result<JSONObject> = post("/api/config", config)

    /** 获取模型列表 */
    suspend fun models(): Result<JSONObject> = get("/api/models")

    /** GET 请求 */
    private suspend fun get(path: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.requestMethod = "GET"

            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (code == 200) {
                Result.success(JSONObject(body))
            } else {
                Result.failure(Exception("HTTP $code: $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $path failed", e)
            Result.failure(e)
        }
    }

    /** POST 请求 */
    private suspend fun post(path: String, body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream.bufferedReader().readText()
            conn.disconnect()

            if (code in 200..299) {
                Result.success(JSONObject(responseBody))
            } else {
                Result.failure(Exception("HTTP $code: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $path failed", e)
            Result.failure(e)
        }
    }
}
