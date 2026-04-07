"""
Final comprehensive scan for corrupted emoji (馃xxx pattern) in all XML and Kotlin files.
"""
import os
import re

BASE = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main'

# Known corrupted emoji byte patterns (GBK-mangled emoji)
# 馃 = E9 A6 83 is the start of many corrupted emoji
CORRUPTED_PREFIX = b'\xe9\xa6\x83'

results = []

for root, dirs, files in os.walk(BASE):
    for f in files:
        if not f.endswith(('.xml', '.kt', '.java')):
            continue
        path = os.path.join(root, f)
        with open(path, 'rb') as fh:
            data = fh.read()
        
        if CORRUPTED_PREFIX in data:
            # Find all occurrences
            idx = 0
            while True:
                idx = data.find(CORRUPTED_PREFIX, idx)
                if idx == -1:
                    break
                # Get surrounding context
                start = max(0, idx - 20)
                end = min(len(data), idx + 20)
                context = data[start:end]
                results.append((os.path.relpath(path, BASE), idx, context.hex()))
                idx += 3

with open(r'D:\AIhub\openclaw-android-port\emoji_scan.txt', 'w', encoding='utf-8') as f:
    f.write(f"Found {len(results)} corrupted emoji patterns\n\n")
    for path, offset, hexctx in results:
        f.write(f"FILE: {path} offset={offset}\n")
        f.write(f"HEX: {hexctx}\n\n")

print(f"Found {len(results)} corrupted emoji patterns")
