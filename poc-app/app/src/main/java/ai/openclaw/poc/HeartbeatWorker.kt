package ai.openclaw.poc

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Heartbeat Worker — periodically sends heartbeat prompt to the AI
 * Mirrors desktop OpenClaw's HEARTBEAT.md polling behavior
 */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "openclaw_heartbeat"
        private const val BASE_URL = "http://127.0.0.1:18789"
        private const val HEARTBEAT_PROMPT = "Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. Do not infer or repeat old tasks from prior chats. If nothing needs attention, reply HEARTBEAT_OK."

        fun schedule(context: Context, intervalMinutes: Long = 30) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .addTag("openclaw_heartbeat")
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences("openclaw_prefs", 0)
                .getBoolean("heartbeat_enabled", false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("openclaw_prefs", 0).edit()
                .putBoolean("heartbeat_enabled", enabled).apply()
            if (enabled) schedule(context) else cancel(context)
        }
    }

    override suspend fun doWork(): Result {
        if (!isEnabled(applicationContext)) return Result.success()

        return try {
            val response = withContext(Dispatchers.IO) { sendChat(HEARTBEAT_PROMPT) }
            // If AI says HEARTBEAT_OK, nothing to do
            if (!response.trim().contains("HEARTBEAT_OK")) {
                // AI has something to report — show notification
                val builder = androidx.core.app.NotificationCompat.Builder(applicationContext, CronWorker.CHANNEL_ID)
                builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                builder.setContentTitle("🦞 Heartbeat Alert")
                builder.setContentText(response.take(300))
                builder.setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(response.take(1000)))
                builder.priority = androidx.core.app.NotificationCompat.PRIORITY_DEFAULT
                builder.setAutoCancel(true)
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(99999, builder.build())
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun sendChat(message: String): String {
        val url = URL("$BASE_URL/api/chat")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.connectTimeout = 30000; conn.readTimeout = 120000; conn.doOutput = true

        val body = JSONObject().apply {
            put("message", message)
            put("stream", false)
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) throw Exception("HTTP $code")

        return try {
            val json = JSONObject(resp)
            json.optString("response", json.optString("message", resp))
        } catch (_: Exception) { resp }
    }
}
