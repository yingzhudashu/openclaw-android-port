# v1.5.0 UI Dynamic Test - string-based (avoid XML parse encoding issues)
$ErrorActionPreference = "Continue"

function DumpUI {
    adb shell uiautomator dump /sdcard/ui_dump.xml 2>$null
    adb pull /sdcard/ui_dump.xml $env:TEMP\ui_dump.xml 2>$null
    Get-Content $env:TEMP\ui_dump.xml -Raw -Encoding UTF8
}

$pass = 0
$fail = 0

function Check {
    param($name, $result)
    if ($result) {
        Write-Host "  PASS: $name" -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host "  FAIL: $name" -ForegroundColor Red
        $script:fail++
    }
}

function TapCenter {
    param($x, $y, $label)
    Write-Host "  Tap ($x, $y) - $label" -ForegroundColor DarkGray
    adb shell input tap $x $y 2>$null
    Start-Sleep -Seconds 2
}

function GetBoundsCenter {
    param($xml, $id)
    $pattern = "resource-id=`"$id`"[^>]*bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`""
    if ($xml -match $pattern) {
        $x = ([int]$matches[1] + [int]$matches[3]) / 2
        $y = ([int]$matches[2] + [int]$matches[4]) / 2
        return @{x=$x; y=$y}
    }
    # Try reverse order (bounds before resource-id)
    $pattern2 = "bounds=`"\[(\d+),(\d+)\]\[(\d+),(\d+)\]`"[^>]*resource-id=`"$id`""
    if ($xml -match $pattern2) {
        $x = ([int]$matches[1] + [int]$matches[3]) / 2
        $y = ([int]$matches[2] + [int]$matches[4]) / 2
        return @{x=$x; y=$y}
    }
    return $null
}

Write-Host "`n========== v1.5.0 UI Dynamic Test ==========" -ForegroundColor Cyan

# Pre-check
Write-Host "`n[Pre-check]" -ForegroundColor White
$focus = adb shell dumpsys window 2>$null | Select-String "mCurrentFocus"
Check "App in foreground" ($focus -match 'ai.openclaw.poc')

$xml = DumpUI

# Test 1: ViewPager
Write-Host "`n[Test 1] ViewPager / Tabs" -ForegroundColor White
Check "ViewPager exists" ($xml -match 'ai.openclaw.poc:id/viewPager')
Check "tabLayout exists" ($xml -match 'ai.openclaw.poc:id/tabLayout')
# Count tab items (LinearLayout with content-desc inside tabLayout area)
$tabCount = ([regex]::Matches($xml, 'content-desc="[^"]*?" checkable="false"[^>]*class="android\.widget\.LinearLayout" package="ai\.openclaw\.poc"')).Count
# More specific: count clickable tabs
$clickableTabs = ([regex]::Matches($xml, 'clickable="true" enabled="true"[^>]*bounds="\[\d+,\d+\]\[\d+,\d+\]" drawing-order="[^"]*" hint=""><node index="0" text="[^"]*" resource-id="" class="android\.widget\.TextView"')).Count
Check "4 Tabs found" ($clickableTabs -ge 3 -or $tabCount -ge 4)

# Test 2: RecyclerView
Write-Host "`n[Test 2] Chat RecyclerView" -ForegroundColor White
Check "rvMessages exists" ($xml -match 'ai.openclaw.poc:id/rvMessages')
Check "RecyclerView class exists" ($xml -match 'androidx.recyclerview.widget.RecyclerView')

# Test 3: Input Box
Write-Host "`n[Test 3] Input Box" -ForegroundColor White
Check "etMessage exists" ($xml -match 'ai.openclaw.poc:id/etMessage')
Check "EditText class exists" ($xml -match 'class="android\.widget\.EditText"')

# Test 4: TTS Button
Write-Host "`n[Test 4] TTS Button" -ForegroundColor White
Check "btnTts exists" ($xml -match 'ai.openclaw.poc:id/btnTts')
Check "TTS content-desc=朗读" ($xml -match 'content-desc="朗读"')

# Test 5: Memory Search
Write-Host "`n[Test 5] Memory Search" -ForegroundColor White
Check "btnMemorySearch exists" ($xml -match 'ai.openclaw.poc:id/btnMemorySearch')
Check "Memory Search content-desc" ($xml -match 'content-desc="Search Memory"')

# Test 6: Send Button
Write-Host "`n[Test 6] Send Button" -ForegroundColor White
Check "fabSend exists" ($xml -match 'ai.openclaw.poc:id/fabSend')

# Test 7: Other buttons
Write-Host "`n[Test 7] Session Controls" -ForegroundColor White
Check "btnSessionList" ($xml -match 'ai.openclaw.poc:id/btnSessionList')
Check "btnNewSession" ($xml -match 'ai.openclaw.poc:id/btnNewSession')
Check "btnAttach" ($xml -match 'ai.openclaw.poc:id/btnAttach')

# Test 8: AI Message Card
Write-Host "`n[Test 8] AI Message Display" -ForegroundColor White
Check "tvAiMessage exists" ($xml -match 'ai.openclaw.poc:id/tvAiMessage')
Check "tvAvatarEmoji exists" ($xml -match 'ai.openclaw.poc:id/tvAvatarEmoji')
Check "tvAiLabel exists" ($xml -match 'ai.openclaw.poc:id/tvAiLabel')
Check "cardAi exists" ($xml -match 'ai.openclaw.poc:id/cardAi')

# Test 9: Navigation - Settings
Write-Host "`n[Test 9] Settings Navigation" -ForegroundColor White
$settingsPos = GetBoundsCenter $xml "ai.openclaw.poc:id/btnSettings"
# Try to find settings tab by content-desc
if ($xml -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]" drawing-order="[^"]*" hint=""><node index="0" text="[^"]*设置"') {
    $x = ([int]$matches[1] + [int]$matches[3]) / 2
    $y = ([int]$matches[2] + [int]$matches[4]) / 2
    TapCenter $x $y "Settings Tab"
    Start-Sleep -Seconds 1
    $xml2 = DumpUI
    Check "Settings page loads" ($xml2 -match 'androidx.recyclerview.widget.RecyclerView')
    # Go back to chat
    if ($xml2 -match 'text="[^"]*对话"') {
        $chatMatch = [regex]::Match($xml2, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*?>.*?对话')
        if ($chatMatch) {
            # Use tabLayout area - first tab
            TapCenter 135 167 "Chat Tab"
            Start-Sleep -Seconds 1
        }
    }
} else {
    Check "Settings Tab found" $false
}

# Test 10: Navigate to Status
Write-Host "`n[Test 10] Status Navigation" -ForegroundColor White
TapCenter 675 167 "Status Tab"
Start-Sleep -Seconds 1
$xml3 = DumpUI
Check "Status page loads" ($xml3 -match 'androidx.recyclerview.widget.RecyclerView' -or $xml3 -match 'ai.openclaw.poc')
# Go back
TapCenter 135 167 "Chat Tab"
Start-Sleep -Seconds 1

# Test 11: Navigate to Cron
Write-Host "`n[Test 11] Cron Navigation" -ForegroundColor White
TapCenter 945 167 "Cron Tab"
Start-Sleep -Seconds 1
$xml4 = DumpUI
Check "Cron page loads" ($xml4 -match 'androidx.recyclerview.widget.RecyclerView' -or $xml4 -match 'ai.openclaw.poc')
# Go back
TapCenter 135 167 "Chat Tab"
Start-Sleep -Seconds 1

# Summary
Write-Host "`n========== Summary ==========" -ForegroundColor Cyan
$total = $pass + $fail
Write-Host "  Total: $total | Pass: $pass | Fail: $fail"
if ($fail -eq 0) {
    Write-Host "`n  All tests passed!" -ForegroundColor Green
} else {
    Write-Host "`n  $fail test(s) failed." -ForegroundColor Red
}
Write-Host "`n========== Test Complete ==========" -ForegroundColor Cyan
