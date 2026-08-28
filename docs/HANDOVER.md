# HANDOVER — 実装担当への引き継ぎ

このリポジトリの v0.1.0 は「設計 + 骨格 + 検証済みの部品」です。以下の順で仕上げてください。
各 Phase の終わりに実機（Pixel 10a）で確認し、結果を `docs/BENCHMARKS.md` に残します。

> **2026-08-28 更新**: Phase 0・Phase 1・Phase 2（4 の耐性テストを除く）が完了。
> 実機 (Pixel) で **セットアップ → Xorg → proot → Firefox ESR 全画面表示までの end-to-end を確認済み**
> （docs/BENCHMARKS.md の動作確認ログ参照）。rootfs 4 バリアントは GitHub Release `rootfs-latest` に公開済み
> （ローカル arm64 エミュレーションでビルド。CI の ARM ランナーはプライベートリポジトリの課金制限で未使用）。
> **注意**: リポジトリがプライベートの間、端末は Release を匿名ダウンロードできない。
> 開発時は `scripts/dev-serve.sh` を実行する（ローカル HTTP + adb reverse + override 設定を一括で行う）。
> **adb reverse は USB 再接続のたびに消える**ので、「セットアップに失敗しました」が出たら再実行すること。
> 残りは Phase 2-4（展開の耐性テスト）→ Phase 3（導線と設定）→ Phase 4（Play 提出、リポジトリ公開化の判断込み）。

## 既に出来ていること

- rootfs ビルド一式（`rootfs/`）と CI（`.github/workflows/build-rootfs.yml`）。shellcheck 済み
- ゲスト側スクリプト `fb-session`、openbox 設定、Firefox user.js、Chromium フラグ、/proc スタブ、fontconfig
- proot の取得スクリプト `native/fetch-proot-from-termux.sh`（Termux 公式 .deb → jniLibs/assets）
- Android: `ProotCommand`（argv/env 生成。単体テスト付き）、`RootfsManager`（manifest → DL → SHA-256 → tar.xz 展開）、
  `SessionService`、`BrowserCatalog`、`Prefs`、`SetupActivity`/`MainActivity` の骨格、Manifest、App Shortcuts、文言 (ja/en)
- **X サーバ組み込み（済）**: `external/termux-x11`（LDFA パッチ入りコミット 50ac80fb のツリー同梱）を
  `android/embedded-x11` モジュール（LDFA 由来の生成オーバーレイ）でビルドし `libXlorie.so` を APK に同梱。
  全 .so が 16 KB ページ整列済みであることを確認
- **X サーバ起動方式（重要な設計変更）**: 当初案の app_process + CLASSPATH は **不採用**。
  LDFA が Android 17 で app_process 起動の失敗を踏んで v0.8 から移行した実績ある方式に合わせ、
  `XServerService`（`android:process=":x11"` の FGS）内で JNI ブリッジ `EmbeddedX11ServerBridge.start([":0","-noreset"])` を呼ぶ。
  ビューアは上流の `com.termux.x11.MainActivity` + LorieView をそのまま使い、
  `XServerController.openViewer()` が bind → Binder を `EmbeddedX11Display.connect()` で注入して起動する

## Phase 0 — 前提確認（済 2026-08-28）

1. ✅ `native/fetch-proot-from-termux.sh android/app` 実行済み（proot 5.1.107.92、PROOT_LOADER 対応、16KB 整列確認）
2. ✅ lorie 組み込み完了。submodule ではなく **ツリー同梱**（`external/termux-x11`）+ `:embedded-x11` モジュール方式。
   LDFA のパッチコミット 50ac80fb は upstream に存在しないため submodule 化は不可（THIRD_PARTY_NOTICES.md 参照）
3. ✅ `./gradlew :app:testDebugUnitTest` パス、`./gradlew :app:assembleDebug` 成功（APK 約 40 MB / debug）

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

## Phase 2 — アプリ結線（1〜3, 5 済 2026-08-28）

1. ✅ `XServerService`（:x11 プロセスの FGS、`EmbeddedX11ServerBridge` 経由で Xorg 起動、TMPDIR=`<rootfs>/tmp`、
   XKB_CONFIG_ROOT=`<rootfs>/usr/share/X11/xkb`、ソケット/ロックの後始末付き）+ `XServerController` 書き換え
2. ✅ ビューアは LorieView 内蔵ではなく上流 `com.termux.x11.MainActivity` を使用（LDFA 方式）。
   `MainActivity` が Running 遷移時に lorie preference（`<pkg>_preferences`）を §ARCHITECTURE 4 の値で初期化して
   `openViewer()` を呼ぶ。immersive + cutout は両 Activity で設定済み
3. ✅ `SessionService` 完成（X 起動 → awaitSocket → proot → 終了時にビューアを閉じ keepWarm 判定）。通知の「終了」あり
4. `RootfsManager` の展開を実機で 3 回連続成功させる（途中キャンセル → 再開も）← **未・Phase 1 の rootfs 公開後に**
5. ✅ DNS: 実装済み（`RootfsManager.resolvConf()`）。Private DNS 有効時のフォールバック 1.1.1.1 も込み

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
| 1 | targetSdk 35+ で jniLibs の `libproot.so` を `ProcessBuilder` で直接実行できるか（`extractNativeLibs`）。不可なら `/system/bin/linker64 <path>` 経由。LDFA-fix は同方式 (targetSdk 35) で動作実績あり | 起動不可 | Phase 1 の rootfs で初回セッション時 |
| 2 | ~~app_process の CLASSPATH と SELinux~~ → **解消**: :x11 サービス + JNI ブリッジ方式に変更したため app_process は使わない (2026-08-28) | — | — |
| 3 | Direct touch (touchMode=3) で Firefox/Chromium のタッチスクロールが効くか。効かなければ既定を 2 に | 操作性 | Phase 1 |
| 4 | ~~Debian trixie の Chromium が起動するか~~ → **問題確定 (2026-08-28)**: Debian Chromium 150 は X11 表示ありだと起動直後 (Variations 読込後・子プロセス生成前) に**プロセス間共有 futex (sem_wait/FUTEX_WAIT_BITSET, 匿名 rw ページ) で永久待ち**。`--headless=new --dump-dom` は**完全動作** (renderer 生成含む)。Chrome 公式 152 は同一 proot/X で動作 → Debian ビルド固有の表示初期化問題。試して無効: `--no-zygote` / `--in-process-gpu` / `--single-process` / `--use-angle=vulkan|swiftshader` / `CHROME_HEADLESS=1` / `NO_AT_BRIDGE=1 GTK_A11Y=none` / `PROOT_NO_SECCOMP=1` (crashpad の recvmsg ENOSYS は解消するが本体は待ちのまま。この recvmsg エラーは終了時のもので赤ニシン)。当面 Browser.CHROMIUM を supported=false にして UI から非表示。再挑戦の候補: chromium を sid/backports 版に / ungoogled-chromium / Debian パッチ差分の精査 | Chromium 提供不可 (Chrome で代替) | rootfs 改善時 |
| 5 | Firefox ESR の sandbox 無効化 env が proot 内で十分か（`MOZ_DISABLE_*_SANDBOX`） | Firefox 起動不可 | Phase 1 |
| 6 | `apt-get satisfy` で Chrome の Depends を trixie で解決できるか（t64 パッケージ名） | chromebase ビルド失敗 | CI |
| 7 | Android 16/17 の Phantom Process Killer（32 プロセス制限）で Chromium のプロセスが殺されないか | 突然終了 | 長時間テスト。対策: renderer 数制限 |
| 8 | `/system/fonts` のバインドと fontconfig キャッシュ（初回 fc-cache に数秒） | 初回起動が遅い | build 時に fc-cache は不可（別環境）→ 初回起動時に生成 |

## コーディング規約

- Kotlin: 公式スタイル。副作用のないロジック（argv 生成、manifest 解析）はクラスに切り出して単体テスト
- シェル: `#!/bin/bash` + `set -u`（ゲスト）/ `set -euo pipefail`（ビルド）。`scripts/check.sh` が shellcheck を実行
- 文言は `strings.xml`（ja が既定、en を併記）。ログは `Log.i("FB", ...)` に統一
