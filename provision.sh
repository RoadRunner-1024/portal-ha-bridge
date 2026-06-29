#!/usr/bin/env bash
#
# provision.sh - one-shot setup for a Portal HA Bridge device (macOS / Linux).
#
# Installs the app if it isn't already on the device, grants every permission/
# app-op it needs (all require ADB - they can't be granted from the Portal UI),
# enables the screen-control AccessibilityService, and optionally sets the
# immortal launcher as the default home.
#
# Needs nothing pre-installed. This single file is enough:
#     1. download provision.sh
#     2. chmod +x provision.sh && ./provision.sh
# It auto-installs the app when missing; if adb isn't on your PATH it downloads
# Google's platform-tools; and if no local APK is found it downloads the latest
# release APK - all automatically.
#
# USAGE (device connected via adb):
#     ./provision.sh                       # install app if needed, then grant everything
#     ./provision.sh --install             # force a reinstall / update to the latest APK
#     ./provision.sh --apk /path/app.apk   # install a specific APK
#     ./provision.sh --serial 821..        # target a specific device (when several are connected)
#     ./provision.sh --set-launcher        # also set immortal as the default home launcher
#
# The APK is resolved from, in order: --apk; the build output
# (app/build/outputs/apk/release/app-release.apk); a portal-ha-bridge.apk /
# app-release.apk next to this script; otherwise the latest GitHub release APK
# is downloaded automatically.

PKG="com.aeonos.portalha"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_APK_URL="https://github.com/RoadRunner-1024/portal-ha-bridge/releases/latest/download/portal-ha-bridge.apk"

# colours (only when stdout is a terminal)
if [ -t 1 ]; then
  C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YEL=$'\033[33m'; C_GREY=$'\033[90m'; C_OFF=$'\033[0m'
else
  C_CYAN=""; C_GREEN=""; C_RED=""; C_YEL=""; C_GREY=""; C_OFF=""
fi

SERIAL=""; APK=""; FORCE_INSTALL=0; SET_LAUNCHER=0
while [ $# -gt 0 ]; do
  case "$1" in
    --install)      FORCE_INSTALL=1 ;;
    --set-launcher) SET_LAUNCHER=1 ;;
    --serial)       SERIAL="$2"; shift ;;
    --apk)          APK="$2"; shift ;;
    -h|--help)      sed -n '3,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)              printf "%sUnknown argument: %s%s\n" "$C_RED" "$1" "$C_OFF"; exit 1 ;;
  esac
  shift
done

# ── Find adb, or bootstrap it ────────────────────────────────────────────────
# Prefer adb on PATH, then a platform-tools folder next to this script, and as a
# last resort download Google's platform-tools (like Immortal's setup) so nothing
# needs to be pre-installed.
resolve_adb() {
  if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
  if [ -x "$SCRIPT_DIR/platform-tools/adb" ]; then echo "$SCRIPT_DIR/platform-tools/adb"; return; fi

  local url zip
  case "$(uname -s)" in
    Darwin) url="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" ;;
    Linux)  url="https://dl.google.com/android/repository/platform-tools-latest-linux.zip" ;;
    *) printf "%sUnsupported OS $(uname -s); install platform-tools manually.%s\n" "$C_RED" "$C_OFF" >&2; exit 1 ;;
  esac
  printf "%sadb not found - downloading Android platform-tools (one-time, ~8 MB)...%s\n" "$C_YEL" "$C_OFF" >&2
  zip="${TMPDIR:-/tmp}/platform-tools.zip"
  if ! curl -fsSL -o "$zip" "$url"; then
    printf "%sCould not download platform-tools. Install it from https://developer.android.com/tools/releases/platform-tools and re-run.%s\n" "$C_RED" "$C_OFF" >&2
    exit 1
  fi
  unzip -o -q "$zip" -d "$SCRIPT_DIR" && rm -f "$zip"
  if [ -x "$SCRIPT_DIR/platform-tools/adb" ]; then
    printf "%s  platform-tools ready.%s\n" "$C_GREEN" "$C_OFF" >&2
    echo "$SCRIPT_DIR/platform-tools/adb"; return
  fi
  printf "%splatform-tools download did not contain adb.%s\n" "$C_RED" "$C_OFF" >&2
  exit 1
}
ADB="$(resolve_adb)"

# adb wrapper that injects -s SERIAL when given
adb_cmd() {
  if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi
}

is_installed() {
  adb_cmd shell pm list packages "$PKG" 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG"
}

printf "%sProvisioning %s%s\n" "$C_CYAN" "$PKG" "$C_OFF"
if ! adb_cmd get-state >/dev/null 2>&1; then
  printf "%sNo device reachable via adb. Plug in the Portal (accept the USB-debugging prompt on screen), then re-run.%s\n" "$C_RED" "$C_OFF"
  exit 1
fi

# ── Install the app if missing (or if --install / --apk forces it) ───────────
if ! is_installed || [ "$FORCE_INSTALL" -eq 1 ] || [ -n "$APK" ]; then
  apk_path=""
  for c in "$APK" \
           "$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk" \
           "$SCRIPT_DIR/portal-ha-bridge.apk" \
           "$SCRIPT_DIR/app-release.apk"; do
    if [ -n "$c" ] && [ -f "$c" ]; then apk_path="$c"; break; fi
  done
  if [ -z "$apk_path" ]; then
    apk_path="$SCRIPT_DIR/portal-ha-bridge.apk"
    printf "%sNo local APK found - downloading the latest release APK...%s\n" "$C_YEL" "$C_OFF"
    if ! curl -fsSL -o "$apk_path" "$RELEASE_APK_URL"; then
      printf "%sCould not download the release APK. Pass --apk <path> or build from source (gradle assembleRelease).%s\n" "$C_RED" "$C_OFF"
      exit 1
    fi
    printf "%s  downloaded %s%s\n" "$C_GREEN" "$apk_path" "$C_OFF"
  fi
  printf "%sInstalling %s ...%s\n" "$C_CYAN" "$apk_path" "$C_OFF"
  adb_cmd install -r -t "$apk_path"
  if ! is_installed; then
    printf "%sInstall failed - %s is still not present. See the adb output above.%s\n" "$C_RED" "$PKG" "$C_OFF"
    exit 1
  fi
else
  printf "%sApp already installed (use --install to force an update).%s\n" "$C_GREY" "$C_OFF"
fi

# ── Grant permissions ────────────────────────────────────────────────────────
printf "%sGranting permissions...%s\n" "$C_CYAN" "$C_OFF"
for perm in WRITE_SECURE_SETTINGS RECORD_AUDIO CAMERA READ_LOGS; do
  adb_cmd shell pm grant "$PKG" "android.permission.$perm"
  printf "%s  granted %s%s\n" "$C_GREEN" "$perm" "$C_OFF"
done
adb_cmd shell appops set "$PKG" WRITE_SETTINGS allow             # read/set screen brightness
adb_cmd shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow        # overlay -> background camera access
adb_cmd shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow   # in-app "Check for Updates"
printf "%s  set WRITE_SETTINGS + SYSTEM_ALERT_WINDOW + REQUEST_INSTALL_PACKAGES = allow%s\n" "$C_GREEN" "$C_OFF"

if [ "$SET_LAUNCHER" -eq 1 ]; then
  if adb_cmd shell pm list packages com.immortal.launcher 2>/dev/null | grep -q com.immortal.launcher; then
    adb_cmd shell cmd package set-home-activity com.immortal.launcher/com.immortal.launcher.HomeActivity >/dev/null 2>&1
    printf "%s  set default home -> immortal launcher%s\n" "$C_GREEN" "$C_OFF"
  else
    printf "%s  immortal launcher not installed - skipping launcher step%s\n" "$C_YEL" "$C_OFF"
  fi
fi

# Restart so the app re-runs setup (notably auto-enabling the AccessibilityService
# now that WRITE_SECURE_SETTINGS is granted).
printf "%sRestarting app...%s\n" "$C_CYAN" "$C_OFF"
adb_cmd shell am force-stop "$PKG"
adb_cmd shell am start -n "$PKG/.DashboardActivity" >/dev/null 2>&1
sleep 7

# ── Verify ───────────────────────────────────────────────────────────────────
printf "\n%sVerification:%s\n" "$C_CYAN" "$C_OFF"
check() {  # $1 = label, $2 = 0/1
  if [ "$2" -eq 1 ]; then printf "  %-22s %sOK%s\n" "$1" "$C_GREEN" "$C_OFF"
  else printf "  %-22s %sMISSING%s\n" "$1" "$C_RED" "$C_OFF"; fi
}
DUMP="$(adb_cmd shell dumpsys package "$PKG" 2>/dev/null)"
for perm in CAMERA RECORD_AUDIO READ_LOGS WRITE_SECURE_SETTINGS; do
  if echo "$DUMP" | grep -q "android.permission.$perm: granted=true"; then check "$perm" 1; else check "$perm" 0; fi
done
echo "$(adb_cmd shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null)" | grep -q allow && check "SYSTEM_ALERT_WINDOW" 1 || check "SYSTEM_ALERT_WINDOW" 0
echo "$(adb_cmd shell appops get "$PKG" WRITE_SETTINGS 2>/dev/null)" | grep -q allow && check "WRITE_SETTINGS" 1 || check "WRITE_SETTINGS" 0
if adb_cmd shell settings get secure enabled_accessibility_services 2>/dev/null | grep -q portalha; then
  printf "  %-22s %sOK%s\n" "ScreenAccessibility" "$C_GREEN" "$C_OFF"
else
  printf "  %-22s %snot yet - relaunch app%s\n" "ScreenAccessibility" "$C_YEL" "$C_OFF"
fi

printf "\n%sDone.%s\n" "$C_CYAN" "$C_OFF"
