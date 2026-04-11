package ai.openclaw.poc

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Device Control API (v1.3.0)
 *
 * HTTP endpoints for device features:
 * - POST /device/camera/snap  — Take a photo
 * - GET  /device/location     — Get current GPS location
 * - GET  /device/notifications — List recent notifications
 *
 * Port: 18791 (gateway on 18789, browser bridge on 18790)
 */
class DeviceControlApi(private val context: Context) {

    companion object {
        private const val TAG = "DeviceControlApi"
        const val PORT = 18791
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var running = false

    @Volatile var requestCount: Long = 0
        private set

    fun start() {
        if (running) return
        running = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(PORT, 10, java.net.InetAddress.getByName("127.0.0.1"))
                Log.d(TAG, "HTTP server started on port $PORT")
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Thread { handleClient(socket) }.start()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {
            Log.w(TAG, "close serverSocket failed", e)
        }
        Log.d(TAG, "DeviceControlApi stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val uri = fullPath.split("?")[0]
            val queryString = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

            // Skip headers
            while (input.readLine().isNotEmpty()) {}

            val params = parseQuery(queryString)

            val result: JSONObject = when {
                uri == "/device/camera/snap" && method == "POST" -> handleCameraSnap()
                uri == "/device/location" -> handleLocation()
                uri == "/device/notifications" -> handleNotifications(params["limit"]?.toIntOrNull() ?: 10)
                else -> {
                    sendResponse(socket.getOutputStream(), 404, JSONObject().put("error", "Not found: $method $uri").toString())
                    socket.close()
                    return
                }
            }

            sendResponse(socket.getOutputStream(), 200, result.toString())
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Handle error: ${e.message}")
            try { socket.close() } catch (e: Exception) {
                Log.w(TAG, "close client socket failed", e)
            }
        }
    }

    private fun handleCameraSnap(): JSONObject {
        requestCount++
        Log.d(TAG, "Camera snap requested")

        // Check permission
        if (!PermissionManager.hasCamera(context)) {
            return JSONObject().apply {
                put("error", "Camera permission not granted. Please grant permission in App Settings > Permissions.")
                put("status", "permission_denied")
                put("hint", "Open app settings to grant camera permission.")
            }
        }

        // Create photo request and launch Camera Activity
        val requestId = PhotoStore.createRequest()
        val intent = android.content.Intent(context, CameraCaptureActivity::class.java).apply {
            putExtra(CameraCaptureActivity.EXTRA_REQUEST_ID, requestId)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        // Wait for photo result (blocking, with timeout)
        val result = PhotoStore.waitForResult(requestId)

        return if (result.error != null) {
            JSONObject().apply {
                put("error", result.error)
                put("status", "error")
            }
        } else {
            JSONObject().apply {
                put("status", "ok")
                put("file_path", result.filePath)
                put("width", result.width)
                put("height", result.height)
                put("image_base64", result.base64.take(100)) // Truncated preview
                put("image_size_bytes", result.base64.length * 3 / 4)
                put("hint", "Full base64 image data available (truncated in this response)")
            }
        }
    }

    private fun handleLocation(): JSONObject {
        requestCount++
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val hasPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                JSONObject().apply {
                    put("error", "Location permission not granted")
                    put("status", "permission_denied")
                }
            } else {
                val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

                if (lastLoc != null) {
                    JSONObject().apply {
                        put("latitude", lastLoc.latitude)
                        put("longitude", lastLoc.longitude)
                        put("accuracy", lastLoc.accuracy)
                        put("altitude", lastLoc.altitude)
                        put("provider", lastLoc.provider)
                        put("time", lastLoc.time)
                        put("status", "ok")
                    }
                } else {
                    JSONObject().apply {
                        put("error", "No location available. Enable GPS.")
                        put("status", "unavailable")
                    }
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("error", "location_get failed: ${e.message}")
                put("status", "error")
            }
        }
    }

    private fun handleNotifications(limit: Int): JSONObject {
        requestCount++
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return JSONObject().apply {
                    put("error", "Notifications API requires Android 6.0+")
                    put("status", "unsupported")
                }
            }

            // NotificationListenerService requires user permission setup
            // For now, return a stub with info
            JSONObject().apply {
                put("notifications", JSONArray())
                put("count", 0)
                put("status", "ok")
                put("note", "NotificationListenerService requires user to grant permission in Settings > Notification access. Full implementation needs a NotificationListenerService subclass.")
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("error", "notifications_list failed: ${e.message}")
                put("status", "error")
            }
        }
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, body: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(body.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        return qs.split("&").mapNotNull {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
            else null
        }.toMap()
    }
}
