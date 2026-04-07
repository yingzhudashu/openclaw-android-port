package ai.openclaw.poc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务数据模型
 */
data class CronTask(
    val id: String = java.util.UUID.randomUUID().toString().substring(0, 8),
    val name: String,
    val prompt: String,           // 发给 AI 的消息
    val intervalMinutes: Int,     // 执行间隔（分钟）
    val enabled: Boolean = true,
    val lastRun: Long = 0,
    val lastResult: String = "",
    val notify: Boolean = true    // 是否推送通知
)

/**
 * 定时任务管理器
 * 使用 SharedPreferences 持久化任务列表
 */
object CronManager {
    private const val PREFS_NAME = "openclaw_cron"
    private const val KEY_TASKS = "tasks"

    fun getTasks(context: Context): List<CronTask> {
        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        val json = prefs.getString(KEY_TASKS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveTask(context: Context, task: CronTask) {
        val tasks = getTasks(context).toMutableList()
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx >= 0) tasks[idx] = task else tasks.add(task)
        saveTasks(context, tasks)
    }

    fun deleteTask(context: Context, taskId: String) {
        val tasks = getTasks(context).filter { it.id != taskId }
        saveTasks(context, tasks)
    }

    fun updateLastRun(context: Context, taskId: String, result: String) {
        val tasks = getTasks(context).map {
            if (it.id == taskId) it.copy(lastRun = System.currentTimeMillis(), lastResult = result)
            else it
        }
        saveTasks(context, tasks)
    }

    private fun saveTasks(context: Context, tasks: List<CronTask>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(taskToJson(it)) }
        context.getSharedPreferences(PREFS_NAME, 0).edit()
            .putString(KEY_TASKS, arr.toString()).apply()
    }

    private fun parseTask(obj: JSONObject) = CronTask(
        id = obj.optString("id", ""),
        name = obj.optString("name", ""),
        prompt = obj.optString("prompt", ""),
        intervalMinutes = obj.optInt("intervalMinutes", 60),
        enabled = obj.optBoolean("enabled", true),
        lastRun = obj.optLong("lastRun", 0),
        lastResult = obj.optString("lastResult", ""),
        notify = obj.optBoolean("notify", true)
    )

    private fun taskToJson(task: CronTask) = JSONObject().apply {
        put("id", task.id)
        put("name", task.name)
        put("prompt", task.prompt)
        put("intervalMinutes", task.intervalMinutes)
        put("enabled", task.enabled)
        put("lastRun", task.lastRun)
        put("lastResult", task.lastResult)
        put("notify", task.notify)
    }
}
