"""
Fix encoding by reading raw bytes and replacing corrupted sequences.
"""
import os

BASE = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main'

def fix_bytes(path, replacements):
    """Replace byte sequences in a file."""
    with open(path, 'rb') as f:
        data = f.read()
    
    changed = False
    for old, new in replacements:
        if old in data:
            data = data.replace(old, new)
            changed = True
            print(f"  Replaced {old.hex()} -> {new.hex()}")
    
    if changed:
        with open(path, 'wb') as f:
            f.write(data)
        print(f"  Written: {os.path.basename(path)}")
    else:
        print(f"  No changes: {os.path.basename(path)}")


# ============================================================
# 1. Fix strings.xml
# ============================================================
print("=== Fix strings.xml ===")
path = os.path.join(BASE, 'res', 'values', 'strings.xml')

with open(path, 'rb') as f:
    data = f.read()

# Find the exact bytes for each corrupted string
# Line 298: link_open_failed - find the value between > and </string>
import re
pattern = b'<string name="link_open_failed">([^<]*)</string>'
m = re.search(pattern, data)
if m:
    old_val = m.group(1)
    new_val = '无法打开链接'.encode('utf-8')
    data = data.replace(
        b'<string name="link_open_failed">' + old_val + b'</string>',
        b'<string name="link_open_failed">' + new_val + b'</string>'
    )
    print(f"  Fixed link_open_failed: {old_val.hex()[:20]}... -> {new_val.hex()}")

# Line 331: cron_interval_hint
pattern = b'<string name="cron_interval_hint">([^<]*)</string>'
m = re.search(pattern, data)
if m:
    old_val = m.group(1)
    new_val = '执行间隔（分钟，最小15）'.encode('utf-8')
    data = data.replace(
        b'<string name="cron_interval_hint">' + old_val + b'</string>',
        b'<string name="cron_interval_hint">' + new_val + b'</string>'
    )
    print(f"  Fixed cron_interval_hint")

# Line 333: cron_interval_fmt
pattern = b'<string name="cron_interval_fmt">([^<]*)</string>'
m = re.search(pattern, data)
if m:
    old_val = m.group(1)
    new_val = '每 %d 分钟'.encode('utf-8')
    data = data.replace(
        b'<string name="cron_interval_fmt">' + old_val + b'</string>',
        b'<string name="cron_interval_fmt">' + new_val + b'</string>'
    )
    print(f"  Fixed cron_interval_fmt")

with open(path, 'wb') as f:
    f.write(data)


# ============================================================
# 2. Fix fragment_settings.xml - corrupted ℹ️ emoji
# ============================================================
print("\n=== Fix fragment_settings.xml ===")
fix_bytes(
    os.path.join(BASE, 'res', 'layout', 'fragment_settings.xml'),
    [
        (b'\xe9\x88\xa9\xe7\x99\xb8\xe7\xac\x8d', b'\xe2\x84\xb9\xef\xb8\x8f'),  # 鈩癸笍 -> ℹ️
        (b'\xe9\x88\xbb', b'\xe2\x80\xba'),  # 鈻 -> ›
    ]
)


# ============================================================
# 3. Fix Kotlin files - replace corrupted comment lines
# ============================================================
def fix_kotlin_comments(path, comment_map=None):
    """Replace lines that are corrupted comments with clean versions."""
    with open(path, 'rb') as f:
        data = f.read()
    
    lines = data.split(b'\n')
    new_lines = []
    fixed = 0
    
    for line in lines:
        stripped = line.lstrip()
        is_comment = stripped.startswith(b'//') or (stripped.startswith(b'*') and not stripped.startswith(b'*/'))
        
        if is_comment:
            # Check for non-ASCII bytes that aren't valid CJK
            has_corrupt = False
            for byte_val in stripped:
                if byte_val > 127:
                    has_corrupt = True
                    break
            
            if has_corrupt:
                indent = len(line) - len(line.lstrip())
                indent_bytes = b' ' * indent
                
                # Try to determine what the comment should be
                if b'Markdown' in stripped:
                    new_lines.append(indent_bytes + b'// Markdown link [text](url)')
                elif b'http' in stripped:
                    new_lines.append(indent_bytes + b'// Auto-detect http/https URLs')
                elif b'**' in stripped or b'`' in stripped:
                    new_lines.append(indent_bytes + b'* Render markdown: **bold**, `code`, _italic_, [link](url)')
                elif b'Model' in stripped or b'Provider' in stripped:
                    new_lines.append(indent_bytes + b'// Model + Provider settings')
                elif b'SharedPreferences' in stripped:
                    new_lines.append(indent_bytes + b'// Sync to SharedPreferences for MessageAdapter')
                elif stripped == b'//':
                    new_lines.append(line)  # Empty comment, keep
                else:
                    # Generic comment replacement
                    if stripped.startswith(b'* '):
                        new_lines.append(indent_bytes + b'* (comment)')
                    else:
                        new_lines.append(indent_bytes + b'// (comment)')
                fixed += 1
                continue
        
        new_lines.append(line)
    
    if fixed > 0:
        with open(path, 'wb') as f:
            f.write(b'\n'.join(new_lines))
        print(f"  Fixed {fixed} comments in {os.path.basename(path)}")
    else:
        print(f"  No corrupted comments in {os.path.basename(path)}")


print("\n=== Fix Kotlin comments ===")
kt_files = [
    'ChatFragment.kt',
    'MessageAdapter.kt', 
    'StatusFragment.kt',
    'SettingsFragment.kt',
    'CronManager.kt',
    'CronFragment.kt',
    'NodeRunner.kt',
    'MainActivity.kt',
]

for f in kt_files:
    path = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc', f)
    if os.path.exists(path):
        fix_kotlin_comments(path)


# ============================================================
# 4. Fix MessageAdapter.kt specific byte pattern
# ============================================================
print("\n=== Fix MessageAdapter.kt specific bytes ===")
fix_bytes(
    os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc', 'MessageAdapter.kt'),
    [
        # 裸链接 -> raw link
        (b'\xe8\xa3\xb8\xe9\x93\xbe\xe6\x8e\xa5', b'raw link'),
    ]
)


print("\n=== ALL FIXES COMPLETE ===")
