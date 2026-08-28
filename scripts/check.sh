#!/usr/bin/env bash
# ローカル/CI 用の静的チェック: shellcheck, bash -n, XML/JSON 妥当性, 実行ビット
set -euo pipefail
cd "$(dirname "$0")/.."
status=0
shell_files=(rootfs/build-rootfs.sh rootfs/browsers/*.sh rootfs/overlay/usr/local/bin/fb-* rootfs/overlay/etc/fullbrowser/env rootfs/overlay/etc/fullbrowser/chromium-flags.conf native/fetch-proot-from-termux.sh scripts/check.sh)
for f in "${shell_files[@]}"; do
  bash -n "$f" || { echo "syntax error: $f"; status=1; }
done
if command -v shellcheck >/dev/null; then
  shellcheck -x -S warning -s bash rootfs/build-rootfs.sh rootfs/overlay/usr/local/bin/fb-* native/fetch-proot-from-termux.sh scripts/check.sh || status=1
else
  echo "shellcheck not installed (skipped)"
fi
if command -v xmllint >/dev/null; then
  xmllint --noout rootfs/overlay/etc/fullbrowser/openbox-rc.xml rootfs/overlay/etc/fonts/conf.d/99-android-fonts.conf \
    android/app/src/main/AndroidManifest.xml android/app/src/main/res/xml/shortcuts.xml android/app/src/main/res/values/strings.xml android/app/src/main/res/values-en/strings.xml || status=1
fi
python3 -m py_compile rootfs/make-manifest.py || status=1
for f in rootfs/overlay/usr/local/bin/fb-* rootfs/build-rootfs.sh native/fetch-proot-from-termux.sh; do
  [ -x "$f" ] || { echo "not executable: $f"; status=1; }
done
[ "$status" -eq 0 ] && echo "check: OK"
exit "$status"
