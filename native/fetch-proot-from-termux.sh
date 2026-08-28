#!/usr/bin/env bash
# fetch-proot-from-termux.sh — Termux 公式リポジトリの proot (Android 向けパッチ済み) を取り出し、
# Android アプリに同梱できる形に並べる。
#
#   android/app/src/main/jniLibs/arm64-v8a/libproot.so   ← proot 本体 (改名。jniLibs は exec 可能)
#   android/app/src/main/jniLibs/arm64-v8a/libloader.so  ← proot の ELF ローダ (PROOT_LOADER で指定)
#   android/app/src/main/assets/proot/libtalloc.so.2     ← 初回起動時に files/lib へコピーし LD_LIBRARY_PATH で読む
#   android/app/src/main/assets/proot/libandroid-shmem.so
#   android/app/src/main/assets/proot/VERSION
#
# 使い方: native/fetch-proot-from-termux.sh android/app
# 前提:   curl, ar (binutils), tar, xz。ホストは何でもよい (バイナリを展開するだけ)。
set -euo pipefail
APP="${1:-android/app}"
ARCH="${FB_TERMUX_ARCH:-aarch64}"
REPO="${FB_TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo ">> fetching package index ($ARCH)"
curl -fsSL --retry 3 "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$WORK/Packages"

field() { # field <package> <Field>
  awk -v pkg="$1" -v fld="$2" 'BEGIN{RS="";FS="\n"} $1=="Package: "pkg {for(i=1;i<=NF;i++) if($i ~ "^"fld": ") {sub("^"fld": ","",$i); print $i}}' "$WORK/Packages"
}

for p in proot libtalloc libandroid-shmem; do
  f="$(field "$p" Filename)"; v="$(field "$p" Version)"
  [ -n "$f" ] || { echo "package $p not found in index" >&2; exit 1; }
  echo ">> $p $v"
  curl -fsSL --retry 3 "$REPO/$f" -o "$WORK/$p.deb"
  ( cd "$WORK" && ar x "$p.deb" && tar -xf data.tar.xz && rm -f data.tar.xz control.tar.xz debian-binary )
  echo "$p $v" >> "$WORK/VERSION"
done

T="$WORK/data/data/com.termux/files/usr"
JNI="$APP/src/main/jniLibs/arm64-v8a"
AST="$APP/src/main/assets/proot"
mkdir -p "$JNI" "$AST"
install -m 755 "$T/bin/proot"            "$JNI/libproot.so"
install -m 755 "$T/libexec/proot/loader" "$JNI/libloader.so"
install -m 644 "$T/lib/libtalloc.so.2".* "$AST/libtalloc.so.2"
install -m 644 "$T/lib/libandroid-shmem.so" "$AST/libandroid-shmem.so"
cp "$WORK/VERSION" "$AST/VERSION"

echo ">> sanity"
if command -v readelf >/dev/null; then
  readelf -d "$JNI/libproot.so" | grep -E 'NEEDED' || true
  readelf -l "$JNI/libproot.so" | awk '/LOAD/{getline; print "  LOAD align", $NF; exit}'
fi
grep -q PROOT_LOADER "$JNI/libproot.so" && echo "  PROOT_LOADER: supported"
cat "$AST/VERSION"
echo ">> done"
