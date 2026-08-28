# HANDOVER — 実装担当への引き継ぎ

このリポジトリの v0.1.0 は「設計 + 骨格 + 検証済みの部品」です。以下の順で仕上げてください。
各 Phase の終わりに実機（Pixel 10a）で確認し、結果を `docs/BENCHMARKS.md` に残します。

## 既に出来ていること

- rootfs ビルド一式（`rootfs/`）と CI（`.github/workflows/build-rootfs.yml`）。shellcheck 済み
- ゲスト側スクリプト `fb-session`、openbox 設定、Firefox user.js、Chromium フラグ、/proc スタブ、fontconfig
- proot の取得スクリプト `native/fetch-proot-from-termux.sh`（Termux 公式 .deb → jniLibs/assets）
- Android: `ProotCommand`（argv/env 生成。単体テスト付き）、`RootfsManager`（manifest → DL → SHA-256 → tar.xz 展開）、
  `SessionService`、`BrowserCatalog`、`Prefs`、`SetupActivity`/`MainActivity` の骨格、Manifest、App Shortcuts、文言 (ja/en)

## Phase 0 — 前提確認（半日）

1. `native/fetch-proot-from-termux.sh android/app` を実行し、`android/app/src/main/jniLibs/arm64-v8a/libproot.so` と `libloader.so`、
   `android/app/src/main/assets/proot/` が出来ること
2. `git submodule add https://github.com/termux/termux-x11 external/termux-x11`。LDFA で行った lorie 組み込み（LorieView + CmdEntryPoint）を
   `android/app` から参照できる形にする。方針は 2 択:
   - (a) `external/termux-x11/lorie` を **library module** 化して `:app` から依存（applicationId は :app 側）
   - (b) LDFA と同様に必要ソースを `android/app` 配下へコピー（ライセンス表示を保持）
   どちらでも `com.termux.x11.CmdEntryPoint` と `LorieView` が使えればよい
3. `android/` で `./gradlew :app:testDebugUnitTest` が通る（ProotCommandTest）

## Phase 1 — rootfs を端末で動かす（1 日）

1. `rootfs/build-rootfs.sh firefox out/` を ARM64 Linux（または GitHub Actions）で実行。`fullbrowser-rootfs-firefox-arm64-*.tar.xz` を得る
2. **検証用に Termux を使う**（製品は Termux 非依存だが、検証には便利）:
   ```sh
   pkg install proot x11-repo && pkg install termux-x11-nightly
   mkdir -p ~/fb && tar -xJf fullbrowser-rootfs-firefox-arm64-*.tar.xz -C ~/fb
   termux-x11 :0 &   # Termux:X11 アプリを開く
   proot --kill-on-exit --link2symlink --sysvipc -L -0 -r ~/fb -w /root \
     -b /dev -b /proc -b /sys -b /system/fonts -b $PREFIX/tmp:/tmp \
     /usr/bin/env -i HOME=/root PATH=/usr/local/bin:/usr/bin:/bin DISPLAY=:0 LANG=C.UTF-8 TZ=Asia/Tokyo \
     /usr/local/bin/fb-session firefox
   ```
3. 確認: 全画面になる／回転で追従する／Gboard で日本語が打てる／`fb-session` 終了で戻る／`/proc/stat` を読むツールが落ちない
4. Chromium, chromebase（`fb-install-chrome` を実行してから）も同様に

## Phase 2 — アプリ結線（2–3 日）

1. `XServerController.kt` の TODO を lorie の実装で埋める（`app_process` 起動、`TMPDIR`/`XKB_CONFIG_ROOT`/`CLASSPATH`、LorieView の接続）
2. `MainActivity` に LorieView を置き、immersive + cutout 設定。lorie の preference を §ARCHITECTURE 4 の値で初期化
3. `SessionService` の実装を `ProotCommand` + `XServerController` で完成。通知の「終了」アクション
4. `RootfsManager` の展開を実機で 3 回連続成功させる（途中キャンセル → 再開も）
5. DNS: `ConnectivityManager.getLinkProperties().dnsServers` を resolv.conf へ。Private DNS 有効時は 1.1.1.1 をフォールバック

## Phase 3 — 導線と設定（1–2 日）

1. SetupActivity: 選択 → 容量チェック → DL 進捗 → 完了 → 「起動」。Chrome は規約リンク + 同意 → `fb-install-chrome`
2. 設定画面: 既定ブラウザ / 倍率 / タッチ方式 / 省メモリ / 常駐 (keepWarm) / ブラウザ追加・更新・削除 / ダウンロード書き出し (SAF)
3. App Shortcuts の動的更新（インストール済みブラウザのみ表示）
4. 初回チュートリアル（戻るキーでキーボード、通知から終了）

## Phase 4 — Play 提出（1 日 + 審査）

`docs/PLAY-COMPLIANCE.md` のチェックリストを全部埋める。内部テストトラック → クローズドテスト（Play の新規個人アカウント要件: テスター 12 人 × 14 日）→ 製品版。

## 未検証・要注意

| # | 項目 | 影響 | 確認方法 |
|---|---|---|---|
| 1 | targetSdk 35+ で jniLibs の `libproot.so` を `ProcessBuilder` で直接実行できるか（`extractNativeLibs`）。不可なら `/system/bin/linker64 <path>` 経由 | 起動不可 | Phase 2 冒頭で確認 |
| 2 | lorie の `CmdEntryPoint` を自アプリの APK から `app_process` で起動する際の `CLASSPATH` と SELinux（LDFA では動作実績あり） | 画面が出ない | LDFA のコードを参照 |
| 3 | Direct touch (touchMode=3) で Firefox/Chromium のタッチスクロールが効くか。効かなければ既定を 2 に | 操作性 | Phase 1 |
| 4 | Debian trixie の Chromium が `--no-sandbox` 単独で起動するか（`chromium-sandbox` 不要のはず） | Chromium 起動不可 | Phase 1 |
| 5 | Firefox ESR の sandbox 無効化 env が proot 内で十分か（`MOZ_DISABLE_*_SANDBOX`） | Firefox 起動不可 | Phase 1 |
| 6 | `apt-get satisfy` で Chrome の Depends を trixie で解決できるか（t64 パッケージ名） | chromebase ビルド失敗 | CI |
| 7 | Android 16/17 の Phantom Process Killer（32 プロセス制限）で Chromium のプロセスが殺されないか | 突然終了 | 長時間テスト。対策: renderer 数制限 |
| 8 | `/system/fonts` のバインドと fontconfig キャッシュ（初回 fc-cache に数秒） | 初回起動が遅い | build 時に fc-cache は不可（別環境）→ 初回起動時に生成 |

## コーディング規約

- Kotlin: 公式スタイル。副作用のないロジック（argv 生成、manifest 解析）はクラスに切り出して単体テスト
- シェル: `#!/bin/bash` + `set -u`（ゲスト）/ `set -euo pipefail`（ビルド）。`scripts/check.sh` が shellcheck を実行
- 文言は `strings.xml`（ja が既定、en を併記）。ログは `Log.i("FB", ...)` に統一
