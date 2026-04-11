package ai.openclaw.poc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * 集中管理 App 所需的所有运行时权限
 * 替代原来散落在各处的权限逻辑
 */
object PermissionManager {

    data class PermissionStatus(
        val name: String,
        val androidPermission: String,
        val rationale: String,
        val granted: Boolean
    )

    // 权限定义
    val PERMISSIONS = mapOf(
        "location" to PermissionStatus(
            name = "📍 位置信息",
            androidPermission = Manifest.permission.ACCESS_FINE_LOCATION,
            rationale = "用于获取天气信息和基于位置的服务",
            granted = false
        ),
        "camera" to PermissionStatus(
            name = "📷 相机",
            androidPermission = Manifest.permission.CAMERA,
            rationale = "用于拍照、扫码和图像识别功能",
            granted = false
        ),
        "audio" to PermissionStatus(
            name = "🎤 麦克风",
            androidPermission = Manifest.permission.RECORD_AUDIO,
            rationale = "用于语音输入和语音交互功能",
            granted = false
        ),
        "notification" to PermissionStatus(
            name = "🔔 通知",
            androidPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.POST_NOTIFICATIONS else "",
            rationale = "用于推送任务完成通知和重要提醒",
            granted = false
        ),
        "storage" to PermissionStatus(
            name = "💾 存储",
            androidPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
            rationale = "用于读取和保存文件、图片",
            granted = false
        )
    )

    /**
     * 检查并更新所有权限状态
     */
    fun getAllPermissionStatus(context: Context): List<PermissionStatus> {
        return PERMISSIONS.values.map { status ->
            if (status.androidPermission.isEmpty()) {
                status.copy(granted = true) // 低版本不需要此权限
            } else {
                val granted = context.checkSelfPermission(status.androidPermission) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                status.copy(granted = granted)
            }
        }
    }

    /**
     * 获取权限摘要文本
     */
    fun getSummary(context: Context): String {
        val statuses = getAllPermissionStatus(context)
        val granted = statuses.count { it.granted }
        val total = statuses.size
        return if (granted == total) "✅ 全部已授权" else "$granted/$total 已授权"
    }

    /**
     * 获取未授权的权限列表
     */
    fun getDeniedPermissions(context: Context): List<String> {
        return getAllPermissionStatus(context)
            .filterNot { it.granted }
            .mapNotNull { status ->
                PERMISSIONS.entries.find { it.value.name == status.name }?.key
            }
    }

    /**
     * 检查所有必要权限是否已授予
     */
    fun allGranted(context: Context): Boolean {
        return getDeniedPermissions(context).isEmpty()
    }

    /**
     * 请求单个权限（需在 Activity 中调用）
     */
    fun requestPermission(activity: Activity, permissionKey: String) {
        val permission = PERMISSIONS[permissionKey]?.androidPermission ?: return
        if (permission.isEmpty()) return
        ActivityCompat.requestPermissions(activity, arrayOf(permission), 100 + permissionKey.hashCode())
    }

    /**
     * 打开 App 系统设置页
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    /**
     * 检查单个权限是否已授予
     */
    fun isGranted(context: Context, permissionKey: String): Boolean {
        val permission = PERMISSIONS[permissionKey]?.androidPermission ?: return false
        if (permission.isEmpty()) return true
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 便捷方法：检查位置权限
     */
    fun hasLocation(context: Context): Boolean = isGranted(context, "location")

    /**
     * 便捷方法：检查相机权限
     */
    fun hasCamera(context: Context): Boolean = isGranted(context, "camera")

    /**
     * 便捷方法：检查通知权限
     */
    fun hasNotification(context: Context): Boolean = isGranted(context, "notification")

    /**
     * 便捷方法：检查存储权限
     */
    fun hasStorage(context: Context): Boolean = isGranted(context, "storage")

    /**
     * 便捷方法：检查麦克风权限
     */
    fun hasAudio(context: Context): Boolean = isGranted(context, "audio")
}
