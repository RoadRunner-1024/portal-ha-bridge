<#
  provision.ps1 - one-shot setup for a Portal HA Bridge device.

  Grants every permission/app-op the app needs (all require ADB - they can't be
  granted from the Portal UI), enables the screen-control AccessibilityService,
  optionally installs the latest APK, and optionally sets the immortal launcher
  as the default home.

  USAGE (device connected via adb):
      .\provision.ps1                 # just grant permissions on the connected device
      .\provision.ps1 -Install        # install the APK first, then provision
      .\provision.ps1 -Install -Apk C:\path\portal-ha-bridge.apk   # install a specific APK
      .\provision.ps1 -Serial 821..   # target a specific device (use when several are connected)
      .\provision.ps1 -SetLauncher    # also set immortal as the default home launcher

  With -Install the APK is resolved from, in order: -Apk; the build output
  (app\build\outputs\apk\release\app-release.apk); or a portal-ha-bridge.apk /
  app-release.apk sitting next to this script (e.g. one downloaded from the
  GitHub release). The source ZIP does NOT contain a prebuilt APK.
#>
param(
    [string]$Serial,
    [string]$Apk,
    [switch]$Install,
    [switch]$SetLauncher
)

# NOTE: deliberately NOT "Stop". adb writes harmless first-run chatter to
# stderr ("* daemon not running; starting now ..."), and under -Stop PowerShell
# 5.1 turns any native-command stderr line into a terminating error and aborts
# the whole script. We check $LASTEXITCODE explicitly where it matters instead.
$ErrorActionPreference = "Continue"
$pkg = "com.aeonos.portalha"

# Prefer adb on PATH; otherwise look for a platform-tools folder next to this script.
$adb = if (Get-Command adb -ErrorAction SilentlyContinue) {
    "adb"
} elseif (Test-Path (Join-Path $PSScriptRoot "platform-tools\adb.exe")) {
    Join-Path $PSScriptRoot "platform-tools\adb.exe"
} else {
    Write-Host "adb not found. Install Android platform-tools and add adb to your PATH, then re-run." -ForegroundColor Red
    exit 1
}

# Build the device-target prefix (-s SERIAL) when a serial is given.
$target = @(); if ($Serial) { $target = @("-s", $Serial) }
function Adb { & $adb @target @args }

Write-Host "Provisioning $pkg" -ForegroundColor Cyan
& $adb @target get-state 2>$null | Out-Null   # fail fast if no/ambiguous device
if ($LASTEXITCODE -ne 0) {
    Write-Host "No device reachable via adb. Plug in the Portal (accept the USB-debugging prompt on screen), then re-run." -ForegroundColor Red
    exit 1
}

if ($Install) {
    # Resolve the APK: explicit -Apk, then the build output, then a downloaded
    # APK dropped next to this script.
    $candidates = @()
    if ($Apk) { $candidates += $Apk }
    $candidates += (Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk")
    $candidates += (Join-Path $PSScriptRoot "portal-ha-bridge.apk")
    $candidates += (Join-Path $PSScriptRoot "app-release.apk")
    $apk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $apk) {
        Write-Host "No APK found to install. Either:" -ForegroundColor Red
        Write-Host "  - download portal-ha-bridge.apk from the GitHub release and put it next to this script," -ForegroundColor Red
        Write-Host "  - pass -Apk <path-to-apk>, or" -ForegroundColor Red
        Write-Host "  - build from source first (gradle assembleRelease)." -ForegroundColor Red
        exit 1
    }
    Write-Host "Installing $apk ..." -ForegroundColor Cyan
    Adb install -r -t $apk
}

Write-Host "Granting permissions..." -ForegroundColor Cyan
# Dangerous (runtime) + development-protection permissions - grantable via pm grant.
foreach ($perm in @(
    "android.permission.WRITE_SECURE_SETTINGS",  # auto-enable our AccessibilityService (screen sleep)
    "android.permission.RECORD_AUDIO",           # ambient sound-level sensor
    "android.permission.CAMERA",                 # camera streaming / motion
    "android.permission.READ_LOGS"               # Portal presence sensor (logcat tail)
)) {
    Adb shell "pm grant $pkg $perm"
    Write-Host "  granted $perm" -ForegroundColor Green
}

# Special-access app-ops - set via appops, not pm grant.
Adb shell "appops set $pkg WRITE_SETTINGS allow"        # read/set screen brightness
Adb shell "appops set $pkg SYSTEM_ALERT_WINDOW allow"   # overlay -> background camera access
Write-Host "  set WRITE_SETTINGS + SYSTEM_ALERT_WINDOW = allow" -ForegroundColor Green

if ($SetLauncher) {
    $immortal = "com.immortal.launcher/com.immortal.launcher.HomeActivity"
    if ((Adb shell "pm list packages com.immortal.launcher") -match "com.immortal.launcher") {
        Adb shell "cmd package set-home-activity $immortal"
        Write-Host "  set default home -> immortal launcher" -ForegroundColor Green
    } else {
        Write-Host "  immortal launcher not installed - skipping launcher step" -ForegroundColor Yellow
    }
}

# Restart so the app re-runs setup (notably: auto-enabling the AccessibilityService
# now that WRITE_SECURE_SETTINGS is granted).
Write-Host "Restarting app..." -ForegroundColor Cyan
Adb shell "am force-stop $pkg"
Adb shell "am start -n $pkg/.DashboardActivity" | Out-Null
Start-Sleep -Seconds 7

# ── Verify ──────────────────────────────────────────────────────────────────
Write-Host "`nVerification:" -ForegroundColor Cyan
$dump = Adb shell "dumpsys package $pkg"
foreach ($perm in @("CAMERA","RECORD_AUDIO","READ_LOGS","WRITE_SECURE_SETTINGS")) {
    $ok = ($dump | Select-String "android.permission.${perm}: granted=true").Count -gt 0
    Write-Host ("  {0,-22} {1}" -f $perm, $(if ($ok) {"OK"} else {"MISSING"})) -ForegroundColor $(if ($ok) {"Green"} else {"Red"})
}
$saw = (Adb shell "appops get $pkg SYSTEM_ALERT_WINDOW") -match "allow"
$ws  = (Adb shell "appops get $pkg WRITE_SETTINGS") -match "allow"
Write-Host ("  {0,-22} {1}" -f "SYSTEM_ALERT_WINDOW", $(if ($saw) {"OK"} else {"MISSING"})) -ForegroundColor $(if ($saw) {"Green"} else {"Red"})
Write-Host ("  {0,-22} {1}" -f "WRITE_SETTINGS", $(if ($ws) {"OK"} else {"MISSING"})) -ForegroundColor $(if ($ws) {"Green"} else {"Red"})
$acc = (Adb shell "settings get secure enabled_accessibility_services") -match "portalha"
Write-Host ("  {0,-22} {1}" -f "ScreenAccessibility", $(if ($acc) {"OK"} else {"not yet - relaunch app"})) -ForegroundColor $(if ($acc) {"Green"} else {"Yellow"})

Write-Host "`nDone." -ForegroundColor Cyan
