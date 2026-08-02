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
      .\provision.ps1 -FreeAlohaMic   # free the mic for 2-way intercom (disables Meta's "Hey Alexa")
      .\provision.ps1 -RestoreAlohaMic # undo -FreeAlohaMic (re-enable "Hey Alexa")
      .\provision.ps1 -Alexa          # also revive Amazon Alexa (falcon) + link via amazon.com/code (A9 & A10)

  The APK is resolved from, in order: -Apk; the build output
  (app\build\outputs\apk\release\app-release.apk); a portal-ha-bridge.apk /
  app-release.apk sitting next to this script; otherwise the latest GitHub
  release APK is downloaded automatically.
#>
param(
    [string]$Serial,
    [string]$Apk,
    [switch]$Install,
    [switch]$SetLauncher,
    [switch]$FreeAlohaMic,
    [switch]$RestoreAlohaMic,
    [switch]$Alexa
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

# Find aapt/aapt2 candidates (to read an APK's versionCode) so we can auto-update an
# already-installed app when the local build is newer. OPTIONAL - if none work we
# just fall back to the classic "install only if missing" behaviour. Returns a LIST
# (PATH + every build-tools version) because some build-tools aapt2 builds misbehave;
# Get-ApkVersionCode tries each until one yields a version.
function Resolve-Aapt {
    $found = @()
    foreach ($c in @("aapt2","aapt")) {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { $found += $cmd.Source }
    }
    foreach ($root in @("$env:LOCALAPPDATA\Android\Sdk\build-tools", "$env:ANDROID_HOME\build-tools", "F:\android_sdk\build-tools")) {
        if (-not (Test-Path $root)) { continue }
        foreach ($d in (Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
            foreach ($n in @("aapt2.exe","aapt.exe")) {
                $p = Join-Path $d.FullName $n
                if (Test-Path $p) { $found += $p }
            }
        }
    }
    return $found
}
$aaptList = Resolve-Aapt

# versionCode from an APK - tries each aapt until one returns a number, or $null.
function Get-ApkVersionCode($apkPath) {
    if (-not $apkPath -or -not (Test-Path $apkPath)) { return $null }
    foreach ($aapt in $aaptList) {
        $m = (& $aapt dump badging $apkPath 2>$null | Select-String "versionCode='(\d+)'")
        if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    }
    return $null
}

Write-Host "Provisioning $pkg" -ForegroundColor Cyan
& $adb @target get-state 2>$null | Out-Null   # fail fast if no/ambiguous device
if ($LASTEXITCODE -ne 0) {
    Write-Host "No device reachable via adb. Plug in the Portal (accept the USB-debugging prompt on screen), then re-run." -ForegroundColor Red
    exit 1
}

# Install the app if it isn't already on the device (or if -Install / -Apk
# forces it, or the local build is NEWER than what's installed). Everything after
# this needs the package to exist.
$installed = (Adb shell "pm list packages $pkg" | ForEach-Object { $_.Trim() }) -contains "package:$pkg"

# Resolve a LOCAL apk up front (explicit -Apk, build output, or one dropped next to
# the script) - used both for install-if-missing and the newer-build auto-update.
$candidates = @()
if ($Apk) { $candidates += $Apk }
$candidates += (Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk")
$candidates += (Join-Path $PSScriptRoot "portal-ha-bridge.apk")
$candidates += (Join-Path $PSScriptRoot "app-release.apk")
# NOTE: NOT named $apk - PowerShell variables are case-insensitive, so $apk would
# clobber the -Apk PARAM ($Apk) and make the install-decision always fire.
$localApk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1

# Auto-update: if installed AND a local build is newer, refresh it (so re-running
# after a rebuild actually pushes the new version - the old behaviour silently kept
# a stale build). Needs aapt; without it we skip the comparison (install-if-missing).
$newer = $false
if ($installed -and $localApk -and -not $Install -and -not $Apk) {
    $localVc = Get-ApkVersionCode $localApk
    $instVc = $null
    $ivm = (Adb shell "dumpsys package $pkg" | Select-String "versionCode=(\d+)")
    if ($ivm) { $instVc = [int]$ivm.Matches[0].Groups[1].Value }
    if ($localVc -and $instVc -and ($localVc -gt $instVc)) {
        $newer = $true
        Write-Host "Local build is newer (versionCode $localVc > installed $instVc) - updating." -ForegroundColor Cyan
    }
}

if (-not $installed -or $Install -or $Apk -or $newer) {
    if (-not $localApk) {
        # Nothing local - download the latest release APK so a single file
        # (this script) + a single command is all that's needed.
        $localApk = Join-Path $PSScriptRoot "portal-ha-bridge.apk"
        Write-Host "No local APK found - downloading the latest release APK..." -ForegroundColor Yellow
        $rel = "https://github.com/RoadRunner-1024/portal-ha-bridge/releases/latest/download/portal-ha-bridge.apk"
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $rel -OutFile $localApk -UseBasicParsing
            Write-Host "  downloaded $localApk" -ForegroundColor Green
        } catch {
            Write-Host "Could not download the release APK: $($_.Exception.Message)" -ForegroundColor Red
            Write-Host "Pass -Apk <path-to-apk> or build from source (gradle assembleRelease) instead." -ForegroundColor Red
            exit 1
        }
    }
    Write-Host "Installing $localApk ..." -ForegroundColor Cyan
    Adb install -r -t $localApk

    # Confirm it landed before granting - otherwise pm grant / appops fail with
    # the confusing "No UID for $pkg in user 0" errors.
    $installed = (Adb shell "pm list packages $pkg" | ForEach-Object { $_.Trim() }) -contains "package:$pkg"
    if (-not $installed) {
        Write-Host "Install failed - $pkg is still not present. See the adb output above." -ForegroundColor Red
        exit 1
    }
} else {
    $note = if (-not $aaptList) { "App already installed (aapt not found - couldn't check for a newer build; use -Install to force)." }
            else { "App already installed and up to date." }
    Write-Host $note -ForegroundColor DarkGray
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

# Installer-overlay fix -- Gen-1 Portal+ (API < 29) only.
# Meta's com.facebook.aloha.rro.niu.android RRO overlay causes the stock
# package-installer dialog to render white-on-white (invisible Install button).
# Disabling it fixes the blank-installer issue; takes effect immediately, no
# reboot required, and does not affect Shizuku.
$api = ([string](Adb shell "getprop ro.build.version.sdk")).Trim()
if ($api -and ([int]$api -lt 29)) {
    Write-Host "Gen-1 Portal+ detected (API $api) - disabling installer overlay..." -ForegroundColor Cyan
    Adb shell "cmd overlay disable com.facebook.aloha.rro.niu.android" | Out-Null
    Write-Host "  disabled com.facebook.aloha.rro.niu.android" -ForegroundColor Green
}

# Two-way intercom mic fix -- free the Portal's single mic slot (opt-in).
# Meta's "Hey Alexa" wake detector (com.millennium) holds the far-field mic array
# and throttles a third-party AudioRecord to ~20% of real-time, leaving the device
# receive-only for the Portal-to-Portal intercom. Disabling it (reversible, per-user)
# frees the mic so the device becomes two-way. It does NOT touch
# com.facebook.portal.aiservice, so Meta face-presence + Smart-Camera framing stay.
$millennium = "com.millennium"
$hasMillennium = (Adb shell "pm list packages $millennium") -match [regex]::Escape($millennium)
if ($FreeAlohaMic) {
    if ($hasMillennium) {
        Adb shell "pm disable-user --user 0 $millennium" | Out-Null
        Write-Host "  freed mic for two-way intercom (disabled $millennium)" -ForegroundColor Green
    } else {
        Write-Host "  -FreeAlohaMic: $millennium not present - mic already free" -ForegroundColor DarkGray
    }
} elseif ($RestoreAlohaMic) {
    Adb shell "pm enable $millennium" | Out-Null
    Write-Host "  restored $millennium (Hey Alexa) - intercom returns to receive-only" -ForegroundColor Green
}

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
if ($api -and ([int]$api -lt 29)) {
    $overlayOut = (Adb shell "cmd overlay list") -join "`n"
    $overlayOk = $overlayOut -match '\[ \].*com\.facebook\.aloha\.rro\.niu\.android'
    Write-Host ("  {0,-22} {1}" -f "InstallerOverlay", $(if ($overlayOk) {"OK (disabled)"} else {"STILL ENABLED"})) -ForegroundColor $(if ($overlayOk) {"Green"} else {"Red"})
}
if ($FreeAlohaMic) {
    $stillEnabled = (Adb shell "pm list packages -e $millennium") -match [regex]::Escape($millennium)
    $micFreed = -not $stillEnabled
    Write-Host ("  {0,-22} {1}" -f "TwoWayMic", $(if ($micFreed) {"OK (mic freed)"} else {"STILL THROTTLED"})) -ForegroundColor $(if ($micFreed) {"Green"} else {"Red"})
}

# ── Amazon Alexa (falcon) - optional (-Alexa) ────────────────────────────────
# Revives the stock Meta Alexa client and links it via Code-Based Linking (which
# stores its own token, bypassing the Portal's broken keystore). Works on A9 AND
# A10 - our own foreground wake-word detector drives it (millennium can't: on A10
# a background recorder is mic-silenced). After this, enable "Alexa support" in the
# app's Voice & Assistants settings. Reverse: adb uninstall com.amazon.alexa.multimodal.falcon
if ($Alexa) {
    Write-Host "`nProvisioning Amazon Alexa (falcon)..." -ForegroundColor Cyan
    $falcon    = "com.amazon.alexa.multimodal.falcon"
    $falconUrl = "https://github.com/starbrightlab/hey-dist/releases/download/v0.1.0/falcon.apk"
    $falconSha = "76133f807e492e46aaf58e6ae503e623a93af840eaa3eccfb8630f1ecab3268d"
    $falconApk = Join-Path $PSScriptRoot "falcon.apk"

    # Download + verify once, cached next to the script (skip the 115 MB re-download).
    $haveFalcon = (Test-Path $falconApk) -and
        ((Get-FileHash $falconApk -Algorithm SHA256).Hash.ToLower() -eq $falconSha)
    if (-not $haveFalcon) {
        Write-Host "  downloading falcon (~115 MB)..." -ForegroundColor Yellow
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $falconUrl -OutFile $falconApk -UseBasicParsing
        } catch { Write-Host "  falcon download failed: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
        if ((Get-FileHash $falconApk -Algorithm SHA256).Hash.ToLower() -ne $falconSha) {
            Write-Host "  falcon checksum mismatch - delete falcon.apk and retry" -ForegroundColor Red; exit 1
        }
    }
    Write-Host "  falcon.apk verified" -ForegroundColor Green

    # dee.app (the dead standard Alexa app) shares a permission with falcon and blocks its install.
    if ((Adb shell "pm list packages com.amazon.dee.app") -match "com.amazon.dee.app") {
        Adb uninstall com.amazon.dee.app | Out-Null
        Write-Host "  removed conflicting com.amazon.dee.app" -ForegroundColor Green
    }
    Adb install -r $falconApk | Out-Null
    Write-Host "  falcon installed" -ForegroundColor Green

    # Grants + settings so falcon can run and capture.
    foreach ($p in @(
        "android.permission.READ_PHONE_STATE",
        "android.permission.INTERACT_ACROSS_USERS",
        "android.permission.RECORD_AUDIO")) { Adb shell "pm grant $falcon $p" 2>$null | Out-Null }
    Adb shell "appops set $falcon SYSTEM_ALERT_WINDOW allow" | Out-Null
    Adb shell "settings put secure user_setup_complete 1" | Out-Null
    Adb shell "settings put global hidden_api_policy 1" | Out-Null
    Write-Host "  granted falcon perms" -ForegroundColor Green

    # We drive Alexa with our OWN wake word, so millennium isn't needed - disable it if present.
    if ((Adb shell "pm list packages $millennium") -match [regex]::Escape($millennium)) {
        Adb shell "pm disable-user --user 0 $millennium" | Out-Null
    }

    # Code-Based Linking: launch falcon so the sign-in code shows on the Portal.
    Adb shell "am start -n $falcon/com.amazon.alexa.multimodal.LaunchActivity" | Out-Null
    Write-Host "`n  >>> SIGN IN: a code is on the Portal screen. Go to https://amazon.com/code and enter it. <<<" -ForegroundColor Yellow
    Write-Host "      Do it now - connecting can take a few minutes (esp. on Android 10)." -ForegroundColor DarkGray

    # Kick loop: relaunch falcon until its Speech Interaction Manager reaches ReadyState.
    $ready = $false
    for ($i = 0; $i -lt 18; $i++) {
        Start-Sleep -Seconds 30
        if (((Adb logcat -t 300 -s SPCH-SIM_SimStateMachine:I) -join "`n") -match "in ReadyState") { $ready = $true; break }
        Adb shell "am force-stop $falcon" | Out-Null
        Adb shell "am start -n $falcon/com.amazon.alexa.multimodal.LaunchActivity" | Out-Null
        Write-Host ("  ...still connecting ({0}s)" -f (($i + 1) * 30)) -ForegroundColor DarkGray
    }
    if ($ready) {
        Write-Host "  [ok] Alexa connected (ReadyState)" -ForegroundColor Green
        Write-Host "      Enable 'Alexa support' in the app (Settings -> Voice & Assistants), then say your Alexa wake word." -ForegroundColor Cyan
    } else {
        Write-Host "  Alexa not connected within the window. Finish the amazon.com/code sign-in - it connects on its own; re-run -Alexa to re-check." -ForegroundColor Yellow
    }
    # Return the Portal to our dashboard (falcon was left foreground for sign-in).
    Adb shell "am start -n $pkg/.DashboardActivity" | Out-Null
}

Write-Host "`nDone." -ForegroundColor Cyan
