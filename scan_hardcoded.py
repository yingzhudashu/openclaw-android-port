"""
Find all hardcoded non-ASCII strings in StatusFragment.kt and other Kotlin files.
"""
import re
import sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

files = [
    r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\StatusFragment.kt',
    r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\SettingsFragment.kt',
    r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\ChatFragment.kt',
    r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\MessageAdapter.kt',
    r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\CronFragment.kt',
    r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\MainActivity.kt',
]

output = []
for fpath in files:
    import os
    fname = os.path.basename(fpath)
    with open(fpath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        # Skip comments
        if stripped.startswith('//') or stripped.startswith('*'):
            continue
        
        # Find all string literals
        for m in re.finditer(r'"([^"]*)"', line):
            val = m.group(1)
            if len(val) < 2:
                continue
            has_nonascii = any(ord(c) > 127 for c in val)
            if has_nonascii:
                output.append(f'{fname}:{i}  "{val}"  hex={val.encode("utf-8").hex()}')

# Also check fragment_status.xml for hardcoded text
path = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\res\layout\fragment_status.xml'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

for m in re.finditer(r'android:text="([^"@][^"]*)"', content):
    val = m.group(1)
    if any(ord(c) > 127 for c in val):
        output.append(f'fragment_status.xml  android:text="{val}"  hex={val.encode("utf-8").hex()}')

# Write results
with open(r'D:\AIhub\openclaw-android-port\hardcoded_strings.txt', 'w', encoding='utf-8') as f:
    f.write(f"Found {len(output)} hardcoded non-ASCII strings\n\n")
    for line in output:
        f.write(line + '\n')

print(f"Found {len(output)} items -> hardcoded_strings.txt")
