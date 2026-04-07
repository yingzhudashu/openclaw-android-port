"""
Fix corrupted emoji in fragment_status.xml.
"""

path = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\res\layout\fragment_status.xml'

with open(path, 'rb') as f:
    data = f.read()

replacements = [
    # 馃搨 -> 📂 (folder emoji, U+1F4C2)
    (b'\xe9\xa6\x83\xe6\x90\xa8', b'\xf0\x9f\x93\x82'),
    # 馃挰 -> 💬 (speech bubble emoji, U+1F4AC) 
    (b'\xe9\xa6\x83\xe6\x8c\xb0', b'\xf0\x9f\x92\xac'),
]

for old, new in replacements:
    if old in data:
        data = data.replace(old, new)
        print(f"Fixed: {old.hex()} -> {new.hex()}")

with open(path, 'wb') as f:
    f.write(data)

print("Done!")

# Verify
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

import re
for m in re.finditer(r'android:text="([^"@][^"]*)"', content):
    val = m.group(1)
    if 'Workspace' in val or 'Activity' in val:
        print(f"  Verified: android:text=\"{val}\"")
