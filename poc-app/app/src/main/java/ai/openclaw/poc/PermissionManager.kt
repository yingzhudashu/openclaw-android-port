package ai.openclaw.poc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Permission Manager (v1.4.0)
 * 
 * Centralized runtime permission management for:
 * - 📍 Location (ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION)
 * - 📷 Camera (CAMERA)
 * - 🎤 Microphone (RECORD_AUDIO)
 * - 🔔 Notifications (POST_NOTIFICATIONS)
 * - 📁 Storage (READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE)
 */
object PermissionManager {

    // Request codes
    const val REQUEST_LOCATION = 1001
    const val REQUEST_CAMERA = 1002
    const val REQUEST_AUDIO = 1003
    const val REQUEST_NOTIFICATIONS = 1004
    const val REQUEST_STORAGE = 1005
    const val REQUEST_ALL = 1000

    data class PermissionStatus(
        val name: String,
        val icon: String,
        val granted: Boolean,
        val rationale: String,
        val permissions: List<String>
    )

    /**
     * Check all managed permissions and return their status
     */
    fun getAllPermissionStatus(context: Context): List<PermissionStatus> {
        return listOf(
            PermissionStatus(
                name = "📍 位置",
                icon = "location",
                granted = hasLocation(context),
                rationale = "用于获取设备 GPS 位置信息",
                permissions = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            ),
            PermissionStatus(
                name = "📷 相机",
                icon = "camera",
                granted = hasCamera(context),
                rationale = "用于拍照功能",
                permissions = listOf(Manifest.permission.CAMERA)
            ),
            PermissionStatus(
                name = "🎤 麦克风",
                icon = "microphone",
                granted = hasAudio(context),
                rationale = "用于语音输入",
                permissions = listOf(Manifest.permission.RECORD_AUDIO)
            ),
            PermissionStatus(
                name = "🔔 通知",
                icon = "notification",
                granted = hasNotification(context),
                rationale = "用于发送应用通知",
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
            ),
            PermissionStatus(
                name = "📁 存储",
                icon = "storage",
                granted = hasStorage(context),
                rationale = "用于读取/保存照片和文件",
                permissions = getStoragePermissions()
            )
        )
    }

    /**
     * Request all permissions at once
     */
    fun requestAllPermissions(activity: Activity) {
        val permissionsToRequest = mutableListOf<String>()

        // Location
        if (!hasLocation(activity)) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Camera
        if (!hasCamera(activity)) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        // Audio
        if (!hasAudio(activity)) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotification(activity)) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Storage
        if (!hasStorage(activity)) {
            permissionsToRequest.addAll(getStoragePermissions())
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toTypedArray(),
                REQUEST_ALL
            )
        }
    }

    /**
     * Request a specific permission group
     */
    fun requestPermission(activity: Activity, permissionType: String) {
        val permissions = when (permissionType) {
            "location" -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            "camera" -> listOf(Manifest.permission.CAMERA)
            "audio" -> listOf(Manifest.permission.RECORD_AUDIO)
            "notification" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else emptyList()
            "storage" -> getStoragePermissions()
            else -> emptyList()
        }

        if (permissions.isNotEmpty()) {
            val requestCode = when (permissionType) {
                "location" -> REQUEST_LOCATION
                "camera" -> REQUEST_CAMERA
                "audio" -> REQUEST_AUDIO
                "notification" -> REQUEST_NOTIFICATIONS
                "storage" -> REQUEST_STORAGE
                else -> REQUEST_ALL
            }
            ActivityCompat.requestPermissions(
                activity,
                permissions.toTypedArray(),
                requestCode
            )
        }
    }

    /**
     * Open app settings page for manual permission management
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    // ─── Permission Checks ───────────────────────────────────────

    fun hasLocation(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
               hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun hasCamera(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.CAMERA)
    }

    fun hasAudio(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.RECORD_AUDIO)
    }

    fun hasNotification(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Pre-Android 13 doesn't need notification permission
        }
    }

    fun hasStorage(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true // Android 10+ has scoped storage
        } else {
            hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getStoragePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            emptyList() // Scoped storage, no permission needed
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Get a human-readable summary of all permissions
     */
    fun getSummary(context: Context): String {
        val status = getAllPermissionStatus(context)
        val granted = status.count { it.granted }
        val total = status.size
        return "$granted/$total 已授权"
    }

    /**
     * Check if any critical permissions are missing
     */
    fun hasAnyMissing(context: Context): Boolean {
        return getAllPermissionStatus(context).any { !it.granted }
    }
}
