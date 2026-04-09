package ai.openclaw.poc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Transparent Activity for capturing photos via Camera Intent.
 *
 * Usage:
 *   val intent = Intent(context, CameraCaptureActivity::class.java).apply {
 *       putExtra("request_id", requestId)
 *   }
 *   intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 *   context.startActivity(intent)
 *
 * Flow:
 *   1. Creates a temp file for the photo
 *   2. Launches system camera Intent
 *   3. On result, saves path to PhotoStore and finishes
 */
class CameraCaptureActivity : Activity() {

    companion object {
        private const val TAG = "CameraCapture"
        private const val REQUEST_CODE = 2001
        const val EXTRA_REQUEST_ID = "request_id"

        @Volatile var currentRequestId: String? = null
        @Volatile var currentPhotoUri: Uri? = null
        @Volatile var currentPhotoFile: File? = null
    }

    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId.isNullOrEmpty()) {
            Log.w(TAG, "No request_id provided")
            finish()
            return
        }

        // Check camera permission
        if (!PermissionManager.hasCamera(this)) {
            PhotoStore.setError(requestId!!, "Camera permission not granted")
            Toast.makeText(this, "请先授予相机权限", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Create photo file
        val photoFile = try {
            createImageFile()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create image file: ${e.message}")
            PhotoStore.setError(requestId!!, "Failed to create photo file: ${e.message}")
            Toast.makeText(this, "无法创建照片文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentPhotoFile = photoFile

        // Get URI via FileProvider
        val photoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        currentPhotoUri = photoUri

        // Launch camera Intent
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivityForResult(cameraIntent, REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE) {
            finish()
            return
        }

        val reqId = requestId ?: run { finish(); return }

        if (resultCode == RESULT_OK) {
            val file = currentPhotoFile
            if (file != null && file.exists()) {
                // Also save to MediaStore for gallery visibility
                saveToGallery(file)
                PhotoStore.setResult(reqId, file)
                Log.d(TAG, "Photo captured: ${file.absolutePath}")
                Toast.makeText(this, "📷 拍照成功", Toast.LENGTH_SHORT).show()
            } else {
                PhotoStore.setError(reqId, "Photo file not found after capture")
                Toast.makeText(this, "拍照失败: 文件不存在", Toast.LENGTH_SHORT).show()
            }
        } else {
            PhotoStore.setError(reqId, "Camera cancelled by user")
            Toast.makeText(this, "拍照已取消", Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val fileName = "OC_${timestamp}.jpg"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "OC_${timestamp}",
            ".jpg",
            storageDir
        )
    }

    private fun saveToGallery(file: File) {
        try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OpenClaw")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { `in` -> `in`.copyTo(out) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save to gallery: ${e.message}")
        }
    }
}
