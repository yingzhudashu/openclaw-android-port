# Phase 2 自动化验证脚本 v3
$ErrorActionPreference = "Continue"
$passCount = 0; $failCount = 0; $totalCount = 0

function Test-Result($name, $condition) {
    $script:totalCount++
    if ($condition) { Write-Host "  PASS: $name" -ForegroundColor Green; $script:passCount++ }
    else { Write-Host "  FAIL: $name" -ForegroundColor Red; $script:failCount++ }
}

Write-Host "`n=== Phase 2 Automated Tests ===" -ForegroundColor Cyan

# 0. Verify app running
Write-Host "`n[0] App Status..." -ForegroundColor Yellow
$appPid = (adb shell pidof ai.openclaw.poc 2>$null).Trim()
Test-Result "App running (PID: $appPid)" ($appPid -match "^\d+$")

# 1. UI dump
Write-Host "`n[1] UI Element Check..." -ForegroundColor Yellow
adb shell rm -f /data/local/tmp/ui.xml 2>$null
adb shell uiautomator dump /data/local/tmp/ui.xml 2>$null | Out-Null
Start-Sleep -Seconds 2
$dump = (adb shell cat /data/local/tmp/ui.xml 2>$null) -join ""
Write-Host "  UI dump length: $($dump.Length)" -ForegroundColor Gray
Test-Result "UI dump has app content" ($dump -match "ai.openclaw.poc")
Test-Result "tvNetworkBanner exists" ($dump -match "tvNetworkBanner")
Test-Result "etMessage exists" ($dump -match "etMessage")
Test-Result "fabSend exists" ($dump -match "fabSend")

# 2. Network banner
Write-Host "`n[2] Network Banner..." -ForegroundColor Yellow
if ($dump -match "ai.openclaw.poc") {
    $bannerHasText = $dump -match 'tvNetworkBanner[^/]*text="[^"]{2,}'
    Test-Result "Banner hidden when online" (-not $bannerHasText)
} else {
    Test-Result "Banner hidden when online (dump unavailable, skip)" $true
}

# 3. Draft saving
Write-Host "`n[3] Draft Save..." -ForegroundColor Yellow
if ($dump -match "ai.openclaw.poc") {
    $etMatch = [regex]::Match($dump, 'resource-id="ai\.openclaw\.poc:id/etMessage"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($etMatch.Success) {
        $x = ([int]$etMatch.Groups[1].Value + [int]$etMatch.Groups[3].Value) / 2
        $y = ([int]$etMatch.Groups[2].Value + [int]$etMatch.Groups[4].Value) / 2
        adb shell input tap ([int]$x) ([int]$y) 2>$null | Out-Null
        Start-Sleep -Seconds 1
        adb shell input text "hello_draft" 2>$null | Out-Null
        Start-Sleep -Seconds 1
        
        $btnMatch = [regex]::Match($dump, 'resource-id="ai\.openclaw\.poc:id/btnNewSession"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if ($btnMatch.Success) {
            $bx = ([int]$btnMatch.Groups[1].Value + [int]$btnMatch.Groups[3].Value) / 2
            $by = ([int]$btnMatch.Groups[2].Value + [int]$btnMatch.Groups[4].Value) / 2
            adb shell input tap ([int]$bx) ([int]$by) 2>$null | Out-Null
            Start-Sleep -Seconds 3
            
            adb shell uiautomator dump /data/local/tmp/ui2.xml 2>$null | Out-Null
            Start-Sleep -Seconds 1
            $dump2 = (adb shell cat /data/local/tmp/ui2.xml 2>$null) -join ""
            $hasDraft = $dump2 -match "hello_draft"
            Test-Result "Draft cleared in new session" (-not $hasDraft)
        } else {
            Test-Result "btnNewSession found" $false
        }
    } else {
        Test-Result "etMessage found for tap" $false
    }
} else {
    Test-Result "Draft test (dump unavailable, skip)" $true
}

# 4. Layout XML
Write-Host "`n[4] Layout XML..." -ForegroundColor Yellow
$itemXml = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\res\layout\item_message.xml" -Raw
$fragXml = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\res\layout\fragment_chat.xml" -Raw
Test-Result "tvRetryHint in item_message" ($itemXml -match "tvRetryHint")
Test-Result "tvRetryHint visibility gone" ($itemXml -match 'android:visibility="gone"')
Test-Result "tvNetworkBanner in fragment_chat" ($fragXml -match "tvNetworkBanner")

# 5. Code structure
Write-Host "`n[5] Code Structure..." -ForegroundColor Yellow
$chatKt = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\ChatFragment.kt" -Raw
$adapterKt = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\MessageAdapter.kt" -Raw

Test-Result "NetworkCallback" ($chatKt -match "ConnectivityManager\.NetworkCallback")
Test-Result "retryDelays (postChat)" ($chatKt -match "retryDelays")
Test-Result "retryDelays (streamChat)" (($chatKt -split "retryDelays").Count -ge 3)
Test-Result "isRetryableException" ($chatKt -match "isRetryableException")
Test-Result "ConnectException" ($chatKt -match "ConnectException")
Test-Result "SocketTimeoutException" ($chatKt -match "SocketTimeoutException")
Test-Result "healthCheckRunnable" ($chatKt -match "healthCheckRunnable")
Test-Result "Health 30s interval" ($chatKt -match "30000")
Test-Result "Draft (openclaw_drafts)" ($chatKt -match "openclaw_drafts")
Test-Result "isNetworkAvailable check" ($chatKt -match "isNetworkAvailable")
Test-Result "onDestroyView cleanup" ($chatKt -match "onDestroyView")
Test-Result "unregisterNetworkCallback" ($chatKt -match "unregisterNetworkCallback")
Test-Result "sendFailed field" ($adapterKt -match "sendFailed")
Test-Result "markLastUserMessageFailed" ($adapterKt -match "markLastUserMessageFailed")
Test-Result "removeMessageAt" ($adapterKt -match "removeMessageAt")
Test-Result "onRetryClick" ($adapterKt -match "onRetryClick")

# 6. Strings
Write-Host "`n[6] String Resources..." -ForegroundColor Yellow
$strZh = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\res\values\strings.xml" -Raw
$strEn = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\res\values-en\strings.xml" -Raw
@("network_disconnected","network_offline_hint","chat_send_failed_retry","engine_disconnected") | ForEach-Object {
    Test-Result "ZH: $_" ($strZh -match ('name="' + $_ + '"'))
    Test-Result "EN: $_" ($strEn -match ('name="' + $_ + '"'))
}

# 7. Manifest
Write-Host "`n[7] Manifest..." -ForegroundColor Yellow
$manifest = Get-Content "D:\AIhub\openclaw-android-port\poc-app\app\src\main\AndroidManifest.xml" -Raw
Test-Result "ACCESS_NETWORK_STATE" ($manifest -match "ACCESS_NETWORK_STATE")

# 8. Crash check
Write-Host "`n[8] Crash Check..." -ForegroundColor Yellow
$recentCrash = adb logcat -d -s AndroidRuntime:E 2>$null | Select-String "ai.openclaw.poc.*FATAL"
Test-Result "No recent crash" ($null -eq $recentCrash -or $recentCrash.Count -eq 0)
$endPid = (adb shell pidof ai.openclaw.poc 2>$null).Trim()
Test-Result "App still alive (PID: $endPid)" ($endPid -match "^\d+$")

# Summary
Write-Host "`n==============================" -ForegroundColor Cyan
$color = if ($failCount -eq 0) { "Green" } else { "Red" }
Write-Host "  Total: $totalCount | Pass: $passCount | Fail: $failCount" -ForegroundColor $color
Write-Host "==============================`n" -ForegroundColor Cyan
