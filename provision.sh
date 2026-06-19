#!/usr/bin/env bash
# provision.sh - macOS equivalent of provision.ps1
# Portal HA Bridge one-shot provisioning script
#
# USAGE:
#   ./provision.sh                          # install app if needed, then grant everything
#   ./provision.sh --install                # force reinstall / update to latest APK
#   ./provision.sh --apk /path/to/apk      # install a specific APK
#   ./provision.sh --serial 821XXXXX       # target a specific device
#   ./provision.sh --set-launcher          # also set immortal as the default home launcher

set -uo pipefail

PKG="com.aeonos.portalha"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL=""
APK_PATH=""
FORCE_INSTALL=false
SET_LAUNCHER=false

# ── Argument parsing ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --apk)    APK_PATH="$2"; shift 2 ;;
    --install) FORCE_INSTALL=true; shift ;;
    --set-launcher) SET_LAUNCHER=true; shift ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

# ── Resolve adb ──────────────────────────────────────────────────────────────
ADB=""
if command -v adb &>/dev/null; then
  ADB="$(command -v adb)"
elif [[ -x "$SCRIPT_DIR/platform-tools/adb" ]]; then
  ADB="$SCRIPT_DIR/platform-tools/adb"
else
  echo -e "\033[33madb not found - downloading Android platform-tools (one-time, ~8 MB)...\033[0m"
  zip="$TMPDIR/platform-tools-latest-mac.zip"
  curl -L "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" -o "$zip"
  unzip -q "$zip" -d "$SCRIPT_DIR"
  rm -f "$zip"
  chmod +x "$SCRIPT_DIR/platform-tools/adb"
  if [[ -x "$SCRIPT_DIR/platform-tools/adb" ]]; then
    echo -e "\033[32m  platform-tools ready.\033[0m"
    ADB="$SCRIPT_DIR/platform-tools/adb"
  else
    echo -e "\033[31mplatform-tools download failed. Install manually from https://developer.android.com/tools/releases/platform-tools\033[0m"
    exit 1
  fi
fi

# Build device-target args
TARGET=()
[[ -n "$SERIAL" ]] && TARGET=(-s "$SERIAL")

adb_cmd() { "$ADB" ${TARGET[@]+"${TARGET[@]}"} "$@"; }

# ── Connectivity check ────────────────────────────────────────────────────────
echo -e "\033[36mProvisioning $PKG\033[0m"
ADB_STATE=$(adb_cmd get-state 2>/dev/null || true)
if [[ "$ADB_STATE" != "device" ]]; then
  echo -e "\033[31mNo device reachable via adb (state: '${ADB_STATE:-none}'). Plug in the Portal (accept the USB-debugging prompt on screen), then re-run.\033[0m"
  exit 1
fi

# ── Install app if needed ─────────────────────────────────────────────────────
INSTALLED=false
if adb_cmd shell "pm list packages $PKG" 2>/dev/null | grep -q "package:$PKG"; then
  INSTALLED=true
fi || true

if [[ "$INSTALLED" == false ]] || [[ "$FORCE_INSTALL" == true ]] || [[ -n "$APK_PATH" ]]; then
  # Resolve APK: explicit --apk, then build output, then local files, else download
  RESOLVED_APK=""
  for candidate in \
    "$APK_PATH" \
    "$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk" \
    "$SCRIPT_DIR/portal-ha-bridge.apk" \
    "$SCRIPT_DIR/app-release.apk"
  do
    if [[ -n "$candidate" && -f "$candidate" ]]; then
      RESOLVED_APK="$candidate"
      break
    fi
  done

  if [[ -z "$RESOLVED_APK" ]]; then
    echo -e "\033[33mNo local APK found - downloading the latest release APK...\033[0m"
    RESOLVED_APK="$SCRIPT_DIR/portal-ha-bridge.apk"
    if ! curl -L "https://github.com/RoadRunner-1024/portal-ha-bridge/releases/latest/download/portal-ha-bridge.apk" \
         -o "$RESOLVED_APK"; then
      echo -e "\033[31mCould not download the release APK. Pass --apk <path> or build from source instead.\033[0m"
      exit 1
    fi
    echo -e "\033[32m  downloaded $RESOLVED_APK\033[0m"
  fi

  echo -e "\033[36mInstalling $RESOLVED_APK ...\033[0m"
  adb_cmd install -r -t "$RESOLVED_APK"

  if ! adb_cmd shell "pm list packages $PKG" | grep -q "package:$PKG"; then
    echo -e "\033[31mInstall failed - $PKG is still not present. See adb output above.\033[0m"
    exit 1
  fi
else
  echo -e "\033[90mApp already installed (use --install to force an update).\033[0m"
fi

# ── Grant permissions ─────────────────────────────────────────────────────────
echo -e "\033[36mGranting permissions...\033[0m"
for PERM in \
  "android.permission.WRITE_SECURE_SETTINGS" \
  "android.permission.RECORD_AUDIO" \
  "android.permission.CAMERA" \
  "android.permission.READ_LOGS"
do
  adb_cmd shell "pm grant $PKG $PERM"
  echo -e "\033[32m  granted $PERM\033[0m"
done

adb_cmd shell "appops set $PKG WRITE_SETTINGS allow"
adb_cmd shell "appops set $PKG SYSTEM_ALERT_WINDOW allow"
echo -e "\033[32m  set WRITE_SETTINGS + SYSTEM_ALERT_WINDOW = allow\033[0m"

# ── Optional: set default launcher ───────────────────────────────────────────
if [[ "$SET_LAUNCHER" == true ]]; then
  IMMORTAL="com.immortal.launcher/com.immortal.launcher.HomeActivity"
  if adb_cmd shell "pm list packages com.immortal.launcher" | grep -q "com.immortal.launcher"; then
    adb_cmd shell "cmd package set-home-activity $IMMORTAL"
    echo -e "\033[32m  set default home -> immortal launcher\033[0m"
  else
    echo -e "\033[33m  immortal launcher not installed - skipping launcher step\033[0m"
  fi
fi

# ── Restart app ───────────────────────────────────────────────────────────────
echo -e "\033[36mRestarting app...\033[0m"
adb_cmd shell "am force-stop $PKG"
adb_cmd shell "am start -n $PKG/.DashboardActivity" >/dev/null
sleep 7

# ── Verify ────────────────────────────────────────────────────────────────────
echo -e "\n\033[36mVerification:\033[0m"
DUMP=$(adb_cmd shell "dumpsys package $PKG")

for PERM in CAMERA RECORD_AUDIO READ_LOGS WRITE_SECURE_SETTINGS; do
  if echo "$DUMP" | grep -q "android.permission.${PERM}: granted=true"; then
    printf "  \033[32m%-22s OK\033[0m\n" "$PERM"
  else
    printf "  \033[31m%-22s MISSING\033[0m\n" "$PERM"
  fi
done

if adb_cmd shell "appops get $PKG SYSTEM_ALERT_WINDOW" | grep -q "allow"; then
  printf "  \033[32m%-22s OK\033[0m\n" "SYSTEM_ALERT_WINDOW"
else
  printf "  \033[31m%-22s MISSING\033[0m\n" "SYSTEM_ALERT_WINDOW"
fi

if adb_cmd shell "appops get $PKG WRITE_SETTINGS" | grep -q "allow"; then
  printf "  \033[32m%-22s OK\033[0m\n" "WRITE_SETTINGS"
else
  printf "  \033[31m%-22s MISSING\033[0m\n" "WRITE_SETTINGS"
fi

if adb_cmd shell "settings get secure enabled_accessibility_services" | grep -q "portalha"; then
  printf "  \033[32m%-22s OK\033[0m\n" "ScreenAccessibility"
else
  printf "  \033[33m%-22s not yet - relaunch app\033[0m\n" "ScreenAccessibility"
fi

echo -e "\n\033[36mDone.\033[0m"
