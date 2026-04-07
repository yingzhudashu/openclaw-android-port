param(
    [Parameter(Mandatory=$true)]
    [string]$ApkPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found: $ApkPath"
    exit 1
}

$fileSize = (Get-Item $ApkPath).Length
Write-Host "APK: $ApkPath ($([math]::Round($fileSize/1MB, 1)) MB)"

Write-Host "Pushing APK to device..."
adb push $ApkPath /data/local/tmp/openclaw_install.apk
if ($LASTEXITCODE -ne 0) { Write-Error "adb push failed"; exit 1 }

Write-Host "Creating install session..."
$createOutput = adb shell "pm install-create -r -t -g -d -S $fileSize" 2>&1 | Out-String
$match = [regex]::Match($createOutput, '\[(\d+)\]')
if (-not $match.Success) { Write-Error "Failed to create session: $createOutput"; exit 1 }
$sessionId = $match.Groups[1].Value
Write-Host "Session ID: $sessionId"

Write-Host "Writing APK to session..."
$writeOutput = adb shell "pm install-write -S $fileSize $sessionId base /data/local/tmp/openclaw_install.apk" 2>&1 | Out-String
if ($writeOutput -notmatch "Success") { Write-Error "install-write failed: $writeOutput"; exit 1 }

Write-Host "Committing install..."
$commitOutput = adb shell "pm install-commit $sessionId" 2>&1 | Out-String
if ($commitOutput -notmatch "Success") { Write-Error "install-commit failed: $commitOutput"; exit 1 }

$versionInfo = adb shell "dumpsys package ai.openclaw.poc" 2>&1 | Select-String "versionName"
Write-Host "Installed: $($versionInfo.Line.Trim())" -ForegroundColor Green

adb shell "rm /data/local/tmp/openclaw_install.apk" 2>$null
Write-Host "Done!" -ForegroundColor Green
