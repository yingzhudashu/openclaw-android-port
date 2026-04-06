package ai.openclaw.poc

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREF_NAME = "openclaw_prefs"
    private const val KEY_LANGUAGE = "app_language"

    fun getLanguage(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "zh") ?: "zh"
    }

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, lang).apply()
    }

    fun applyLocale(context: Context): Context {
        val lang = getLanguage(context)
        val locale = when (lang) {
            "en" -> Locale.ENGLISH
            else -> Locale.SIMPLIFIED_CHINESE
        }
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun restartActivity(activity: Activity) {
        // Save current session ID before restart so chat is preserved
        val prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("language_just_changed", true).apply()
        val intent = activity.intent
        activity.finish()
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
    }
}
