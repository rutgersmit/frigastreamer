#!/usr/bin/env bash
# Installs the latest FrigaStreamer release via ADB on all connected Android devices.
# Requires: gh (logged in: `gh auth login`) and adb (Android platform-tools). No SDK/JDK needed.
#
#   ./scripts/install.sh                 # latest release
#   ./scripts/install.sh v0.2.0          # specific release
#   REPO=owner/repo ./scripts/install.sh # a fork
set -euo pipefail

PACKAGE="com.rutgersmit.frigastreamer"
ACTIVITY="com.rutgersmit.frigastreamer/.ui.MainActivity"
TAG="${1:-}"

fail() { echo "ERROR: $*" >&2; exit 1; }

command -v gh  >/dev/null 2>&1 || fail "'gh' (GitHub CLI) not found. Install it from https://cli.github.com and run 'gh auth login'."
command -v adb >/dev/null 2>&1 || fail "'adb' not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
gh auth status >/dev/null 2>&1 || fail "gh is not logged in. Run 'gh auth login' first."

if [[ -z "${REPO:-}" ]]; then
  REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
  REPO="${REPO:-rutgersmit/frigastreamer}"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading release ${TAG:-(latest)} from $REPO ..."
if [[ -n "$TAG" ]]; then
  gh release download "$TAG" --repo "$REPO" --pattern 'FrigaStreamer-*.apk' --dir "$WORK" \
    || fail "Download of release $TAG failed."
else
  gh release download --repo "$REPO" --pattern 'FrigaStreamer-*.apk' --dir "$WORK" \
    || fail "Download failed. Is there a release yet? (gh release list --repo $REPO)"
fi
shopt -s nullglob
apks=("$WORK"/FrigaStreamer-*.apk)
(( ${#apks[@]} == 1 )) || fail "Expected exactly one APK in the release, found: ${#apks[@]}."
APK="${apks[0]}"
echo "APK: $(basename "$APK")"

adb start-server >/dev/null 2>&1 || true
mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
unauth=$(adb devices | awk 'NR>1 && $2=="unauthorized"' | wc -l)
(( unauth == 0 )) || echo "Note: $unauth device(s) 'unauthorized': confirm the USB debugging prompt on the screen."
(( ${#devices[@]} > 0 )) || fail "No connected devices. Connect via USB or use 'adb pair' + 'adb connect <ip>:<port>' (see README)."

rc=0
for d in "${devices[@]}"; do
  echo "== $d: installing ..."
  if out=$(adb -s "$d" install -r "$APK" 2>&1); then
    echo "$out" | tail -1
    adb -s "$d" shell am start -n "$ACTIVITY" >/dev/null 2>&1 && echo "   started" || echo "   installed, but launching failed"
  else
    echo "$out" | tail -2
    if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
      echo "   The installed app is signed with a different key. Uninstall it first:"
      echo "   adb -s $d uninstall $PACKAGE   (settings will be lost)"
    fi
    rc=1
  fi
done
exit $rc
