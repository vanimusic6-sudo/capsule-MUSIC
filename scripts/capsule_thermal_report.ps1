# Read-only performance snapshots; does not reset battery statistics or change settings.
# Windows PowerShell 5.1+ / Android platform-tools. Select the scenario on the phone first.
param(
  [ValidateSet("audio-background", "player", "lyrics", "video", "video-background")][string]$Scenario = "lyrics",
  [ValidateSet("release", "dev", "debug")][string]$Variant = "dev",
  [ValidateRange(10, 300)][int]$Seconds = 60
)
$ErrorActionPreference = "Stop"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "Install Android platform-tools and put adb on PATH." }
$devices = @(adb devices | Select-String '^[^\s]+\s+device$')
if ($devices.Count -ne 1) { throw "Exactly one authorized Android test device is required." }
$line = @(Get-Content (Join-Path $PSScriptRoot "..\gradle.properties") | Select-String '^capsuleApplicationId=([a-zA-Z0-9_.]+)$')
if ($line.Count -ne 1) { throw "Missing or ambiguous application identity." }
$pkg = $line[0].Matches[0].Groups[1].Value
if ($Variant -ne "release") { $pkg += ".$Variant" }
if (-not (@(adb shell pm path $pkg) | Select-String '^package:')) { throw "$pkg is not installed." }
$directory = Join-Path (Get-Location) ("capsule-thermal-" + $Scenario + "-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $directory | Out-Null
function Save-Snapshot([string]$Phase) {
  $output = @("Scenario: $Scenario", "Package: $pkg", "UTC: $([DateTime]::UtcNow.ToString('o'))")
  $output += "Battery / temperature (battery temperature is not CPU temperature):"
  $output += @(adb shell dumpsys battery)
  $output += "Thermal service (availability depends on device):"
  $output += @(adb shell dumpsys thermalservice)
  $output += "App memory:"
  $output += @(adb shell dumpsys meminfo $pkg)
  $output += "App rendering statistics:"
  $output += @(adb shell dumpsys gfxinfo $pkg framestats)
  $output += "Current CPU attribution (only matching Capsule processes):"
  $output += @(adb shell dumpsys cpuinfo | Select-String -SimpleMatch $pkg | ForEach-Object { $_.Line })
  [System.IO.File]::WriteAllLines((Join-Path $directory "$Phase.txt"), [string[]]$output, [System.Text.UTF8Encoding]::new($false))
}
Save-Snapshot "before"
Write-Host "Keep scenario '$Scenario' unchanged for $Seconds seconds. No device settings will be changed."
Start-Sleep -Seconds $Seconds
Save-Snapshot "after"
Write-Host "Saved: $directory"
