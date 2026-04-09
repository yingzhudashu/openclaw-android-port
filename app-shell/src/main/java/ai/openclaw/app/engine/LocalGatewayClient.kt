package ai.openclaw.app.engine

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 Gateway WebSocket 客户端
 *
 * 核心职责：
 * 1. 通过 WebSocket 连接 localhost:19789 的 Node.js 引擎
 * 2. 发送聊天消息、接收流式回复
 * 3. 心跳检测保持连接
 * 4. 断线自动重连
 *
 * 协议：复用 OpenClaw GatewaySession 协议 v3
 */
class LocalGatewayClient(
    private val port: Int = 19789,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "LocalGatewayClient"
        private const val GATEWAY_PROTOCOL_VERSION = 3
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val CONNECT_TIMEOUT_S = 10L
    }

    // ========== 连接状态 ==========

    /** 连接状态 */
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ========== 消息流 ==========

    /** 收到的聊天消息事件 */
    sealed class GatewayEvent {
        /** 聊天回复（完整消息） */
        data class ChatMessage(
            val sessionKey: String,
            val role: String,
            val content: String,
            val messageId: String? = null
        ) : GatewayEvent()

        /** 流式 token */
        data class StreamToken(
            val sessionKey: String,
            val token: String,
            val done: Boolean = false
        ) : GatewayEvent()

        /** 工具调用 */
        data class ToolCall(
            val sessionKey: String,
            val toolName: String,
            val args: String
        ) : GatewayEvent()

        /** 错误 */
        data class ErrorEvent(val message: String) : GatewayEvent()

        /** 引擎状态 */
        data class EngineStatus(
            val version: String,
            val uptime: Long,
            val sessions: Int
        ) : GatewayEvent()
    }

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    // ========== 内部状态 ==========

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket 不限读超时
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val messageIdCounter = AtomicLong(1)
    private var reconnectAttempts = 0

    @Volatile
    private var isManualDisconnect = false

    // ========== 连接管理 ==========

    /**
     * 连接到本地 Gateway
     */
    fun connect() {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            Log.d(TAG, "已连接或正在连接，跳过")
            return
        }

        isManualDisconnect = false
        reconnectAttempts = 0
        doConnect()
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isManualDisconnect = true
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * 执行实际连接
     */
    private fun doConnect() {
        _connectionState.value = ConnectionState.Connecting
        Log.i(TAG, "连接到 ws://127.0.0.1:$port/ws")

        val request = Request.Builder()
            .url("ws://127.0.0.1:$port/ws")
            .header("X-Gateway-Protocol", GATEWAY_PROTOCOL_VERSION.toString())
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 连接成功")
                _connectionState.value = ConnectionState.Connected
                reconnectAttempts = 0
                startHeartbeat()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 正在关闭: code=$code, reason=$reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 已关闭: code=$code, reason=$reason")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 连接失败: ${t.message}")
                _connectionState.value = ConnectionState.Error(t.message ?: "连接失败")
                handleDisconnect()
            }
        })
    }

    /**
     * 处理断线：自动重连
     */
    private fun handleDisconnect() {
        webSocket = null
        heartbeatJob?.cancel()
        heartbeatJob = null

        if (isManualDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val delayMs = RECONNECT_DELAY_MS * reconnectAttempts.coerceAtMost(5)
            Log.i(TAG, "将在 ${delayMs}ms 后重连 ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")

            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                if (!isManualDisconnect) {
                    doConnect()
                }
            }
        } else {
            Log.e(TAG, "达到最大重连次数，停止重连")
            _connectionState.value = ConnectionState.Error("连接失败，请检查引擎状态")
        }
    }

    // ========== 消息收发 ==========

    /**
     * 发送聊天消息
     * @param message 用户输入的文本
     * @param sessionKey 会话标识（可选，默认使用 main）
     */
    fun sendChat(message: String, sessionKey: String? = null) {
        val payload = JSONObject().apply {
            put("type", "chat.send")
            put("id", messageIdCounter.getAndIncrement())
            put("data", JSONObject().apply {
                put("message", message)
                if (sessionKey != null) {
                    put("sessionKey", sessionKey)
                }
            })
        }
        sendJson(payload)
    }

    /**
     * 请求会话列表
     */
    fun requestSessions() {
        val payload = JSONObject().apply {
            put("type", "sessions.list")
            put("id", messageIdCounter.getAndIncrement())
        }
        sendJson(payload)
    }

    /**
     * 请求引擎状态
     */
    fun requestStatus() {
        val payload = JSONObject().apply {
            put("type", "status")
            put("id", messageIdCounter.getAndIncrement())
        }
        sendJson(payload)
    }

    /**
     * 发送 JSON 消息
     */
    private fun sendJson(json: JSONObject) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "WebSocket 未连接，无法发送消息")
            scope.launch {
                _events.emit(GatewayEvent.ErrorEvent("未连接到引擎"))
            }
            return
        }

        val text = json.toString()
        val sent = ws.send(text)
        if (!sent) {
            Log.e(TAG, "消息发送失败")
        } else {
            Log.d(TAG, "消息已发送: ${json.optString("type")}")
        }
    }

    /**
     * 解析并分发收到的消息
     */
    private fun handleMessage(text: String) {
        scope.launch {
            try {
                val json = JSONObject(text)
                val type = json.optString("type", "")

                when {
                    // 聊天回复
                    type == "chat.message" || type == "chat.reply" -> {
                        val data = json.optJSONObject("data") ?: return@launch
                        _events.emit(GatewayEvent.ChatMessage(
                            sessionKey = data.optString("sessionKey", "main"),
                            role = data.optString("role", "assistant"),
                            content = data.optString("content", ""),
                            messageId = data.optString("messageId", null)
                        ))
                    }

                    // 流式 token
                    type == "chat.stream" || type == "chat.delta" -> {
                        val data = json.optJSONObject("data") ?: return@launch
                        _events.emit(GatewayEvent.StreamToken(
                            sessionKey = data.optString("sessionKey", "main"),
                            token = data.optString("token", data.optString("delta", "")),
                            done = data.optBoolean("done", false)
                        ))
                    }

                    // 工具调用
                    type == "tool.call" -> {
                        val data = json.optJSONObject("data") ?: return@launch
                        _events.emit(GatewayEvent.ToolCall(
                            sessionKey = data.optString("sessionKey", "main"),
                            toolName = data.optString("name", ""),
                            args = data.optString("args", "{}")
                        ))
                    }

                    // 引擎状态
                    type == "status" -> {
                        val data = json.optJSONObject("data") ?: return@launch
                        _events.emit(GatewayEvent.EngineStatus(
                            version = data.optString("version", ""),
                            uptime = data.optLong("uptime", 0),
                            sessions = data.optInt("sessions", 0)
                        ))
                    }

                    // 错误
                    type == "error" -> {
                        val message = json.optString("message",
                            json.optJSONObject("data")?.optString("message", "未知错误") ?: "未知错误"
                        )
                        _events.emit(GatewayEvent.ErrorEvent(message))
                    }

                    // 心跳响应
                    type == "pong" || type == "heartbeat" -> {
                        Log.d(TAG, "收到心跳响应")
                    }

                    else -> {
                        Log.d(TAG, "收到未处理的消息类型: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析消息失败: ${e.message}", e)
            }
        }
    }

    // ========== 心跳 ==========

    /**
     * 启动心跳定时器
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val payload = JSONObject().apply {
                    put("type", "ping")
                    put("timestamp", System.currentTimeMillis())
                }
                sendJson(payload)
            }
        }
    }

    // ========== 生命周期 ==========

    /**
     * 释放资源
     */
    fun destroy() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
