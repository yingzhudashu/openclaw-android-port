"""
Fix specific corrupted lines in the project files.
Only touch the exact lines that are broken.
"""
import os
import re

BASE = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main'

def read_utf8(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()

def write_utf8(path, content):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(content)

# ============================================================
# 1. Fix strings.xml - 3 corrupted lines
# ============================================================
print("=== Fix strings.xml ===")
path = os.path.join(BASE, 'res', 'values', 'strings.xml')
content = read_utf8(path)

# Line 298: link_open_failed
content = content.replace(
    '<string name="link_open_failed">\u00de\u0b7e\u00a8\u013a\u00b4\u0137\u00c1\u00b4\u0161\u00b4\u00f2</string>',
    '<string name="link_open_failed">\u65e0\u6cd5\u6253\u5f00\u94fe\u63a5</string>'
)
# Try alternate encoding pattern
content = re.sub(
    r'<string name="link_open_failed">[^<]*</string>',
    '<string name="link_open_failed">\u65e0\u6cd5\u6253\u5f00\u94fe\u63a5</string>',
    content
)

# Line 331: cron_interval_hint
content = re.sub(
    r'<string name="cron_interval_hint">[^<]*</string>',
    '<string name="cron_interval_hint">\u6267\u884c\u95f4\u9694\uff08\u5206\u949f\uff0c\u6700\u5c0f15\uff09</string>',
    content
)

# Line 333: cron_interval_fmt
content = re.sub(
    r'<string name="cron_interval_fmt">[^<]*</string>',
    '<string name="cron_interval_fmt">\u6bcf %d \u5206\u949f</string>',
    content
)

write_utf8(path, content)
print("  Fixed 3 strings")


# ============================================================
# 2. Fix fragment_settings.xml - corrupted emoji on line 335
# ============================================================
print("=== Fix fragment_settings.xml ===")
path = os.path.join(BASE, 'res', 'layout', 'fragment_settings.xml')
with open(path, 'rb') as f:
    raw = f.read()

# Replace corrupted bytes for ℹ️ emoji
# The corrupted bytes are: E9 88 A9 E7 99 B8 E7 AC 8D (鈩癸笍)
# Should be: E2 84 B9 EF B8 8F (ℹ️)
raw = raw.replace(b'\xe9\x88\xa9\xe7\x99\xb8\xe7\xac\x8d', b'\xe2\x84\xb9\xef\xb8\x8f')

# Also check for other corrupted emoji sequences
# 鈻 (E9 88 BB) is corrupted › (E2 80 BA)
raw = raw.replace(b'\xe9\x88\xbb', b'\xe2\x80\xba')

with open(path, 'wb') as f:
    f.write(raw)
print("  Fixed emoji bytes")


# ============================================================
# 3. Fix Kotlin comments (cosmetic only)
# ============================================================
print("=== Fix Kotlin comments ===")

# CronManager.kt line 14
path = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc', 'CronManager.kt')
content = read_utf8(path)
content = re.sub(
    r'//\s*[^\x00-\x7F]+.*?(\n)',
    r'// Execution interval in minutes\1',
    content,
    count=1
)
write_utf8(path, content)
print("  Fixed CronManager.kt")

# MessageAdapter.kt - fix corrupted comments
path = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc', 'MessageAdapter.kt')
content = read_utf8(path)

# Replace corrupted Chinese comments with English equivalents
comment_fixes = [
    (r'//\s*[^\x00-\x7F]*\u00b4\u00a6[^\n]*', '// Copy button'),
    (r'//\s*[^\x00-\x7F]*\u0163[^\n]*', '// Copy button (collapsed state)'),
    (r'/\*\*\s*\n\s*\*\s*[^\x00-\x7F]*\u00c8\u00be[^\n]*', '/**\n     * Render markdown format: **bold**, `code`, _italic_, [link](url)'),
    (r'//\s*Markdown\s*[^\x00-\x7F]+[^\n]*', '// Markdown link [text](url)'),
    (r'//\s*[^\x00-\x7F]*http[^\n]*', '// Auto-detect http:// and https:// URLs'),
    (r'/\*\*\s*\n\s*\*\s*[^\x00-\x7F]*\u013a[^\n]*', '/**\n     * Long press menu'),
]

# Simpler approach: just replace lines with corrupted chars in comments
lines = content.split('\n')
new_lines = []
for line in lines:
    stripped = line.strip()
    if stripped.startswith('//') or stripped.startswith('*'):
        # Check if comment has corrupted chars
        has_corrupt = False
        for ch in stripped:
            cp = ord(ch)
            if cp > 127 and cp < 0x4E00:  # Not real CJK
                has_corrupt = True
                break
        
        if has_corrupt:
            # Try to guess what the comment should be
            if '裸' in stripped or '掇' in stripped:
                indent = len(line) - len(line.lstrip())
                new_lines.append(' ' * indent + '// Long press menu')
            elif 'Markdown' in stripped:
                indent = len(line) - len(line.lstrip())
                new_lines.append(' ' * indent + '// Markdown link [text](url)')
            elif 'http' in stripped:
                indent = len(line) - len(line.lstrip())
                new_lines.append(' ' * indent + '// Auto-detect http/https URLs')
            elif '**' in stripped or '`' in stripped:
                indent = len(line) - len(line.lstrip())
                new_lines.append(' ' * indent + '* Render markdown: **bold**, `code`, _italic_, [link](url)')
            else:
                indent = len(line) - len(line.lstrip())
                # Generic fix: replace corrupted chars with placeholder
                clean = ''.join(ch if ord(ch) < 128 or ord(ch) >= 0x4E00 else '' for ch in stripped)
                if clean.strip() in ('//', '/*', '*/', '*', '/**'):
                    new_lines.append(line)  # Keep as-is if only markers left
                else:
                    new_lines.append(' ' * indent + '// (comment)')
        else:
            new_lines.append(line)
    else:
        new_lines.append(line)

write_utf8(path, '\n'.join(new_lines))
print("  Fixed MessageAdapter.kt comments")

# StatusFragment.kt
path = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc', 'StatusFragment.kt')
content = read_utf8(path)
lines = content.split('\n')
new_lines = []
for line in lines:
    stripped = line.strip()
    if stripped.startswith('//'):
        has_corrupt = any(127 < ord(ch) < 0x4E00 for ch in stripped)
        if has_corrupt:
            indent = len(line) - len(line.lstrip())
            new_lines.append(' ' * indent + '// Button click handler')
        else:
            new_lines.append(line)
    else:
        new_lines.append(line)

write_utf8(path, '\n'.join(new_lines))
print("  Fixed StatusFragment.kt comments")

# SettingsFragment.kt
path = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc', 'SettingsFragment.kt')
content = read_utf8(path)
lines = content.split('\n')
new_lines = []
for line in lines:
    stripped = line.strip()
    if stripped.startswith('//') or (stripped.startswith('*') and not stripped.startswith('*/')):
        has_corrupt = any(127 < ord(ch) < 0x4E00 for ch in stripped)
        if has_corrupt:
            indent = len(line) - len(line.lstrip())
            if 'Model' in stripped or 'Provider' in stripped:
                new_lines.append(' ' * indent + '// Model + Provider settings')
            elif 'SharedPreferences' in stripped or 'adapter' in stripped.lower():
                new_lines.append(' ' * indent + '// Sync model/provider lists to SharedPreferences')
            else:
                new_lines.append(' ' * indent + '// (settings handler)')
        else:
            new_lines.append(line)
    else:
        new_lines.append(line)

write_utf8(path, '\n'.join(new_lines))
print("  Fixed SettingsFragment.kt comments")

# ChatFragment.kt
path = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc', 'ChatFragment.kt')
content = read_utf8(path)
lines = content.split('\n')
new_lines = []
for line in lines:
    stripped = line.strip()
    if stripped.startswith('//') or (stripped.startswith('*') and not stripped.startswith('*/')):
        has_corrupt = any(127 < ord(ch) < 0x4E00 for ch in stripped)
        if has_corrupt:
            indent = len(line) - len(line.lstrip())
            new_lines.append(' ' * indent + '// (handler)')
        else:
            new_lines.append(line)
    else:
        new_lines.append(line)

write_utf8(path, '\n'.join(new_lines))
print("  Fixed ChatFragment.kt comments")


print("\n=== ALL ENCODING FIXES APPLIED ===")
