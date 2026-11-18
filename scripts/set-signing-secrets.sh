#!/usr/bin/env bash
# Zet de vier signing-secrets in de GitHub-repository (eenmalig).
#
#   ./scripts/set-signing-secrets.sh <keystore.p12> <signing-secrets.env> [eigenaar/repo]
#
# signing-secrets.env bevat SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS en SIGNING_KEY_PASSWORD.
# Bewaar de keystore en de wachtwoorden daarnaast op een veilige plek (wachtwoordmanager):
# zonder deze sleutel kun je geen updates meer over de bestaande installatie heen zetten.
set -euo pipefail
KS="${1:?gebruik: $0 <keystore.p12> <signing-secrets.env> [eigenaar/repo]}"
ENVF="${2:?gebruik: $0 <keystore.p12> <signing-secrets.env> [eigenaar/repo]}"
REPO="${3:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
command -v gh >/dev/null || { echo "gh niet gevonden"; exit 1; }
[[ -f "$KS" && -f "$ENVF" ]] || { echo "bestand niet gevonden"; exit 1; }
# shellcheck disable=SC1090
set -a; . "$ENVF"; set +a
base64 < "$KS" | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$SIGNING_STORE_PASSWORD" | gh secret set SIGNING_STORE_PASSWORD --repo "$REPO"
printf '%s' "$SIGNING_KEY_ALIAS"      | gh secret set SIGNING_KEY_ALIAS --repo "$REPO"
printf '%s' "$SIGNING_KEY_PASSWORD"   | gh secret set SIGNING_KEY_PASSWORD --repo "$REPO"
echo "Secrets gezet voor $REPO:"
gh secret list --repo "$REPO"
