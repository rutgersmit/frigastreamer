#!/usr/bin/env bash
# Sets the four signing secrets in the GitHub repository (one-time).
#
#   ./scripts/set-signing-secrets.sh <keystore.p12> <signing-secrets.env> [owner/repo]
#
# signing-secrets.env contains SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS and SIGNING_KEY_PASSWORD.
# Also keep the keystore and the passwords in a safe place (password manager): without this
# key you can no longer install updates over the existing installation.
set -euo pipefail
KS="${1:?usage: $0 <keystore.p12> <signing-secrets.env> [owner/repo]}"
ENVF="${2:?usage: $0 <keystore.p12> <signing-secrets.env> [owner/repo]}"
REPO="${3:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
command -v gh >/dev/null || { echo "gh not found"; exit 1; }
[[ -f "$KS" && -f "$ENVF" ]] || { echo "file not found"; exit 1; }
# shellcheck disable=SC1090
set -a; . "$ENVF"; set +a
base64 < "$KS" | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$SIGNING_STORE_PASSWORD" | gh secret set SIGNING_STORE_PASSWORD --repo "$REPO"
printf '%s' "$SIGNING_KEY_ALIAS"      | gh secret set SIGNING_KEY_ALIAS --repo "$REPO"
printf '%s' "$SIGNING_KEY_PASSWORD"   | gh secret set SIGNING_KEY_PASSWORD --repo "$REPO"
echo "Secrets set for $REPO:"
gh secret list --repo "$REPO"
