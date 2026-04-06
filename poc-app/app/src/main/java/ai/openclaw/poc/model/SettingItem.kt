package ai.openclaw.poc.model

/**
 * SettingItem represents a single item in the settings list
 */
data class SettingItem(
    val id: String,
    val icon: String,
    val title: String,
    val summary: String = "",
    val type: SettingItemType = SettingItemType.NAVIGATE,
    val onClick: (() -> Unit)? = null
)

enum class SettingItemType {
    NAVIGATE,    // Navigate to detail page
    ACTION,      // Execute an action directly
    SEPARATOR    // Group separator
}