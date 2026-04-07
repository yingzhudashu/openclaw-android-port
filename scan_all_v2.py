"""
Comprehensive scan v2: find ALL corrupted UTF-8, output to file to avoid console encoding issues.
"""
import os
import re
import sys

# Force UTF-8 output
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main'
OUTPUT = r'D:\AIhub\openclaw-android-port\scan_results.txt'

results = []

def is_corrupted_utf8(text):
    for ch in text:
        cp = ord(ch)
        if cp > 127:
            if 0x80 <= cp <= 0xFF:
                return True
            if 0x0100 <= cp <= 0x017E:
                return True
            if 0x0590 <= cp <= 0x06FF:
                return True
            if ch in '\u9489\u9488\u7678\u7b0d\u94f2\u82ae\u88f8\u6387\u9489\u94bb':
                return True
    return False

# 1. strings.xml
path = os.path.join(BASE, 'res', 'values', 'strings.xml')
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', content):
    name, value = m.group(1), m.group(2)
    if is_corrupted_utf8(value):
        results.append(('strings.xml', name, value, value.encode('utf-8').hex()))

# 2. Layout XMLs
layout_dir = os.path.join(BASE, 'res', 'layout')
for f in sorted(os.listdir(layout_dir)):
    if not f.endswith('.xml'):
        continue
    fpath = os.path.join(layout_dir, f)
    with open(fpath, 'r', encoding='utf-8') as fh:
        content = fh.read()
    for m in re.finditer(r'android:text="([^"@]+)"', content):
        value = m.group(1)
        if is_corrupted_utf8(value):
            results.append((f, 'android:text', value, value.encode('utf-8').hex()))

# 3. Kotlin hardcoded strings (non-comment lines only)
kt_dir = os.path.join(BASE, 'java', 'ai', 'openclaw', 'poc')
for f in sorted(os.listdir(kt_dir)):
    if not f.endswith('.kt'):
        continue
    fpath = os.path.join(kt_dir, f)
    with open(fpath, 'r', encoding='utf-8') as fh:
        lines = fh.readlines()
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith('//') or stripped.startswith('*'):
            continue
        for m in re.finditer(r'"([^"]*)"', line):
            value = m.group(1)
            if len(value) > 1 and is_corrupted_utf8(value):
                results.append((f'{f}:{i}', 'string_literal', value, value.encode('utf-8').hex()))

# 4. XML comments
for root, dirs, files in os.walk(os.path.join(BASE, 'res')):
    for f in sorted(files):
        if not f.endswith('.xml'):
            continue
        fpath = os.path.join(root, f)
        with open(fpath, 'r', encoding='utf-8') as fh:
            content = fh.read()
        for m in re.finditer(r'<!--\s*(.*?)\s*-->', content):
            value = m.group(1)
            if is_corrupted_utf8(value):
                name = os.path.relpath(fpath, BASE)
                results.append((name, 'comment', value, ''))

# Write results
with open(OUTPUT, 'w', encoding='utf-8') as f:
    f.write(f"Found {len(results)} corrupted items\n\n")
    for loc, kind, value, hexval in results:
        f.write(f"FILE: {loc}\n")
        f.write(f"TYPE: {kind}\n")
        f.write(f"VALUE: {value}\n")
        if hexval:
            f.write(f"HEX: {hexval}\n")
        f.write("\n")

print(f"Found {len(results)} corrupted items. Results in scan_results.txt")
