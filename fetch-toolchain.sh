#!/bin/sh
# Downloads the native/Java platform pieces the on-device build needs and places them where Gradle packs them
# into the APK. Run by CI before assembleDebug; you can also run it on a Linux computer.
#
#   app/src/main/jniLibs/arm64-v8a/libaapt2.so   aapt2 for 64-bit ARM Android (static executable)
#   app/src/main/assets/toolchain/android.jar    platform jar the projects are compiled against
#   app/src/main/assets/toolchain/api.txt        its API level
#
# The aapt2 binary comes from the third-party project github.com/lzhiyong/android-sdk-tools (release 34.0.3),
# which builds the AOSP aapt2 for Android. It is a third-party binary: review the printed SHA-256 and the
# ELF details, and pin the hash below (AAPT2_SHA256) once you have checked it.
set -eu

API="${TOOLCHAIN_API:-34}"
RELEASE="${AAPT2_RELEASE:-34.0.3}"
AAPT2_SHA256="${AAPT2_SHA256:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBDIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSETDIR="$ROOT/app/src/main/assets/toolchain"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$LIBDIR" "$ASSETDIR"

echo "== aapt2 ($RELEASE, aarch64)"
URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/$RELEASE/android-sdk-tools-static-aarch64.zip"
curl -fsSL --retry 3 -o "$TMP/tools.zip" "$URL"
mkdir -p "$TMP/x"
unzip -q "$TMP/tools.zip" -d "$TMP/x"
AAPT2="$(find "$TMP/x" -type f -name aapt2 | head -n 1)"
if [ -z "$AAPT2" ]; then echo "aapt2 not found inside the downloaded archive" >&2; exit 1; fi
# Must be a 64-bit ARM ELF executable, otherwise it cannot run on a phone.
HDR="$(readelf -h "$AAPT2")"
echo "$HDR" | grep -q "Class:.*ELF64" || { echo "aapt2 is not a 64-bit ELF" >&2; exit 1; }
echo "$HDR" | grep -q "Machine:.*AArch64" || { echo "aapt2 is not built for AArch64" >&2; exit 1; }
if readelf -d "$AAPT2" 2>/dev/null | grep -q "NEEDED"; then
  echo "NOTE: aapt2 has shared-library dependencies:"; readelf -d "$AAPT2" | grep NEEDED
fi
SUM="$(sha256sum "$AAPT2" | cut -d' ' -f1)"
echo "aapt2 sha256: $SUM"
if [ -n "$AAPT2_SHA256" ] && [ "$AAPT2_SHA256" != "$SUM" ]; then
  echo "aapt2 checksum does not match the pinned AAPT2_SHA256" >&2; exit 1
fi
cp "$AAPT2" "$LIBDIR/libaapt2.so"
chmod 755 "$LIBDIR/libaapt2.so"

echo "== android.jar (API $API)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
JAR="$SDK/platforms/android-$API/android.jar"
if [ ! -f "$JAR" ]; then echo "Missing $JAR. Install it with: sdkmanager \"platforms;android-$API\"" >&2; exit 1; fi
cp "$JAR" "$ASSETDIR/android.jar"
echo "$API" > "$ASSETDIR/api.txt"
ls -l "$LIBDIR/libaapt2.so" "$ASSETDIR/android.jar"
echo "Toolchain files are in place."
