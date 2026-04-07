package ai.openclaw.poc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
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
 * WorkManager Worker for executing cron tasks
 */
class CronWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "openclaw_cron"
        const val BASE_URL = "http://127.0.0.1:18789"

        /**
         * Schedule all enabled cron tasks
         */
        fun scheduleAll(context: Context) {
            val wm = WorkManager.getInstance(context)
            // Cancel all existing cron work
            wm.cancelAllWorkByTag("openclaw_cron")

            val tasks = CronManager.getTasks(context).filter { it.enabled }
            for (task in tasks) {
                val request = PeriodicWorkRequestBuilder<CronWorker>(
                    task.intervalMinutes.toLong(), TimeUnit.MINUTES
                )
                    .setInputData(workDataOf("task_id" to task.id))
                    .addTag("openclaw_cron")
                    .addTag("cron_${task.id}")
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                wm.enqueueUniquePeriodicWork(
                    "cron_${task.id}",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }
        }

        /**
         * Cancel a specific task
         */
        fun cancelTask(context: Context, taskId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("cron_$taskId")
        }

        /**
         * Create notification channel
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.cron_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.cron_notification_desc)
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.failure()
        val task = CronManager.getTasks(applicationContext).find { it.id == taskId }
            ?: return Result.failure()

        if (!task.enabled) return Result.success()

        return try {
            val response = withContext(Dispatchers.IO) { sendChat(task.prompt) }
            CronManager.updateLastRun(applicationContext, taskId, response.take(200))

            if (task.notify) {
                showNotification(task.name, response.take(300))
            }
            Result.success()
        } catch (e: Exception) {
            CronManager.updateLastRun(applicationContext, taskId, "❌ ${e.message}")
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

    private fun showNotification(title: String, content: String) {
        createNotificationChannel(applicationContext)
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        builder.setContentTitle("🤖 $title")
        builder.setContentText(content)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(content))
        builder.priority = NotificationCompat.PRIORITY_DEFAULT
        builder.setAutoCancel(true)
        nm.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
