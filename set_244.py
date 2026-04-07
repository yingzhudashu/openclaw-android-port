import re

# Bump to 2.4.4 to force re-extraction
p = r'D:\AIhub\openclaw-android-port\poc-app\app\build.gradle.kts'
with open(p, 'r', encoding='utf-8') as f:
    c = f.read()
c = re.sub(r'versionCode = \d+', 'versionCode = 74', c)
c = re.sub(r'versionName = "[^"]*"', 'versionName = "2.4.4"', c)
with open(p, 'w', encoding='utf-8', newline='\n') as f:
    f.write(c)

p2 = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\assets\openclaw-engine\openclaw.json'
with open(p2, 'r', encoding='utf-8') as f:
    c2 = f.read()
c2 = re.sub(r'"version"\s*:\s*"[^"]*"', '"version": "2.4.4-android"', c2)
with open(p2, 'w', encoding='utf-8', newline='\n') as f:
    f.write(c2)
print('2.4.4')
