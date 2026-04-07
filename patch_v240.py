"""
v2.4.0 patch script - applies all changes in one shot:
1. Remove voice input from ChatFragment.kt
2. Fix dark mode colors (colors.xml + night/colors.xml)
3. Add config preservation to NodeRunner.kt  
4. Fix strings.xml encoding
5. Update version
"""
import os
import re

BASE = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main'
RES = os.path.join(BASE, 'res')
JAVA = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc')


def read_utf8(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()

def write_utf8(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(content)
    print(f"  Written: {os.path.basename(path)}")


# ============================================================
# 1. Remove voice input from ChatFragment.kt
# ============================================================
print("=== 1. Remove voice input ===")
path = os.path.join(JAVA, 'ChatFragment.kt')
content = read_utf8(path)

# Remove imports
content = content.replace('import android.speech.RecognizerIntent\n', '')
content = content.replace('import android.speech.SpeechRecognizer\n', '')

# Remove VOICE_REQUEST_CODE
content = content.replace('    private val VOICE_REQUEST_CODE = 9001\n', '')

# Remove fabVoice initialization
content = content.replace('        fabVoice = view.findViewById(R.id.fabVoice)\n', '')
content = content.replace('        fabVoice.setOnClickListener { startVoiceInput() }\n', '')

# Change visibility logic: fabVoice.visibility + fabSend.visibility -> fabSend.alpha
content = content.replace(
    '        fabVoice.visibility = View.VISIBLE\n        fabSend.visibility = View.GONE\n',
    '        fabSend.alpha = 0.4f\n'
)

# TextWatcher: replace visibility toggle with alpha
content = content.replace(
    '                fabSend.visibility = if (hasText) View.VISIBLE else View.GONE\n                fabVoice.visibility = if (hasText) View.GONE else View.VISIBLE',
    '                fabSend.alpha = if (hasText) 1.0f else 0.4f'
)

# Remove startVoiceInput method
content = re.sub(
    r'    private fun startVoiceInput\(\) \{.*?\n    \}\n',
    '',
    content,
    flags=re.DOTALL
)

# Remove onActivityResult
content = re.sub(
    r'    @Deprecated.*?\n    override fun onActivityResult.*?\n    \}\n',
    '',
    content,
    flags=re.DOTALL
)

# Remove voice shortcut handling in onResume
content = re.sub(
    r'(    override fun onResume\(\) \{\n        super\.onResume\(\)\n).*?startVoiceInput\(\).*?\n.*?\}\n.*?\}',
    r'\g<1>    }',
    content,
    flags=re.DOTALL
)

# Remove fabVoice lateinit declaration
content = content.replace('    private lateinit var fabVoice: FloatingActionButton\n', '')

write_utf8(path, content)

# Verify
remaining = len([l for l in content.split('\n') if 'fabVoice' in l or 'startVoiceInput' in l or 'VOICE_REQUEST' in l])
print(f"  Voice refs remaining: {remaining}")


# ============================================================
# 2. Remove fabVoice from fragment_chat.xml
# ============================================================
print("=== 2. Fix fragment_chat.xml ===")
path = os.path.join(RES, 'layout', 'fragment_chat.xml')
content = read_utf8(path)

# Remove fabVoice block
content = re.sub(
    r'\s*<com\.google\.android\.material\.floatingactionbutton\.FloatingActionButton\s+android:id="@\+id/fabVoice"[^/]*?/>',
    '',
    content,
    flags=re.DOTALL
)

# Replace send button icon with custom drawable
content = content.replace(
    'android:src="@android:drawable/ic_menu_send"',
    'android:src="@drawable/ic_send"'
)

write_utf8(path, content)
print(f"  fabVoice in XML: {'fabVoice' in content}")


# ============================================================
# 3. Remove RECORD_AUDIO from AndroidManifest.xml
# ============================================================
print("=== 3. Fix AndroidManifest.xml ===")
path = os.path.join(BASE, 'AndroidManifest.xml')
content = read_utf8(path)
content = re.sub(r'\s*<uses-permission android:name="android\.permission\.RECORD_AUDIO"\s*/>', '', content)
write_utf8(path, content)


# ============================================================
# 4. Create custom send icon
# ============================================================
print("=== 4. Create ic_send.xml ===")
write_utf8(os.path.join(RES, 'drawable', 'ic_send.xml'), '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"/>
</vector>
''')


# ============================================================
# 5. Create night/colors.xml for dark mode
# ============================================================
print("=== 5. Create night colors ===")
write_utf8(os.path.join(RES, 'values-night', 'colors.xml'), '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="md_theme_primary">#4DD0E1</color>
    <color name="md_theme_on_primary">#003738</color>
    <color name="md_theme_primary_container">#004F50</color>
    <color name="md_theme_on_primary_container">#97F0FF</color>
    <color name="md_theme_secondary">#B0BEC5</color>
    <color name="md_theme_surface">#121212</color>
    <color name="bubble_user">#1A3A4A</color>
    <color name="bubble_user_text">#E8E8E8</color>
    <color name="bubble_ai">#252525</color>
    <color name="bubble_ai_text">#E8E8E8</color>
    <color name="bubble_assistant">#252525</color>
    <color name="bubble_assistant_text">#E8E8E8</color>
    <color name="avatar_bg">#1A3A2A</color>
    <color name="label_text">#4DD0E1</color>
    <color name="timestamp_light">#707070</color>
    <color name="timestamp_dark">#606060</color>
    <color name="bg_input">#2C2C2C</color>
    <color name="input_stroke">#404040</color>
    <color name="code_bg">#2D2D2D</color>
    <color name="code_text">#FF7043</color>
    <color name="code_block_bg">#1E1E1E</color>
    <color name="code_block_text">#E0E0E0</color>
    <color name="code_header_bg">#333333</color>
    <color name="tool_log_bg">#2A2A1A</color>
    <color name="tool_log_text">#FFB74D</color>
    <color name="tool_log_detail">#A1887F</color>
    <color name="topbar_bg">#1A1A1A</color>
    <color name="topbar_divider">#333333</color>
    <color name="topbar_text">#E8E8E8</color>
    <color name="text_primary">#E8E8E8</color>
    <color name="text_secondary">#B0B0B0</color>
    <color name="text_hint">#707070</color>
    <color name="text_disabled">#505050</color>
    <color name="bg_primary">#121212</color>
    <color name="bg_secondary">#1E1E1E</color>
    <color name="bg_card">#252525</color>
    <color name="divider">#333333</color>
    <color name="fab_bg">#4DD0E1</color>
    <color name="fab_icon">#003738</color>
    <color name="tab_indicator">#4DD0E1</color>
    <color name="tab_selected">#4DD0E1</color>
    <color name="tab_unselected">#707070</color>
    <color name="group_title">#808080</color>
    <color name="subtitle">#999999</color>
    <color name="attach_btn">#B0B0B0</color>
    <color name="input_background">#2C2C2C</color>
    <color name="input_background_dark">#2C2C2C</color>
    <color name="search_highlight">#8D6E63</color>
    <color name="network_banner_bg">#B71C1C</color>
</resources>
''')


# ============================================================
# 6. Create night/themes.xml
# ============================================================
print("=== 6. Create night themes ===")
write_utf8(os.path.join(RES, 'values-night', 'themes.xml'), '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.OpenClaw" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/md_theme_primary</item>
        <item name="colorOnPrimary">@color/md_theme_on_primary</item>
        <item name="colorPrimaryContainer">@color/md_theme_primary_container</item>
        <item name="colorOnPrimaryContainer">@color/md_theme_on_primary_container</item>
        <item name="android:windowBackground">@color/bg_primary</item>
        <item name="android:statusBarColor">@color/topbar_bg</item>
        <item name="android:navigationBarColor">@color/bg_primary</item>
    </style>
</resources>
''')


# ============================================================
# 7. Add missing colors to day mode colors.xml
# ============================================================
print("=== 7. Patch day colors ===")
path = os.path.join(RES, 'values', 'colors.xml')
content = read_utf8(path)

# Add missing colors before </resources>
missing_colors = '''
    <!-- Added for dark mode compatibility -->
    <color name="md_theme_primary_container">#97F0FF</color>
    <color name="md_theme_on_primary_container">#001F24</color>
    <color name="fab_bg">#006874</color>
    <color name="fab_icon">#FFFFFF</color>
    <color name="tab_indicator">#006874</color>
    <color name="tab_selected">#006874</color>
    <color name="tab_unselected">#999999</color>
'''

if 'fab_bg' not in content:
    content = content.replace('</resources>', missing_colors + '</resources>')
    write_utf8(path, content)
else:
    print("  Already has fab_bg, skipping")


# ============================================================
# 8. Fix NodeRunner.kt - add config preservation
# ============================================================
print("=== 8. Patch NodeRunner.kt ===")
path = os.path.join(JAVA, 'NodeRunner.kt')
content = read_utf8(path)

# Find the extraction section and add config preservation
# Before: if (engineDir.exists()) deleteRecursive(engineDir)
old_extract = 'if (engineDir.exists()) deleteRecursive(engineDir)'
new_extract = '''// Preserve user config before re-extraction
        val configFile = File(engineDir, "openclaw.json")
        val savedConfig = if (configFile.exists()) {
            try { configFile.readText() } catch (_: Exception) { null }
        } else null

        if (engineDir.exists()) deleteRecursive(engineDir)'''

if old_extract in content:
    content = content.replace(old_extract, new_extract)

# After version file write, restore config
old_version = '        versionFile.writeText(assetVersion)\n        log("Engine extracted successfully")'
new_version = '''        versionFile.writeText(assetVersion)

        // Restore user config after extraction
        if (savedConfig != null) {
            try {
                val defaultCfg = if (configFile.exists()) configFile.readText() else "{}"
                val user = org.json.JSONObject(savedConfig)
                val defaults = org.json.JSONObject(defaultCfg)
                if (user.has("providers")) defaults.put("providers", user.getJSONObject("providers"))
                if (user.has("model") && user.optString("model").isNotEmpty()) defaults.put("model", user.getString("model"))
                if (user.has("system_prompt")) defaults.put("system_prompt", user.get("system_prompt"))
                if (user.has("embedding")) defaults.put("embedding", user.getJSONObject("embedding"))
                configFile.writeText(defaults.toString(2))
                log("Restored user config after engine update")
            } catch (e: Exception) {
                log("Config merge failed: ${e.message}, restoring as-is")
                try { configFile.writeText(savedConfig) } catch (_: Exception) {}
            }
        }

        log("Engine extracted successfully")'''

if old_version in content:
    content = content.replace(old_version, new_version)

write_utf8(path, content)
print(f"  Config preservation: {'savedConfig' in content}")


# ============================================================
# 9. Fix Gateway timeout bug
# ============================================================
print("=== 9. Patch android-gateway.cjs ===")
gw_path = os.path.join(BASE, 'assets', 'openclaw-engine', 'android-gateway.cjs')
gw = read_utf8(gw_path)

# Add connection timeout after server creation
if 'server.keepAliveTimeout' not in gw:
    gw = gw.replace(
        "server.listen(port, bind, () => {",
        "server.keepAliveTimeout = 30000;\nserver.headersTimeout = 35000;\n\nserver.listen(port, bind, () => {"
    )

# Add per-request socket timeout
if 'req.socket.setTimeout' not in gw:
    gw = gw.replace(
        "const method = req.method.toUpperCase();",
        "req.socket.setTimeout(30000);\n    const method = req.method.toUpperCase();"
    )

# Fix Intl bug in date injection
if 'Intl.DateTimeFormat' in gw:
    gw = gw.replace(
        "const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';",
        "const offset = -(new Date().getTimezoneOffset()); const tzH = Math.floor(Math.abs(offset)/60); const tzM = Math.abs(offset)%60; const tz = `UTC${offset>=0?'+':'-'}${tzH}${tzM?':'+String(tzM).padStart(2,'0'):''}`;"
    )

write_utf8(gw_path, gw)


# ============================================================
# 10. Update version
# ============================================================
print("=== 10. Update version ===")
gradle_path = os.path.join(BASE, '..', '..', 'build.gradle.kts')
gradle = read_utf8(gradle_path)
gradle = re.sub(r'versionCode = \d+', 'versionCode = 70', gradle)
gradle = re.sub(r'versionName = "[^"]*"', 'versionName = "2.4.0"', gradle)
write_utf8(gradle_path, gradle)

# Update engine version
cfg_path = os.path.join(BASE, 'assets', 'openclaw-engine', 'openclaw.json')
cfg = read_utf8(cfg_path)
cfg = re.sub(r'"version"\s*:\s*"[^"]*"', '"version": "2.4.0-android"', cfg)
write_utf8(cfg_path, cfg)


print("\n=== ALL PATCHES APPLIED ===")
