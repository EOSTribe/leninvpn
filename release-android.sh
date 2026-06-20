#!/usr/bin/env bash
# release-android.sh — Build a signed Android release (AAB + APK) ready for Play Store.
#
# Usage:
#   ./release-android.sh [--aab-only | --apk-only] [--abi <abi>]
#
# Required env vars (set before running, or export in your shell):
#   ANDROID_KEYSTORE_KEY_ALIAS   — key alias inside the keystore
#   ANDROID_KEYSTORE_STORE_PASS  — keystore password
#   ANDROID_KEYSTORE_KEY_PASS    — key password (often the same as store password)
#
# Override its path with ANDROID_KEYSTORE_PATH if needed.
#
# Outputs (after successful build):
#   deploy/build/LeninVPN-release.aab          — upload this to Play Store
#   deploy/build/LeninVPN-<abi>-release.apk    — use for local testing

set -euo pipefail

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()    { echo -e "${GREEN}==>${NC} $*"; }
warn()    { echo -e "${YELLOW}WARN:${NC} $*"; }
fatal()   { echo -e "${RED}ERROR:${NC} $*" >&2; exit 1; }

# ── Defaults ──────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_AAB=1
BUILD_APK=1
ABI="arm64-v8a"

# ── Parse args ────────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --aab-only) BUILD_APK=0; shift;;
    --apk-only) BUILD_AAB=0; shift;;
    --abi) ABI="$2"; shift 2;;
    -h|--help)
      sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
      exit 0;;
    *) fatal "Unknown option: $1";;
  esac
done

# ── Resolve keystore ──────────────────────────────────────────────────────────
ANDROID_KEYSTORE_PATH="/Users/eluzgin/Development/amnezia-client/client/android/android-release-key.keystore"
[[ -f "$ANDROID_KEYSTORE_PATH" ]] || fatal "Keystore not found: $ANDROID_KEYSTORE_PATH"

# ── Check required secrets ────────────────────────────────────────────────────
[[ -n "${ANDROID_KEYSTORE_KEY_ALIAS:-}"  ]] || fatal "ANDROID_KEYSTORE_KEY_ALIAS is not set"
[[ -n "${ANDROID_KEYSTORE_STORE_PASS:-}" ]] || fatal "ANDROID_KEYSTORE_STORE_PASS is not set"
[[ -n "${ANDROID_KEYSTORE_KEY_PASS:-}"  ]] || fatal "ANDROID_KEYSTORE_KEY_PASS is not set"

# ── Load Qt / SDK paths ───────────────────────────────────────────────────────
cd "$SCRIPT_DIR"
# shellcheck source=set_env.sh
. ./set_env.sh

info "Android release build"
echo "  Keystore : $ANDROID_KEYSTORE_PATH"
echo "  Alias    : $ANDROID_KEYSTORE_KEY_ALIAS"
echo "  AAB      : $([[ $BUILD_AAB -eq 1 ]] && echo yes || echo no)"
echo "  APK      : $([[ $BUILD_APK -eq 1 ]] && echo yes || echo no) (ABI: $ABI)"
echo ""

# ── Build ─────────────────────────────────────────────────────────────────────
BUILD_ARGS=(--move)

if [[ $BUILD_AAB -eq 1 ]]; then
  BUILD_ARGS+=(--aab)
fi
if [[ $BUILD_APK -eq 1 ]]; then
  BUILD_ARGS+=(--apk "$ABI")
fi

info "Running build_android.sh (release)…"
"$BASH" deploy/build_android.sh "${BUILD_ARGS[@]}"

# ── Sign ──────────────────────────────────────────────────────────────────────
# Qt's androiddeployqt does not pass --sign through to Gradle automatically
# when invoked without that flag, so we sign the output artifacts here.

APKSIGNER="$ANDROID_SDK_ROOT/build-tools/$(ls "$ANDROID_SDK_ROOT/build-tools" | sort -V | tail -1)/apksigner"
[[ -x "$APKSIGNER" ]] || fatal "apksigner not found under $ANDROID_SDK_ROOT/build-tools"

if [[ $BUILD_AAB -eq 1 ]]; then
  AAB="$SCRIPT_DIR/deploy/build/LeninVPN-release.aab"
  [[ -f "$AAB" ]] || fatal "AAB not found at $AAB — build may have failed"
  info "Signing AAB with jarsigner…"
  jarsigner \
    -verbose \
    -sigalg SHA256withRSA \
    -digestalg SHA-256 \
    -keystore "$ANDROID_KEYSTORE_PATH" \
    -storepass "$ANDROID_KEYSTORE_STORE_PASS" \
    -keypass  "$ANDROID_KEYSTORE_KEY_PASS" \
    "$AAB" \
    "$ANDROID_KEYSTORE_KEY_ALIAS"
  info "Verifying AAB signature…"
  jarsigner -verify -verbose -certs "$AAB" | grep -E "^(s|jar verified)" || true
fi

if [[ $BUILD_APK -eq 1 ]]; then
  APK="$SCRIPT_DIR/deploy/build/LeninVPN-$ABI-release.apk"
  [[ -f "$APK" ]] || fatal "APK not found at $APK — build may have failed"

  SIGNED_APK="${APK%.apk}-signed.apk"
  info "Signing APK with apksigner…"
  "$APKSIGNER" sign \
    --ks "$ANDROID_KEYSTORE_PATH" \
    --ks-key-alias "$ANDROID_KEYSTORE_KEY_ALIAS" \
    --ks-pass "pass:$ANDROID_KEYSTORE_STORE_PASS" \
    --key-pass "pass:$ANDROID_KEYSTORE_KEY_PASS" \
    --out "$SIGNED_APK" \
    "$APK"

  info "Verifying APK signature…"
  "$APKSIGNER" verify --verbose "$SIGNED_APK" 2>&1 | head -5

  # Replace unsigned APK with signed one
  mv "$SIGNED_APK" "$APK"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}✅ Release build complete!${NC}"
echo ""
if [[ $BUILD_AAB -eq 1 ]]; then
  AAB="$SCRIPT_DIR/deploy/build/LeninVPN-release.aab"
  echo "📦 AAB (Play Store upload):"
  ls -lh "$AAB"
fi
if [[ $BUILD_APK -eq 1 ]]; then
  APK="$SCRIPT_DIR/deploy/build/LeninVPN-$ABI-release.apk"
  echo "📱 APK (device testing):"
  ls -lh "$APK"
  echo ""
  echo "  Install: adb install -r \"$APK\""
fi
echo ""
echo "Next: upload the AAB to Google Play Console → Production / Internal testing."
