#!/bin/bash
# ModRadar 发布构建：assembleRelease → zipalign → apksigner
# 用法：./build.sh
# 可用环境变量覆盖：KEYSTORE / KS_PASS / KEY_ALIAS / VERSION
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
KEYSTORE="${KEYSTORE:-$HOME/Documents/Dsh/apk_work/release.keystore}"
KS_PASS="${KS_PASS:-dshpass123}"
KEY_ALIAS="${KEY_ALIAS:-dsh}"
VERSION="${VERSION:-0.5.0}"

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

if [ ! -f "$KEYSTORE" ]; then
  echo "找不到 keystore：$KEYSTORE" >&2
  exit 1
fi

echo "==> assembleRelease"
gradle :app:assembleRelease --console=plain

UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$UNSIGNED" ]; then
  echo "找不到未签名 APK：$UNSIGNED" >&2
  exit 1
fi

mkdir -p dist
OUT="dist/ModRadar-$VERSION.apk"

echo "==> zipalign"
"$BT/zipalign" -f -p 4 "$UNSIGNED" dist/aligned.apk

echo "==> apksigner sign"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT" dist/aligned.apk

rm -f dist/aligned.apk

echo "==> verify"
"$BT/apksigner" verify --print-certs "$OUT" | head -8
ls -lh "$OUT"
echo "BUILD OK -> $OUT"
