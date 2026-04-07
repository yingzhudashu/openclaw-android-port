"""
Fix ChatFragment.kt to handle error events from Gateway SSE stream.
"""

path = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\ChatFragment.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Add error handling before the choices check
old = '''                                if (json.has("choices")) {'''
new = '''                                // Handle error from Gateway
                                if (json.has("error")) {
                                    val errorCode = json.optString("error", "unknown")
                                    val errorMsg = when (errorCode) {
                                        "missing_api_key" -> "未配置 API Key，请在设置中添加"
                                        "unknown_model" -> "未找到模型，请检查配置"
                                        "rate_limited" -> "请求频率过高，请稍后重试"
                                        else -> json.optString("message", errorCode)
                                    }
                                    throw Exception(errorMsg)
                                }
                                
                                if (json.has("choices")) {'''

content = content.replace(old, new)

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

# Verify
if 'missing_api_key' in content:
    print("Error handling added successfully")
else:
    print("ERROR: patch failed")
