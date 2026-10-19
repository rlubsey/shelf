#!/usr/bin/env bash
# Builds Shelf, installs it on the TV and gives it the HOME button.
#   ./install.sh            build, install, take over HOME
#   ./install.sh --no-build install build/shelf-home.apk as it is
#   ./install.sh --setup    only grant permissions / take over HOME for the Shelf already on the TV
#                           (use after an update from GitHub; nothing is built or installed)
#   ./install.sh --undo     hand HOME back to Amazon's launcher
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PKG=tv.shelf.home
GUARD=$PKG/$PKG.Guard
OTHER_GUARDS=(
  tv.dumbtv.home/tv.dumbtv.home.Guard
  com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService
)
ADB=${ADB:-adb}
APK=build/shelf-home.apk

adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
sh_() { adb_ shell "$@" </dev/null; }
installed() { sh_ pm path "$1" 2>/dev/null | grep -q '^package:'; }

mode=build
case ${1:-} in
  "") ;;
  --no-build) mode=install ;;
  --setup) mode=setup ;;
  --undo) mode=undo ;;
  *) echo "usage: $0 [--no-build | --setup | --undo]" >&2; exit 2 ;;
esac

command -v "$ADB" >/dev/null || { echo "error: adb not found — brew install android-platform-tools" >&2; exit 1; }
state=$(adb_ get-state 2>/dev/null || true)
[[ $state == device ]] || { echo "error: no TV connected (adb connect <tv-ip>:5555; set ADB_SERIAL if several)" >&2; exit 1; }

# Alarms normally fire no sooner than 5 s from now; the home switch needs them immediate.
set_futurity() {
  local cur
  cur=$(sh_ settings get global alarm_manager_constants | tr -d '\r')
  [[ $cur == null ]] && cur=
  cur=$(printf '%s' "$cur" | tr ',' '\n' | { grep -v '^min_futurity=' || true; } | paste -sd, -)
  if [[ $1 == on ]]; then cur=${cur:+$cur,}min_futurity=0; fi
  if [[ -n $cur ]]; then sh_ settings put global alarm_manager_constants "$cur"
  else sh_ settings delete global alarm_manager_constants >/dev/null; fi
}

# Puts $1 (or nothing) in place of any launcher's service, keeping every other service.
set_guard() {
  local cur drop=(-e "$GUARD" -e '')
  for g in "${OTHER_GUARDS[@]}"; do drop+=(-e "$g"); done
  cur=$(sh_ settings get secure enabled_accessibility_services | tr -d '\r')
  [[ $cur == null ]] && cur=
  cur=$(printf '%s' "$cur" | tr ':' '\n' | { grep -vxF "${drop[@]}" || true; } | paste -sd: -)
  if [[ -n ${1:-} ]]; then cur=${cur:+$cur:}$1; fi
  # Clearing first makes the accessibility manager rebind a service whose process died.
  sh_ settings put secure enabled_accessibility_services '""'
  sleep 1
  if [[ -n $cur ]]; then
    sh_ settings put secure enabled_accessibility_services "$cur"
    sh_ settings put secure accessibility_enabled 1
  else
    sh_ settings put secure accessibility_enabled 0
  fi
}

if [[ $mode == undo ]]; then
  set_futurity off
  set_guard ""
  sh_ appops set $PKG SYSTEM_ALERT_WINDOW default >/dev/null 2>&1 || true
  sh_ appops set $PKG GET_USAGE_STATS default >/dev/null 2>&1 || true
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
  echo "HOME handed back to Amazon's launcher (Shelf stays installed: adb uninstall $PKG)"
  exit 0
fi

if [[ $mode == setup ]]; then
  installed $PKG || { echo "error: Shelf is not installed on this TV; run ./install.sh first" >&2; exit 1; }
  echo "setting up the installed Shelf ($(sh_ dumpsys package $PKG | grep -m1 versionName | tr -d ' '))"
else
  [[ $mode == build ]] && ./build.sh
  [[ -f $APK ]] || { echo "error: $APK not found — run ./build.sh first" >&2; exit 1; }

  out=$(adb_ install -r "$APK" 2>&1 || true)
  case $out in
    *Success*) ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      echo "error: the installed Shelf was signed with a different key." >&2
      echo "  remove it first:  adb uninstall $PKG   (your Shelf settings are lost)" >&2
      exit 1 ;;
    *) echo "error: adb install failed: $out" >&2; exit 1 ;;
  esac
  echo "installed $PKG"
fi

sh_ dumpsys deviceidle whitelist +$PKG >/dev/null
# Lets Shelf's watchdog reconnect its HOME service if Android drops it (see Watchdog.java).
if sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; then
  echo "watchdog: enabled"
else
  echo "note: could not grant WRITE_SECURE_SETTINGS; if HOME stops working, re-run ./install.sh --no-build"
fi
# Android 10+ blocks background activity starts; "display over other apps" is the per-app
# exemption that lets the HOME switch through on Fire OS 8.
if sh_ appops set $PKG SYSTEM_ALERT_WINDOW allow 2>/dev/null; then
  echo "home switch: allowed"
else
  echo "warning: could not allow SYSTEM_ALERT_WINDOW; HOME may land on Amazon's screen" >&2
fi
# "Recently used" row: lets Shelf see which apps were opened most recently.
if sh_ appops set $PKG GET_USAGE_STATS allow 2>/dev/null; then
  echo "recently used: allowed"
else
  echo "note: could not allow usage access; the Recently used row stays hidden" >&2
fi
sh_ cmd package set-home-activity $PKG/.Home >/dev/null 2>&1 || true
set_futurity on
set_guard "$GUARD"
sh_ am start -W -n $PKG/.Home >/dev/null

if installed tv.dumbtv.home; then
  echo "note: dumbtv home is still installed but no longer has the HOME button — adb uninstall tv.dumbtv.home"
fi

sleep 2
printf 'accessibility: %s\n' "$(sh_ settings get secure enabled_accessibility_services)"
printf 'front window:  %s\n' "$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/.* u0 //; s/}$//')"
