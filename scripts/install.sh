#!/usr/bin/env bash
# Installeert de nieuwste FrigaStreamer-release via ADB op alle verbonden Android-apparaten.
# Vereist: gh (ingelogd: `gh auth login`) en adb (Android platform-tools). Geen SDK/JDK nodig.
#
#   ./scripts/install.sh                 # nieuwste release
#   ./scripts/install.sh v0.2.0          # specifieke release
#   REPO=eigenaar/repo ./scripts/install.sh
set -euo pipefail

PACKAGE="nl.frigastreamer"
ACTIVITY="nl.frigastreamer/.ui.MainActivity"
TAG="${1:-}"

fail() { echo "FOUT: $*" >&2; exit 1; }

command -v gh  >/dev/null 2>&1 || fail "'gh' (GitHub CLI) niet gevonden. Installeer via https://cli.github.com en voer 'gh auth login' uit."
command -v adb >/dev/null 2>&1 || fail "'adb' niet gevonden. Installeer Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
gh auth status >/dev/null 2>&1 || fail "gh is niet ingelogd. Voer eerst 'gh auth login' uit."

if [[ -z "${REPO:-}" ]]; then
  REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)"
  [[ -n "$REPO" ]] || fail "Kan de repository niet bepalen. Draai dit script vanuit de repository of zet REPO=eigenaar/repo."
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Release ${TAG:-(nieuwste)} downloaden uit $REPO ..."
if [[ -n "$TAG" ]]; then
  gh release download "$TAG" --repo "$REPO" --pattern 'frigate-viewer-*.apk' --dir "$WORK" \
    || fail "Download van release $TAG mislukt."
else
  gh release download --repo "$REPO" --pattern 'frigate-viewer-*.apk' --dir "$WORK" \
    || fail "Download mislukt. Bestaat er al een release? (gh release list --repo $REPO)"
fi
shopt -s nullglob
apks=("$WORK"/frigate-viewer-*.apk)
(( ${#apks[@]} == 1 )) || fail "Verwacht precies één APK in de release, gevonden: ${#apks[@]}."
APK="${apks[0]}"
echo "APK: $(basename "$APK")"

adb start-server >/dev/null 2>&1 || true
mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
unauth=$(adb devices | awk 'NR>1 && $2=="unauthorized"' | wc -l)
(( unauth == 0 )) || echo "Let op: $unauth apparaat/apparaten 'unauthorized': bevestig de USB-debugging-vraag op het scherm."
(( ${#devices[@]} > 0 )) || fail "Geen verbonden apparaten. Sluit aan via USB of gebruik 'adb pair' + 'adb connect <ip>:<poort>' (zie README)."

rc=0
for d in "${devices[@]}"; do
  echo "== $d: installeren ..."
  if out=$(adb -s "$d" install -r "$APK" 2>&1); then
    echo "$out" | tail -1
    adb -s "$d" shell am start -n "$ACTIVITY" >/dev/null 2>&1 && echo "   gestart" || echo "   installatie gelukt, starten mislukt"
  else
    echo "$out" | tail -2
    if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
      echo "   De geïnstalleerde app is met een andere sleutel ondertekend. Verwijder hem eerst:"
      echo "   adb -s $d uninstall $PACKAGE   (instellingen gaan verloren)"
    fi
    rc=1
  fi
done
exit $rc
