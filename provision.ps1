<#
  provision.ps1 - one-shot setup for a Portal HA Bridge device.

  Installs the app if it isn't already on the device, grants every
  permission/app-op it needs (all require ADB - they can't be granted from the
  Portal UI), enables the screen-control AccessibilityService, and optionally
  sets the immortal launcher as the default home.

  Needs nothing pre-installed. This single file is enough:
      1. download provision.ps1
      2. .\provision.ps1
  It auto-installs the app when missing; if adb isn't on your PATH it downloads
  Google's platform-tools; and if no local APK is found it downloads the latest
  release APK - all automatically.

  USAGE (device connected via adb):
      .\provision.ps1                 # install app if needed, then grant everything
      .\provision.ps1 -Install        # force a reinstall / update to the latest APK
      .\provision.ps1 -Apk C:\path\portal-ha-bridge.apk   # install a specific APK
      .\provision.ps1 -Serial 821..   # target a specific device (use when several are connected)
      .\provision.ps1 -SetLauncher    # also set immortal as the default home launcher

  The APK is resolved from, in order: -Apk; the build output
  (app\build\outputs\apk\release\app-release.apk); a portal-ha-bridge.apk /
  app-release.apk sitting next to this script; otherwise the latest GitHub
  release APK is downloaded automatically.
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

# Find adb, or bootstrap it: prefer adb on PATH, then a platform-tools folder
# next to this script, and as a last resort download Google's platform-tools
# automatically (like Immortal's setup) so the user needs nothing pre-installed.
function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $local = Join-Path $PSScriptRoot "platform-tools\adb.exe"
    if (Test-Path $local) { return $local }

    Write-Host "adb not found - downloading Android platform-tools (one-time, ~8 MB)..." -ForegroundColor Yellow
    $url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    $zip = Join-Path $env:TEMP "platform-tools-latest-windows.zip"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath $PSScriptRoot -Force
        Remove-Item $zip -ErrorAction SilentlyContinue
    } catch {
        Write-Host "Could not download platform-tools: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "Install it manually from https://developer.android.com/tools/releases/platform-tools and re-run." -ForegroundColor Red
        exit 1
    }
    if (Test-Path $local) { Write-Host "  platform-tools ready." -ForegroundColor Green; return $local }
    Write-Host "platform-tools download did not contain adb.exe." -ForegroundColor Red
    exit 1
}
$adb = Resolve-Adb

# Build the device-target prefix (-s SERIAL) when a serial is given.
$target = @(); if ($Serial) { $target = @("-s", $Serial) }
function Adb { & $adb @target @args }

Write-Host "Provisioning $pkg" -ForegroundColor Cyan
& $adb @target get-state 2>$null | Out-Null   # fail fast if no/ambiguous device
if ($LASTEXITCODE -ne 0) {
    Write-Host "No device reachable via adb. Plug in the Portal (accept the USB-debugging prompt on screen), then re-run." -ForegroundColor Red
    exit 1
}

# Install the app if it isn't already on the device (or if -Install / -Apk
# forces it). Everything after this needs the package to exist.
$installed = (Adb shell "pm list packages $pkg" | ForEach-Object { $_.Trim() }) -contains "package:$pkg"

if (-not $installed -or $Install -or $Apk) {
    # Resolve the APK: explicit -Apk, then the build output, then an APK dropped
    # next to this script, else download the latest release.
    $candidates = @()
    if ($Apk) { $candidates += $Apk }
    $candidates += (Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk")
    $candidates += (Join-Path $PSScriptRoot "portal-ha-bridge.apk")
    $candidates += (Join-Path $PSScriptRoot "app-release.apk")
    $apk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $apk) {
        # Nothing local - download the latest release APK so a single file
        # (this script) + a single command is all that's needed.
        $apk = Join-Path $PSScriptRoot "portal-ha-bridge.apk"
        Write-Host "No local APK found - downloading the latest release APK..." -ForegroundColor Yellow
        $rel = "https://github.com/RoadRunner-1024/portal-ha-bridge/releases/latest/download/portal-ha-bridge.apk"
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $rel -OutFile $apk -UseBasicParsing
            Write-Host "  downloaded $apk" -ForegroundColor Green
        } catch {
            Write-Host "Could not download the release APK: $($_.Exception.Message)" -ForegroundColor Red
            Write-Host "Pass -Apk <path-to-apk> or build from source (gradle assembleRelease) instead." -ForegroundColor Red
            exit 1
        }
    }
    Write-Host "Installing $apk ..." -ForegroundColor Cyan
    Adb install -r -t $apk

    # Confirm it landed before granting - otherwise pm grant / appops fail with
    # the confusing "No UID for $pkg in user 0" errors.
    $installed = (Adb shell "pm list packages $pkg" | ForEach-Object { $_.Trim() }) -contains "package:$pkg"
    if (-not $installed) {
        Write-Host "Install failed - $pkg is still not present. See the adb output above." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "App already installed (use -Install to force an update)." -ForegroundColor DarkGray
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
Adb shell "appops set $pkg WRITE_SETTINGS allow"            # read/set screen brightness
Adb shell "appops set $pkg SYSTEM_ALERT_WINDOW allow"       # overlay -> background camera access
Adb shell "appops set $pkg REQUEST_INSTALL_PACKAGES allow"  # in-app "Check for Updates"
Write-Host "  set WRITE_SETTINGS + SYSTEM_ALERT_WINDOW + REQUEST_INSTALL_PACKAGES = allow" -ForegroundColor Green

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
