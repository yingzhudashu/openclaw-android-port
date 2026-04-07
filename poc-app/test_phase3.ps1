# Phase 3 自动化验证脚本
$ErrorActionPreference = "Continue"
$passCount = 0; $failCount = 0; $totalCount = 0

function Test-Result($name, $condition) {
    $script:totalCount++
    if ($condition) { Write-Host "  PASS: $name" -ForegroundColor Green; $script:passCount++ }
    else { Write-Host "  FAIL: $name" -ForegroundColor Red; $script:failCount++ }
}

Write-Host "`n=== Phase 3 Automated Tests ===" -ForegroundColor Cyan

# App running check
Write-Host "`n[0] App Status..." -ForegroundColor Yellow
$appPid = (adb shell pidof ai.openclaw.poc 2>$null).Trim()
Test-Result "App running" ($appPid -match "^\d+$")

# Code structure check
Write-Host "`n[1] Code Structure..." -ForegroundColor Yellow
$adapter = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\MessageAdapter.kt" -Raw
Test-Result "LinkMovementMethod import" ($adapter -match "LinkMovementMethod")
Test-Result "TextPaint import" ($adapter -match "TextPaint")
Test-Result "copy_code string usage" ($adapter -match "copy_code")
Test-Result "Code block copy ClickableSpan" ($adapter -match "copyToClipboard.*codeContent")
Test-Result "Link ClickableSpan" ($adapter -match "openLink.*url")
Test-Result "renderInline linkColor param" ($adapter -match "renderInline.*linkColor")
Test-Result "Markdown link pattern" ($adapter -match "\\[.*\\]\\(.*\\)")
Test-Result "Bare URL pattern" ($adapter -match "https?://")

# Strings check
Write-Host "`n[2] String Resources..." -ForegroundColor Yellow
$strZh = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\res\values\strings.xml" -Raw
$strEn = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\res\values-en\strings.xml" -Raw
Test-Result "ZH: copy_code" ($strZh -match 'name="copy_code"')
Test-Result "EN: copy_code" ($strEn -match 'name="copy_code"')
Test-Result "ZH: link_open_failed" ($strZh -match 'name="link_open_failed"')
Test-Result "EN: link_open_failed" ($strEn -match 'name="link_open_failed"')

# Crash check
Write-Host "`n[3] Crash Check..." -ForegroundColor Yellow
Start-Sleep -Seconds 3
$crashes = adb logcat -d -s AndroidRuntime:E 2>$null | Select-String "ai.openclaw.poc.*FATAL"
Test-Result "No crash" ($null -eq $crashes -or $crashes.Count -eq 0)

# Summary
Write-Host "`n==============================" -ForegroundColor Cyan
$color = if ($failCount -eq 0) { "Green" } else { "Red" }
Write-Host "  Total: $totalCount | Pass: $passCount | Fail: $failCount" -ForegroundColor $color
Write-Host "==============================`n" -ForegroundColor Cyan
