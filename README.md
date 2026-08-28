# FULL-BROWSER

Android スマートフォンで **デスクトップ版 Linux ブラウザ（Firefox / Chromium / Google Chrome）を全画面で動かす** アプリです。
Google Play での公開を前提に設計しています。Termux などの別アプリをインストールする必要はありません。

- 初回起動時にブラウザを **選択式** でセットアップ（1 回だけ）
- 2 回目以降は **アプリのアイコンをタップするだけ** でブラウザが全画面で起動
- 中身は「最小構成の Debian コンテナ」。デスクトップ環境（XFCE 等）は入れず、ブラウザとウィンドウマネージャだけ
- 起動速度とメモリを最優先にチューニング（詳細は [docs/PERFORMANCE.md](docs/PERFORMANCE.md)）

> **開発状況**: v0.1.0 = 設計と骨格。rootfs ビルド・ゲスト側スクリプト・Android 側のコアロジックは実装済み、
> X サーバ組み込みと UI の結線は [docs/HANDOVER.md](docs/HANDOVER.md) の手順で仕上げます。

---

## 対応ブラウザ

| 選択肢 | 入手元 | 目安サイズ（展開後） | 備考 |
|---|---|---|---|
| **Firefox ESR**（おすすめ） | Debian 公式パッケージ（日本語 UI 付き） | 約 450 MB | 起動が最も速く、メモリも少ない。事前ビルド済みイメージを 1 回ダウンロードするだけ |
| **Chromium** | Debian 公式パッケージ（日本語 UI 付き） | 約 550 MB | Chrome と同じエンジン。Google アカウント同期・Widevine（Netflix 等の DRM）は非対応 |
| **Google Chrome** | Google 公式 ARM64 版 .deb（2026 年 7 月 30 日提供開始） | 約 650 MB | Google 同期・Widevine 対応。ライセンス上、本体は端末上で Google のサーバから取得します |
| Microsoft Edge | — | — | **非対応**。Linux ARM64 版が存在しないため（2026 年 8 月時点）。x86 エミュレーションは速度・メモリの面で実用になりません |

---

## 使い方（利用者向けの導線）

1. Google Play から **FULL-BROWSER** をインストールして開く
2. 初回ウィザードで **ブラウザを 1 つ選ぶ** → ダウンロード（Wi-Fi 推奨、サイズは画面に表示）→ 完了
3. 以後は **アイコンをタップ** → 数秒でブラウザが全画面で開く
4. アイコン **長押し** で「Firefox で開く / Chromium で開く / Chrome で開く / 設定」を選べます（インストール済みのものだけ表示）

操作のコツ（画面内にも表示されます）

- **キーボードを出す/しまう**: 戻るボタン（Termux:X11 と同じ挙動）。日本語入力は Android の IME（Gboard 等）がそのまま使えます
- **スクロール/ピンチ**: 指で直接操作（設定で「トラックパッド方式」にも変更可）
- **終了**: 通知の「終了」ボタン、またはブラウザ自身を閉じる。ホームボタンで離れてもブラウザは裏で生き続けるので、戻れば即表示
- **設定**: 表示倍率（文字サイズ）、タッチ方式、省メモリモード、ブラウザの追加/更新/削除

---

## 仕組み

```
[FULL-BROWSER アプリ (Kotlin, GPLv3)]
   ├── X サーバ (Termux:X11 の lorie を組み込み) ──── 画面・タッチ・IME
   ├── SessionService (フォアグラウンドサービス) ─── proot を起動・監視・終了
   └── proot (APK 同梱, jniLibs)
         └── 最小 Debian 13 (trixie) rootfs  ← 初回にダウンロード (1 ファイル)
               ├── openbox (装飾なし・常時最大化 = 全画面, 回転追従)
               ├── fb-session (ゲスト側の起動スクリプト)
               └── firefox-esr / chromium / google-chrome-stable
```

- rootfs は CI（GitHub Actions, ARM64 ランナー）が `mmdebstrap --variant=minbase` で毎週ビルドし、
  Release `rootfs-latest` に `manifest.json`（SHA-256 付き）と一緒に置きます
- 日本語フォントは Android 本体の `/system/fonts`（Noto Sans CJK）を rootfs に見せて流用。フォントのダウンロードは不要
- タイムゾーン・DNS は起動のたびにアプリが Android から取得して rootfs に渡します

設計の詳細と決定理由は [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) にあります。

---

## なぜ「コンテナ方式」なのか（Google Play 前提での結論）

当初は Termux の x11-repo にある **ネイティブ版 Firefox / Chromium**（proot 不要、最速）を主役にする案でした。
しかし Google Play で単体アプリとして公開するには、

1. Termux / Termux:X11 / Termux:Widget といった外部アプリへの依存が許されない（利用者にサイドロードを求められない）
2. 独自の applicationId が必要だが、Termux のパッケージ群は `/data/data/com.termux/files/usr` 固定でビルドされており流用できない
   （全パッケージの再ビルドが必要。Chromium 1 本で数時間の CI）
3. targetSdk 29 以降はアプリ領域の実行ファイルを `exec` できないが、proot は APK 同梱 (`jniLibs`) + `PROOT_LOADER` で回避でき、UserLAnd 等の前例がある

ため、**proot + 最小 Debian** が Play 配布で成立する唯一の現実解です。
そのうえで「最小限」を徹底し（minbase + ブラウザ + openbox のみ、doc/man/不要ロケール除外）、
proot のオーバーヘッドを差し引いても体感で困らないよう起動経路を短くしています。検討した他の案は [docs/ALTERNATIVES.md](docs/ALTERNATIVES.md)。

---

## 起動速度・メモリの要点

- ブラウザは **バックグラウンドで生かし続ける**（フォアグラウンドサービス + WakeLock）。2 回目以降の「起動」は画面を前に出すだけ
- デスクトップ環境なし。X サーバはアプリ内蔵なのでアプリ間 IPC がない
- Firefox: テレメトリ停止、コンテンツプロセス数 2、ソフトウェア WebRender、セッション復元オフ
- Chromium / Chrome: `--disable-gpu`（GPU プロセスを作らない）、`--renderer-process-limit`、`--process-per-site`、低スペック端末モード
- rootfs: `Acquire::Languages none`、`path-exclude` で doc/man/locale（ja 以外）を除外、apt キャッシュなし
- 詳細は [docs/PERFORMANCE.md](docs/PERFORMANCE.md)

---

## リポジトリ構成（開発者向け）

```
docs/            設計・Play 対応・性能・引き継ぎ・代替案
rootfs/          rootfs ビルド (mmdebstrap) と、rootfs に焼き込むゲスト側ファイル (overlay/)
native/          proot の取得/ビルド手順（Termux の公式 .deb から抽出、または静的ビルド）
android/         Android アプリ (Kotlin)。X サーバは external/termux-x11 (submodule) の lorie を組み込む
.github/         CI: rootfs ビルド・proot 取得・shellcheck
scripts/         ローカル検証 (scripts/check.sh)
```

### ビルド

```sh
# 1) rootfs（ARM64 Linux 上、または GitHub Actions の ubuntu-24.04-arm）
sudo apt install mmdebstrap xz-utils
rootfs/build-rootfs.sh firefox out/     # base | firefox | chromium | chromebase

# 2) proot（Termux 公式パッケージから抽出 → android/app/src/main/jniLibs と assets に配置）
native/fetch-proot-from-termux.sh android/app

# 3) Android
git submodule update --init          # external/termux-x11
cd android && ./gradlew :app:assembleDebug
```

### 状態と次の作業

[docs/HANDOVER.md](docs/HANDOVER.md) に Phase 分けした手順・未検証項目・テスト表があります。

---

## ライセンス

GPL-3.0（X サーバ部分に Termux:X11 (GPLv3) を組み込むため）。proot は GPLv2（別プロセスとして同梱）。
Firefox / Chromium / Google Chrome / Microsoft Edge はそれぞれの権利者の商標です。本アプリは各社と無関係です。
