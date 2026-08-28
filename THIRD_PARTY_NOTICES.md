# Third-party notices

FULL-BROWSER は以下のオープンソースプロジェクトを 1 つの APK に統合しています。
正式な著作権表示・ライセンス本文は各ソースツリー内のファイルが正となります。

## Termux:X11 (X サーバ / ビューア)

- プロジェクト: `termux/termux-x11`
- 同梱場所: `external/termux-x11`
- ベースコミット: `50ac80fb2d4a475e323e752d17fcc0483c3c99fc`
  （upstream `termux/termux-x11` に LDFA プロジェクトのパッチ
  「fix(app): force Prefs onto our own package's SharedPreferences」を積んだもの。
  このコミットは upstream には存在しないため、submodule ではなくツリーを同梱している）
- ライセンス: GNU General Public License version 3
- ライセンスファイル: `external/termux-x11/LICENSE`

upstream のビューア Activity・AIDL・ネイティブ `libXlorie` X サーバを
`android/embedded-x11` モジュール（LDFA 由来の再現可能な生成オーバーレイ）でビルドし
APK に組み込んでいます。Xorg は専用の Android フォアグラウンドサービスプロセス
(:x11) で動かし、X 接続は upstream の Binder インタフェースでビューアに渡します。

`android/embedded-x11` のオーバーレイ（scripts/・src/）は
Linux Desktop for Android (LDFA) プロジェクトからの移植で、同じく GPLv3 です。

## proot / libtalloc / libandroid-shmem (コンテナ実行)

- 入手元: Termux 公式パッケージリポジトリ (https://packages.termux.dev)
- 取得方法: `native/fetch-proot-from-termux.sh`（ビルド時に取得、リポジトリには含めない）
- ライセンス: proot は GPLv2、libtalloc は LGPLv3、libandroid-shmem は MIT
- バージョンは `android/app/src/main/assets/proot/VERSION` に記録されます

## Debian rootfs

アプリが実行時にダウンロードする rootfs イメージは Debian 13 (trixie) の
公式パッケージから `rootfs/build-rootfs.sh` (mmdebstrap) で構築されます。
各パッケージのライセンスはイメージ内の `/usr/share/doc/*/copyright` を参照してください。
Google Chrome は再配布せず、利用者の端末上で Google から直接取得します。
