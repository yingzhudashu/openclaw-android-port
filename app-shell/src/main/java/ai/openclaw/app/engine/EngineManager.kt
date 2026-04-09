package ai.openclaw.app.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 引擎状态密封类
 * 使用密封类而非枚举，以便 Error 状态可以携带错误信息
 */
sealed class EngineState {
    /** 引擎已停止 */
    object Stopped : EngineState() {
        override fun toString() = "Stopped"
    }

    /** 引擎正在启动 */
    object Starting : EngineState() {
        override fun toString() = "Starting"
    }

    /** 引擎运行中 */
    object Running : EngineState() {
        override fun toString() = "Running"
    }

    /** 引擎正在停止 */
    object Stopping : EngineState() {
        override fun toString() = "Stopping"
    }

    /** 引擎发生错误 */
    data class Error(val message: String) : EngineState() {
        override fun toString() = "Error: $message"
    }
}

/**
 * EngineManager - 引擎管理器单例
 *
 * 核心职责：
 * 1. 提供启动/停止/重启/状态查询的统一接口
 * 2. 通过 StateFlow 暴露引擎状态，供 UI 层订阅
 * 3. 管理与 NodeEngineService 的绑定关系
 *
 * 设计说明：
 * - 使用 object 单例模式，全局唯一实例
 * - 引擎状态通过 StateFlow 暴露，Compose UI 可直接 collectAsState
 * - 与 NodeEngineService 解耦：Service 内部更新状态，Manager 只负责转发和控制
 */
object EngineManager {

    private const val TAG = "EngineManager"

    /** 引擎监听端口 */
    const val ENGINE_PORT = 19789

    // ========== 状态管理 ==========

    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)

    /**
     * 引擎当前状态
     * UI 层通过 collectAsState() 订阅状态变化
     */
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * 引擎是否正在运行
     * 便捷属性，等价于 state.value == EngineState.Running
     */
    val isRunning: Boolean
        get() = _state.value is EngineState.Running

    /**
     * 引擎是否处于错误状态
     */
    val isError: Boolean
        get() = _state.value is EngineState.Error

    /**
     * 获取错误信息（如果处于 Error 状态）
     */
    val errorMessage: String?
        get() = (_state.value as? EngineState.Error)?.message

    // ========== 内部更新（供 NodeEngineService 调用） ==========

    /**
     * 更新引擎状态
     * 此方法由 NodeEngineService 内部调用，不应由外部 UI 直接调用
     */
    internal fun updateState(newState: EngineState) {
        val oldState = _state.value
        _state.value = newState
        Log.d(TAG, "引擎状态变更: $oldState -> $newState")
    }

    // ========== 控制接口（供 UI / ViewModel 调用） ==========

    /**
     * 启动引擎
     * 通过启动 NodeEngineService 前台服务来启动引擎
     *
     * @param context Android Context
     */
    fun startEngine(context: Context) {
        if (_state.value is EngineState.Running || _state.value is EngineState.Starting) {
            Log.w(TAG, "引擎已在运行或正在启动中，忽略重复启动请求")
            return
        }
        Log.i(TAG, "请求启动引擎")
        NodeEngineService.start(context.applicationContext)
    }

    /**
     * 停止引擎
     * 通过向 NodeEngineService 发送停止指令来停止引擎
     *
     * @param context Android Context
     */
    fun stopEngine(context: Context) {
        if (_state.value is EngineState.Stopped) {
            Log.w(TAG, "引擎已停止，忽略停止请求")
            return
        }
        Log.i(TAG, "请求停止引擎")
        NodeEngineService.stop(context.applicationContext)
    }

    /**
     * 重启引擎
     * 先停止再启动
     *
     * @param context Android Context
     */
    fun restartEngine(context: Context) {
        Log.i(TAG, "请求重启引擎")
        val appContext = context.applicationContext

        // 发送重启 intent
        val intent = Intent(appContext, NodeEngineService::class.java).apply {
            action = "ai.openclaw.app.engine.action.RESTART"
        }
        appContext.startService(intent)
    }

    /**
     * 获取引擎状态的人类可读描述
     */
    fun getStatusDescription(): String {
        return when (val s = _state.value) {
            is EngineState.Stopped -> "引擎已停止"
            is EngineState.Starting -> "引擎启动中..."
            is EngineState.Running -> "引擎运行中 (端口 $ENGINE_PORT)"
            is EngineState.Stopping -> "引擎停止中..."
            is EngineState.Error -> "引擎错误: ${s.message}"
        }
    }

    /**
     * 获取引擎本地网关 URL
     */
    fun getLocalGatewayUrl(): String {
        return "http://127.0.0.1:$ENGINE_PORT"
    }

    /**
     * 获取引擎本地 WebSocket URL
     */
    fun getLocalWebSocketUrl(): String {
        return "ws://127.0.0.1:$ENGINE_PORT"
    }
}

/**
 * EngineServiceBinder - 可选的 ServiceConnection 封装
 *
 * 如果需要与 Service 进行更紧密的绑定（如直接方法调用），
 * 可以使用此类。当前架构中 EngineManager 通过 Intent 控制即可满足需求。
 *
 * 使用示例：
 * ```kotlin
 * val binder = EngineServiceBinder(context)
 * binder.bind()
 * // ... 使用 binder.isBound 检查状态
 * binder.unbind()
 * ```
 */
class EngineServiceBinder(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isBound = MutableStateFlow(false)

    /** 是否已绑定到 Service */
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            _isBound.value = true
            Log.d("EngineServiceBinder", "已绑定到 NodeEngineService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _isBound.value = false
            Log.d("EngineServiceBinder", "与 NodeEngineService 断开绑定")
        }
    }

    /**
     * 绑定到 NodeEngineService
     */
    fun bind() {
        val intent = Intent(context, NodeEngineService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 解除绑定
     */
    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
            // 可能未绑定，忽略
        }
        _isBound.value = false
    }
}
