# native — proot と X サーバ

## proot（既定: Termux 公式パッケージから抽出）

`fetch-proot-from-termux.sh` が Termux の `proot` / `libtalloc` / `libandroid-shmem` (aarch64) を取り出して
`android/app` に配置します。Termux の proot は Android 向けの修正（`--link2symlink`, `--sysvipc`, `-L`, `--kill-on-exit`,
seccomp まわりの回避）が入っており、`PROOT_LOADER` / `PROOT_TMP_DIR` / `PROOT_L2S_DIR` 環境変数に対応しています。
2026-08-28 時点の 5.1.107.92 は 16 KB ページアラインです。

実行時の要点:

- `libproot.so` と `libloader.so` は `jniLibs` に置く → `nativeLibraryDir` から直接 `exec` できる（targetSdk 29+ の制限を回避）
- `libtalloc.so.2` / `libandroid-shmem.so` は `.so.2` 形式のため `jniLibs` に置けない → assets から `files/lib` へコピーし、
  `LD_LIBRARY_PATH=files/lib` で読み込む（`mmap` は許可されているので動く）
- proot の RUNPATH は `/data/data/com.termux/files/usr/lib` のまま。存在しないので無害

## proot（代替: 静的ビルド）

依存を無くしたい場合は termux/proot を NDK で静的にビルドします（talloc を `--disable-python` で静的化）。
`-Wl,-z,max-page-size=16384` を忘れないこと。Play 提出前に `readelf -l` で LOAD アラインを確認。

## X サーバ（Termux:X11 lorie）

`git submodule add https://github.com/termux/termux-x11 external/termux-x11` で取り込みます。
必要なのは `lorie` モジュール（`libXlorie.so`, `LorieView`, `CmdEntryPoint`, `LoriePreferences`）。
LDFA で行った組み込み方法をそのまま使ってください。ビルド時の NDK フラグに `-Wl,-z,max-page-size=16384` を追加します。
