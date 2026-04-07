"""
Comprehensive scan: find ALL corrupted UTF-8 in the entire project.
Focus on user-visible text (strings.xml, layout text, Kotlin setText calls).
"""
import os
import re

BASE = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main'

def is_corrupted_utf8(text):
    """Check if text contains corrupted UTF-8 (mojibake characters)."""
    for ch in text:
        cp = ord(ch)
        # Valid ranges: ASCII (0-127), real CJK (0x4E00-0x9FFF), 
        # CJK Extension (0x3400-0x4DBF), common symbols, emoji
        if cp > 127:
            # Known mojibake ranges that appear when GBK is misread
            if 0x80 <= cp <= 0xFF:  # Latin-1 supplement (common in mojibake)
                return True
            if cp in (0x0100, 0x0101, 0x010C, 0x010D, 0x0112, 0x0113,  # Latin Extended
                      0x0130, 0x0131, 0x013A, 0x013B, 0x0141, 0x0142,
                      0x0143, 0x0144, 0x0150, 0x0151, 0x0152, 0x0153,
                      0x0160, 0x0161, 0x0162, 0x0163, 0x017D, 0x017E):
                return True
            # Hebrew/Arabic (0x0590-0x06FF) - shouldn't appear in Chinese app
            if 0x0590 <= cp <= 0x06FF:
                return True
            # Check for specific known-bad CJK that are mojibake artifacts
            if ch in '鈻鈩癸笍铲芮裸掇':
                return True
    return False

# ============================================================
# 1. Scan strings.xml - every string value
# ============================================================
print("=" * 60)
print("1. STRINGS.XML - All string values")
print("=" * 60)

path = os.path.join(BASE, 'res', 'values', 'strings.xml')
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', content):
    name, value = m.group(1), m.group(2)
    if is_corrupted_utf8(value):
        # Show the raw bytes
        value_bytes = value.encode('utf-8')
        print(f"  CORRUPTED: {name}")
        print(f"    Display: {value}")
        print(f"    Bytes:   {value_bytes.hex()}")
        print()

# ============================================================
# 2. Scan layout XMLs - hardcoded android:text values
# ============================================================
print("=" * 60)
print("2. LAYOUT XML - Hardcoded android:text values")
print("=" * 60)

layout_dir = os.path.join(BASE, 'res', 'layout')
for f in sorted(os.listdir(layout_dir)):
    if not f.endswith('.xml'):
        continue
    path = os.path.join(layout_dir, f)
    with open(path, 'r', encoding='utf-8') as fh:
        content = fh.read()
    
    for m in re.finditer(r'android:text="([^"@]+)"', content):
        value = m.group(1)
        if len(value) > 0 and is_corrupted_utf8(value):
            print(f"  CORRUPTED in {f}: android:text=\"{value}\"")
            print(f"    Bytes: {value.encode('utf-8').hex()}")
            print()

# ============================================================
# 3. Scan Kotlin - programmatic setText with corrupted strings
# ============================================================
print("=" * 60)
print("3. KOTLIN - Hardcoded strings in code")
print("=" * 60)

kt_dir = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc')
for f in sorted(os.listdir(kt_dir)):
    if not f.endswith('.kt'):
        continue
    path = os.path.join(kt_dir, f)
    with open(path, 'r', encoding='utf-8') as fh:
        lines = fh.readlines()
    
    for i, line in enumerate(lines, 1):
        # Look for hardcoded strings (not in comments)
        stripped = line.strip()
        if stripped.startswith('//') or stripped.startswith('*'):
            continue
        
        # Find string literals
        for m in re.finditer(r'"([^"]*)"', line):
            value = m.group(1)
            if len(value) > 1 and is_corrupted_utf8(value):
                print(f"  CORRUPTED in {f}:{i}")
                print(f"    Code: {stripped[:100]}")
                print(f"    String: \"{value}\"")
                print(f"    Bytes: {value.encode('utf-8').hex()}")
                print()

# ============================================================
# 4. Scan XML comments (<!-- --> blocks that might show in IDE)
# ============================================================
print("=" * 60)
print("4. XML COMMENTS with corrupted text")  
print("=" * 60)

for root, dirs, files in os.walk(os.path.join(BASE, 'res')):
    for f in sorted(files):
        if not f.endswith('.xml'):
            continue
        path = os.path.join(root, f)
        with open(path, 'r', encoding='utf-8') as fh:
            content = fh.read()
        
        for m in re.finditer(r'<!--\s*(.*?)\s*-->', content):
            value = m.group(1)
            if is_corrupted_utf8(value):
                name = os.path.relpath(path, BASE)
                print(f"  CORRUPTED comment in {name}: <!-- {value} -->")
                print()

print("=" * 60)
print("SCAN COMPLETE")
print("=" * 60)
