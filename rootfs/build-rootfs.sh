#!/usr/bin/env bash
# FULL-BROWSER rootfs builder
#
# 使い方:  rootfs/build-rootfs.sh <base|firefox|chromium|chromebase> [出力ディレクトリ]
# 前提:    Debian/Ubuntu (arm64) 上で mmdebstrap, xz-utils, dpkg-dev。root か sudo が必要 (--mode=root)。
#          x86_64 ホストで作る場合は qemu-user-static + binfmt を入れれば同じコマンドで動く。
# 出力:    fullbrowser-rootfs-<variant>-arm64-<YYYYMMDD>.tar.xz と .sha256
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VARIANT="${1:-}"
OUT="${2:-out}"
SUITE="${FB_SUITE:-trixie}"
ARCH="${FB_ARCH:-arm64}"
MIRROR="${FB_MIRROR:-http://deb.debian.org/debian}"
BUILD_DATE="${FB_BUILD_DATE:-$(date -u +%Y%m%d)}"

usage() { echo "usage: $0 <base|firefox|chromium|chromebase> [outdir]" >&2; exit 2; }
[ -n "$VARIANT" ] || usage
[ -f "$HERE/browsers/$VARIANT.sh" ] || { echo "unknown variant: $VARIANT" >&2; usage; }

for tool in mmdebstrap xz dpkg-deb python3; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
if [ "$(id -u)" -ne 0 ]; then
  exec sudo --preserve-env=FB_SUITE,FB_ARCH,FB_MIRROR,FB_BUILD_DATE,FB_CHROME_DEB_URL "$0" "$@"
fi

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 共通パッケージ (最小): WM, フォント基盤, XKB データ (X サーバの xkbcomp 用), xrdb/xsetroot, 証明書, TZ, ps
BASE_PKGS="openbox fontconfig fonts-dejavu-core xkb-data x11-xserver-utils ca-certificates tzdata procps pulseaudio"

# バリアント定義を読む (FB_PKGS / FB_BROWSER_ID / fb_prepare / FB_HOOKS)
FB_PKGS=""; FB_BROWSER_ID=""; FB_HOOKS=()
# shellcheck source=browsers/firefox.sh
. "$HERE/browsers/$VARIANT.sh"
if declare -F fb_prepare >/dev/null; then fb_prepare "$WORK"; fi

PKGS="$BASE_PKGS $FB_PKGS"
TAR="$WORK/rootfs.tar"
NAME="fullbrowser-rootfs-${VARIANT}-${ARCH}-${BUILD_DATE}"

echo ">> building $NAME (suite=$SUITE, mirror=$MIRROR)"
echo ">> packages: $PKGS"

mmdebstrap \
  --mode=root \
  --variant=minbase \
  --architectures="$ARCH" \
  --components=main \
  --include="$PKGS" \
  --aptopt='Acquire::Languages "none"' \
  --aptopt='APT::Install-Recommends "false"' \
  --aptopt='APT::Install-Suggests "false"' \
  --dpkgopt="$HERE/overlay/etc/dpkg/dpkg.cfg.d/01-fullbrowser" \
  --customize-hook="sync-in $HERE/overlay /" \
  --customize-hook='mkdir -p "$1/system/fonts" "$1/tmp/.shm" "$1/root/.fullbrowser" "$1/root/Downloads" "$1/root/.config/fullbrowser"' \
  --customize-hook='chmod 755 "$1"/usr/local/bin/fb-*' \
  --customize-hook='mkdir -p "$1/usr/local/lib/fb-im"' \
  --customize-hook="copy-in $HERE/im-fb/im-fb-arm64.so /usr/local/lib/fb-im/" \
  --customize-hook='mv "$1/usr/local/lib/fb-im/im-fb-arm64.so" "$1/usr/local/lib/fb-im/im-fb.so"' \
  --customize-hook='chroot "$1" fc-cache -f >/dev/null 2>&1 || true' \
  ${FB_HOOKS[@]+"${FB_HOOKS[@]}"} \
  --customize-hook="printf 'variant=%s\nbrowser=%s\nsuite=%s\nbuild=%s\n' '$VARIANT' '$FB_BROWSER_ID' '$SUITE' '$BUILD_DATE' > \"\$1/etc/fullbrowser/image-info\"" \
  --customize-hook='rm -rf "$1"/var/lib/apt/lists/* "$1"/var/cache/apt/archives/*.deb "$1"/var/cache/apt/*.bin "$1"/var/log/apt/* "$1"/var/log/dpkg.log' \
  "$SUITE" "$TAR" "$MIRROR"

EXTRACTED_BYTES="$(tar -tvf "$TAR" | awk '{s+=$3} END{print s+0}')"
echo ">> extracted size: $EXTRACTED_BYTES bytes"

echo ">> compressing (xz -9 -T0)"
xz -9 -T0 -c "$TAR" > "$OUT/$NAME.tar.xz"
( cd "$OUT" && sha256sum "$NAME.tar.xz" > "$NAME.tar.xz.sha256" )
printf '%s\n' "$EXTRACTED_BYTES" > "$OUT/$NAME.extracted-bytes"
chmod 644 "$OUT/$NAME".*
ls -la "$OUT/$NAME".*
echo ">> done: $OUT/$NAME.tar.xz"
