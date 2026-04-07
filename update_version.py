import re

# openclaw.json
p1 = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\assets\openclaw-engine\openclaw.json'
with open(p1, 'r', encoding='utf-8') as f:
    c = f.read()
c = re.sub(r'"version"\s*:\s*"[^"]*"', '"version": "2.4.1-android"', c)
with open(p1, 'w', encoding='utf-8', newline='\n') as f:
    f.write(c)
print('openclaw.json updated')

# build.gradle.kts
p2 = r'D:\AIhub\openclaw-android-port\poc-app\app\build.gradle.kts'
with open(p2, 'r', encoding='utf-8') as f:
    c = f.read()
c = re.sub(r'versionCode = \d+', 'versionCode = 71', c)
c = re.sub(r'versionName = "[^"]*"', 'versionName = "2.4.1"', c)
with open(p2, 'w', encoding='utf-8', newline='\n') as f:
    f.write(c)
print('build.gradle.kts updated')
