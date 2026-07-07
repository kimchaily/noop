#!/usr/bin/env bash
# Generate a release signing keystore for the Choop Android app — ONE TIME.
#
# The original NoopApp signing key is gone, so this fork signs with its own key. Whatever key you
# adopt here is the key EVERY future release must use: Android only allows an in-place update when
# the new APK carries the same signature. Generate this once, back it up somewhere safe (a password
# manager / encrypted storage), and never commit it — `*.jks` and `keystore.properties` are
# git-ignored on purpose.
#
# Usage:
#   ./android/tools/make-keystore.sh                 # interactive: prompts for a password
#   KS_PASSWORD='…' ./android/tools/make-keystore.sh # non-interactive
#
# It writes ./choop-release.jks and prints the four values to paste into GitHub Actions secrets
# (Settings → Secrets and variables → Actions), which the "Android Release APK" workflow reads to
# produce a real-key-signed NOOP-full.apk. With no secrets set the workflow falls back to the debug
# key, so this step is what upgrades CI output to your own signature.
set -euo pipefail

ALIAS="${KS_ALIAS:-choop}"
OUT="${KS_OUT:-choop-release.jks}"
DNAME="${KS_DNAME:-CN=Choop, OU=Personal fork, O=kimchaily}"
VALIDITY_DAYS="${KS_VALIDITY:-10000}"   # ~27 years; must outlive every update you plan to ship

if [ -e "$OUT" ]; then
  echo "refusing to overwrite existing $OUT — move it aside first" >&2
  exit 1
fi

if [ -z "${KS_PASSWORD:-}" ]; then
  read -r -s -p "New keystore password (min 6 chars): " KS_PASSWORD; echo
  read -r -s -p "Repeat password: " KS_PASSWORD2; echo
  [ "$KS_PASSWORD" = "$KS_PASSWORD2" ] || { echo "passwords did not match" >&2; exit 1; }
fi
[ "${#KS_PASSWORD}" -ge 6 ] || { echo "password must be at least 6 characters" >&2; exit 1; }

keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$KS_PASSWORD" \
  -keypass "$KS_PASSWORD" \
  -dname "$DNAME"

echo
echo "Created $OUT (alias: $ALIAS)."
echo
echo "── Local release builds ─────────────────────────────────────────────"
echo "Write android/keystore.properties (git-ignored) so ./gradlew assembleFullRelease signs with it:"
cat <<EOF

  storeFile=$OUT
  storePassword=$KS_PASSWORD
  keyAlias=$ALIAS
  keyPassword=$KS_PASSWORD
EOF
echo "── GitHub Actions secrets (for the CI pipeline) ─────────────────────"
echo "Add these under Settings → Secrets and variables → Actions:"
echo "  ANDROID_KEYSTORE_BASE64   = (the base64 below, one line)"
echo "  ANDROID_KEYSTORE_PASSWORD = $KS_PASSWORD"
echo "  ANDROID_KEY_ALIAS         = $ALIAS"
echo "  ANDROID_KEY_PASSWORD      = $KS_PASSWORD"
echo
echo "ANDROID_KEYSTORE_BASE64:"
base64 -w0 "$OUT" 2>/dev/null || base64 "$OUT" | tr -d '\n'
echo
echo
echo "Keep $OUT and the password safe. Lose them and you can never ship an in-place update again."
