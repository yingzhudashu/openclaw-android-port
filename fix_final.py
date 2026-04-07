"""
Fix the remaining 1 corrupted item (XML comment in fragment_chat.xml).
The middle dot (U+00B7 ·) in strings.xml is legitimate, not mojibake.
"""
import os
import re

BASE = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main'

# Fix fragment_chat.xml corrupted comment
path = os.path.join(BASE, 'res', 'layout', 'fragment_chat.xml')
with open(path, 'rb') as f:
    data = f.read()

# Find and replace the corrupted comment bytes
# "缃戠粶鐘舵€佹í骞?" should be "Network status bar" or "网络状态栏"
corrupted = re.search(rb'<!--\s*[^\x00-\x7F]{10,}\s*-->', data)
if corrupted:
    old = corrupted.group(0)
    new = '<!-- Network status bar -->'.encode('utf-8')
    data = data.replace(old, new)
    with open(path, 'wb') as f:
        f.write(data)
    print(f"Fixed comment in fragment_chat.xml")
    print(f"  Old bytes: {old.hex()[:40]}...")
    print(f"  New: <!-- Network status bar -->")
else:
    print("No corrupted comment found in fragment_chat.xml")

print("\nDone! All user-visible encoding issues fixed.")
