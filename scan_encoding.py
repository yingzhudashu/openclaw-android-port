"""
Scan all project files for corrupted UTF-8 characters and fix them.
"""
import os
import re

BASE = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main'

def find_corrupted_files():
    """Find all files with corrupted UTF-8 (replacement char U+FFFD or mojibake)"""
    results = []
    for root, dirs, files in os.walk(BASE):
        for f in files:
            if not f.endswith(('.kt', '.xml', '.java')):
                continue
            path = os.path.join(root, f)
            try:
                with open(path, 'rb') as fh:
                    raw = fh.read()
                text = raw.decode('utf-8')
                
                # Check for common mojibake patterns (GBK bytes misread as UTF-8)
                # These appear as sequences like 锟斤拷, 鈻, etc.
                has_replacement = '\ufffd' in text
                
                # Check for known corrupted sequences
                corrupted_chars = set()
                for i, line in enumerate(text.split('\n'), 1):
                    for ch in line:
                        cp = ord(ch)
                        # CJK chars that are clearly mojibake (not real Chinese)
                        # These appear in comments/strings where original was Chinese
                        if cp == 0xFFFD:
                            corrupted_chars.add(f"U+FFFD at line {i}")
                
                # Also check for byte sequences that indicate double-encoding
                # Pattern: bytes that form valid but nonsensical CJK when the source was GBK
                suspicious_patterns = [
                    b'\xe9\x88\xbb',  # 鈻 (corrupted ›)
                    b'\xe9\x88\xa9',  # 鈩 (corrupted ℹ)
                    b'\xe7\x99\xb8',  # 癸
                    b'\xe7\xac\x8d',  # 笍
                    b'\xe8\xa3\xb8',  # 裸
                    b'\xe6\x8e\x87',  # 掇
                ]
                
                found_suspicious = []
                for pat in suspicious_patterns:
                    if pat in raw:
                        found_suspicious.append(raw.hex()[raw.index(pat)*2:raw.index(pat)*2+20])
                
                if has_replacement or found_suspicious:
                    results.append((path, has_replacement, found_suspicious))
                    
            except Exception as e:
                results.append((path, False, [str(e)]))
    
    return results

# Scan
print("=== Scanning for corrupted files ===")
corrupted = find_corrupted_files()
if not corrupted:
    print("No corrupted files found!")
else:
    for path, has_repl, suspicious in corrupted:
        name = os.path.relpath(path, BASE)
        print(f"\n{name}:")
        if has_repl:
            print(f"  Has U+FFFD replacement chars")
        if suspicious:
            print(f"  Suspicious byte patterns: {suspicious}")

# Now find ALL lines with non-ASCII in layout XMLs and Kotlin files
print("\n\n=== Lines with non-ASCII characters ===")
for root, dirs, files in os.walk(BASE):
    for f in files:
        if not f.endswith(('.kt', '.xml')):
            continue
        path = os.path.join(root, f)
        with open(path, 'r', encoding='utf-8') as fh:
            lines = fh.readlines()
        
        bad_lines = []
        for i, line in enumerate(lines, 1):
            # Find chars that are NOT standard ASCII, NOT common CJK, NOT emoji
            for ch in line:
                cp = ord(ch)
                if cp > 127:
                    # Check if it's a known-bad mojibake char
                    if cp in range(0x9480, 0x94FF) or cp in range(0x9260, 0x92FF):
                        bad_lines.append((i, line.strip()[:80]))
                        break
                    # Check for specific known-bad sequences
                    if ch in '鈻鈩癸笍裸掇铲芮':
                        bad_lines.append((i, line.strip()[:80]))
                        break
        
        if bad_lines:
            name = os.path.relpath(path, BASE)
            print(f"\n{name}:")
            for ln, text in bad_lines[:10]:
                print(f"  Line {ln}: {text}")
