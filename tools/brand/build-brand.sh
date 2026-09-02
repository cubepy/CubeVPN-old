#!/usr/bin/env bash
#
# Builds one reseller's signed app from a brand file.
#
#   tools/brand/build-brand.sh tools/brand/brands/novavpn.properties
#
# Produces dist/<slug>/ containing the signed APKs to hand over.
#
# The signing key is generated on first build and reused forever after. Losing it means that
# brand can never ship an update again — its customers would have to uninstall and reinstall,
# losing everything. Back up tools/brand/keystores/ somewhere off this machine.
#
set -euo pipefail

BRAND_FILE="${1:-}"
if [ -z "$BRAND_FILE" ] || [ ! -f "$BRAND_FILE" ]; then
    echo "usage: $0 <brand.properties>    (see tools/brand/brand.example.properties)" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BRAND_FILE="$(cd "$(dirname "$BRAND_FILE")" && pwd)/$(basename "$BRAND_FILE")"
BRAND_DIR="$(dirname "$BRAND_FILE")"
KEYSTORE_DIR="$ROOT/tools/brand/keystores"
RES_DIR="$ROOT/app/src/main/res"

# Read a key from the brand file. Values may contain spaces and non-ASCII.
prop() {
    sed -n "s/^$1=//p" "$BRAND_FILE" | head -1 | sed -e 's/[[:space:]]*$//'
}

need() {
    local v; v="$(prop "$1")"
    if [ -z "$v" ]; then echo "brand file is missing $1" >&2; exit 2; fi
    printf '%s' "$v"
}

APP_NAME="$(need BRAND_APP_NAME)"
SLUG="$(need BRAND_SLUG)"
APP_ID="$(need BRAND_APPLICATION_ID)"
BOT="$(need BRAND_BOT)"
SUPPORT="$(prop BRAND_SUPPORT)"
CHANNEL="$(prop BRAND_CHANNEL)"
API_BASE_URL="$(need API_BASE_URL)"
TON="$(prop BRAND_TON_WALLET)"
ICON_BG="$(prop BRAND_ICON_BACKGROUND)"; ICON_BG="${ICON_BG:-#6D28D9}"
LOGO="$(prop BRAND_LOGO)"

case "$APP_ID" in
    *.*) ;;
    *) echo "BRAND_APPLICATION_ID must look like ir.company.app (got '$APP_ID')" >&2; exit 2 ;;
esac

echo "==> $APP_NAME  ($APP_ID)"

# --- signing key: generate once, reuse forever -------------------------------------------
mkdir -p "$KEYSTORE_DIR"
KEYSTORE="$KEYSTORE_DIR/$SLUG.jks"
PASSFILE="$KEYSTORE_DIR/$SLUG.pass"

if [ ! -f "$KEYSTORE" ]; then
    echo "==> generating a signing key (first build for this brand)"
    PASS="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32)"
    printf '%s\n' "$PASS" > "$PASSFILE"
    chmod 600 "$PASSFILE"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" -storepass "$PASS" -keypass "$PASS" \
        -alias "$SLUG" -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=$SLUG, OU=$SLUG, O=$SLUG, L=Tehran, ST=Tehran, C=IR" >/dev/null 2>&1
    echo "    $KEYSTORE  — BACK THIS UP; it can never be regenerated"
else
    echo "==> reusing the existing signing key"
fi
PASS="$(cat "$PASSFILE")"

# --- branded icons ------------------------------------------------------------------------
# These are written straight into app/src/main/res and reverted afterwards, so a build never
# leaves one brand's icon behind for the next. CI checks out fresh per build, so the revert
# is belt-and-braces there.
RESTORE_RES=0
if [ -n "$LOGO" ]; then
    LOGO_PATH="$BRAND_DIR/$LOGO"
    [ -f "$LOGO_PATH" ] || { echo "logo not found: $LOGO_PATH" >&2; exit 2; }
    RESTORE_RES=1
    python3 "$ROOT/tools/brand/make_icons.py" --logo "$LOGO_PATH" --background "$ICON_BG" --res "$RES_DIR"
else
    echo "==> no BRAND_LOGO set, keeping the default icon"
fi

cleanup() {
    if [ "$RESTORE_RES" = "1" ]; then
        git -C "$ROOT" checkout -- app/src/main/res 2>/dev/null || true
        git -C "$ROOT" clean -fdq app/src/main/res 2>/dev/null || true
    fi
}
trap cleanup EXIT

# --- build ---------------------------------------------------------------------------------
# Non-ASCII cannot survive a plain -P (the Gradle client decodes argv with the platform
# charset), so the app name travels base64-encoded. See brandProp() in app/build.gradle.kts.
NAME_B64="$(printf '%s' "$APP_NAME" | base64 | tr -d '\n')"
VERSION="$(prop RELEASE_VERSION)"; VERSION="${VERSION:-$(git -C "$ROOT" describe --tags --abbrev=0 2>/dev/null | sed 's/^v//')}"
VERSION="${VERSION:-1.0.0}"

echo "==> building $SLUG v$VERSION"
cd "$ROOT"
KEYSTORE_FILE="$KEYSTORE" \
KEYSTORE_PASSWORD="$PASS" \
KEY_ALIAS="$SLUG" \
KEY_PASSWORD="$PASS" \
./gradlew --quiet assembleRelease \
    -PreleaseVersionName="$VERSION" \
    -PBRAND_APP_NAME_B64="$NAME_B64" \
    -PBRAND_SLUG="$SLUG" \
    -PBRAND_APPLICATION_ID="$APP_ID" \
    -PBRAND_BOT="$BOT" \
    -PBRAND_SUPPORT="$SUPPORT" \
    -PBRAND_CHANNEL="$CHANNEL" \
    -PBRAND_TON_WALLET="$TON" \
    -PAPI_BASE_URL="$API_BASE_URL"

# --- collect + verify ----------------------------------------------------------------------
OUT="$ROOT/dist/$SLUG"
rm -rf "$OUT"; mkdir -p "$OUT"
cp app/build/outputs/apk/release/*.apk "$OUT/"

APKSIGNER="$(find "${ANDROID_HOME:-$HOME/android-sdk}/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -1)"
if [ -n "$APKSIGNER" ]; then
    echo "==> signature"
    for apk in "$OUT"/*.apk; do
        "$APKSIGNER" verify --print-certs "$apk" 2>/dev/null | grep -E "certificate SHA-256" | head -1 \
            | sed "s|^.*digest: |    $(basename "$apk")\n      |"
    done
fi

echo
echo "==> done: $OUT"
ls -1sh "$OUT" | tail -n +2
