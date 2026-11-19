#!/usr/bin/env bash
# Run ONCE on your own trusted computer, never in GitHub Actions.
# Requires: gh, keytool (JDK 17+), openssl, base64. Nothing secret is committed.
set -euo pipefail

REPO="DmitryLedentsov/dimka-ftp-manager"
KEY_DIR="$HOME/.config/dimka-ftp-manager/signing"
KEY_FILE="$KEY_DIR/dimka-stable-signing.p12"
PASS_FILE="$KEY_DIR/signing-password.txt"
ALIAS="dimka-stable"

for cmd in gh keytool openssl base64; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing $cmd. Install it before running the signing setup." >&2
    exit 1
  fi
done
gh auth status || { echo "Run: gh auth login" >&2; exit 1; }

umask 077
mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"
if [[ -f "$KEY_FILE" && -f "$PASS_FILE" ]]; then
  echo "Reusing existing signing key in $KEY_DIR"
elif [[ -e "$KEY_FILE" || -e "$PASS_FILE" ]]; then
  echo "Only one signing file exists. Restore both from backup before continuing." >&2
  exit 1
else
  PASSWORD="$(openssl rand -hex 24)"
  printf '%s' "$PASSWORD" > "$PASS_FILE"
  keytool -genkeypair \
    -keystore "$KEY_FILE" -storetype PKCS12 \
    -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -alias "$ALIAS" -keyalg RSA -keysize 3072 \
    -validity 36500 -dname "CN=DimkaFtpManager, OU=Android"
fi
PASSWORD="$(cat "$PASS_FILE")"
chmod 600 "$KEY_FILE" "$PASS_FILE"

base64 < "$KEY_FILE" | tr -d '\n' | gh secret set DIMKA_KEYSTORE_B64 --repo "$REPO"
printf '%s' "$PASSWORD" | gh secret set DIMKA_STORE_PASSWORD --repo "$REPO"
printf '%s' "$PASSWORD" | gh secret set DIMKA_KEY_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set DIMKA_KEY_ALIAS --repo "$REPO"

echo "All four GitHub Actions secrets are configured."
echo "Keep a PRIVATE backup of BOTH files in: $KEY_DIR"
echo "IMPORTANT: before switching to the stable signed APK, export your current app config to JSON."
echo "Then run the Android APK workflow from GitHub Actions to publish the stable-signed release."
