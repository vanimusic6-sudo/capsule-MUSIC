# Read-only Android security metadata. NO logcat, cookies, databases or network payloads.
# Requirements: Windows PowerShell 5.1+ and Android platform-tools (adb on PATH).
# Run: powershell -NoProfile -File .\scripts\capsule_device_report.ps1
$ErrorActionPreference = "Stop"
$pkg = "com.nikhil.yt"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
  throw "adb not found. Install Android platform-tools, then retry."
}
$devices = @(adb devices | Select-String '^[^\s]+\s+device$')
if ($devices.Count -ne 1) {
  throw "Exactly one AUTHORIZED Android test device is required. See: adb devices"
}
$path = @(adb shell pm path $pkg 2>&1)
if (-not ($path | Select-String '^package:')) {
  throw "Capsule ($pkg) is not installed on the selected device."
}
$apkList = @(adb shell dumpsys package $pkg)
$version = ($apkList | Select-String -Pattern '^\s*versionName=' | Select-Object -First 1).Line.Trim()
$sdk = (adb shell getprop ro.build.version.sdk).Trim()
$target = ($apkList | Select-String -Pattern 'targetSdk=' | Select-Object -First 1).Line.Trim()
$permissions = @($apkList | Select-String -Pattern '^\s*(?:android\.)?permission\.[A-Z_]+: granted=(true|false)|^\s*android\.permission\.[A-Z_]+$' |
  ForEach-Object { $_.Line.Trim() } | Sort-Object -Unique)
# No intent extras or full dumpsys: those could reveal user account identifiers.
$report = @(
  "Capsule read-only Android security report"
  "Android API level: $sdk"
  "Package: $pkg"
  "Version: $version"
  "Target SDK: $target"
  "Permissions:"
) + $permissions
$out = Join-Path (Get-Location) "capsule-device-security.txt"
[System.IO.File]::WriteAllLines($out, [string[]]$report, [System.Text.UTF8Encoding]::new($false))
Write-Host "Saved local metadata report: $out"
Write-Host "No changes were made to Android or PC. No cookies, tokens, logcat or traffic payloads were captured."
