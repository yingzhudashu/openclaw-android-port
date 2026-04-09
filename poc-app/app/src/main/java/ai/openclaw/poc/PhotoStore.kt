package ai.openclaw.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Photo Store — Shared photo buffer between CameraCaptureActivity and DeviceControlApi
 *
 * Flow:
 * 1. DeviceControlApi.handleCameraSnap() sets a pending flag and generates a request ID
 * 2. CameraCaptureActivity takes photo, saves to file
 * 3. DeviceControlApi polls for the result (with timeout)
 */
object PhotoStore {
    private const val TAG = "PhotoStore"
    private const val MAX_WAIT_MS = 30000L
    private const val PHOTO_DIR = "photos"

    private val pendingRequests = mutableMapOf<String, PendingRequest>()

    data class PendingRequest(
        val id: String,
        @Volatile var photoFile: File? = null,
        @Volatile var error: String? = null,
        val latch: Any = Any(),
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * Create a pending photo request and return the request ID
     */
    fun createRequest(): String {
        val id = System.currentTimeMillis().toString()
        val req = PendingRequest(id)
        synchronized(pendingRequests) {
            pendingRequests[id] = req
        }
        return id
    }

    /**
     * Wait for photo result (blocking, with timeout)
     */
    fun waitForResult(requestId: String): PhotoResult {
        val req = synchronized(pendingRequests) { pendingRequests[requestId] }
            ?: return PhotoResult(error = "Request not found: $requestId")

        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            synchronized(req.latch) {
                if (req.photoFile != null) {
                    return PhotoResult(
                        filePath = req.photoFile!!.absolutePath,
                        base64 = encodeToBase64(req.photoFile!!),
                        width = getImageWidth(req.photoFile!!),
                        height = getImageHeight(req.photoFile!!)
                    )
                }
                if (req.error != null) {
                    return PhotoResult(error = req.error)
                }
            }
            Thread.sleep(200)
        }

        // Timeout
        synchronized(pendingRequests) {
            pendingRequests.remove(requestId)
        }
        return PhotoResult(error = "Photo capture timed out (30s)")
    }

    /**
     * Called by CameraCaptureActivity when photo is saved
     */
    fun setResult(requestId: String, file: File) {
        val req = synchronized(pendingRequests) { pendingRequests[requestId] }
        if (req != null) {
            req.photoFile = file
            Log.d(TAG, "Photo saved: ${file.absolutePath}")
        }
    }

    /**
     * Called by CameraCaptureActivity on error
     */
    fun setError(requestId: String, error: String) {
        val req = synchronized(pendingRequests) { pendingRequests[requestId] }
        if (req != null) {
            req.error = error
            Log.w(TAG, "Photo error: $error")
        }
    }

    /**
     * Clean up old requests
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        synchronized(pendingRequests) {
            pendingRequests.entries.removeAll { (id, req) ->
                now - req.createdAt > 60000
            }
        }
    }

    private fun encodeToBase64(file: File): String {
        return try {
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Base64 encode failed: ${e.message}")
            ""
        }
    }

    private fun getImageWidth(file: File): Int {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth
        } catch (_: Exception) { 0 }
    }

    private fun getImageHeight(file: File): Int {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outHeight
        } catch (_: Exception) { 0 }
    }

    /**
     * Get photo directory for this app
     */
    fun getPhotoDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), PHOTO_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    data class PhotoResult(
        val filePath: String = "",
        val base64: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val error: String? = null
    )
}
