# PLAY-COMPLIANCE — Google Play 公開チェックリスト

Play Console のポリシーは変わるため、提出前に各項目の最新版を必ず再確認すること（日付を記録する）。

## A. 技術要件

- [ ] **target API**: Play の「対象 API レベル要件」の最新値に合わせる（2026-08 時点で Android 17 = API 36 が求められる見込み。`android/app/build.gradle.kts` の `targetSdk` を更新）
- [ ] **16 KB ページサイズ**: 同梱するネイティブ物（`libproot.so`, `libloader.so`, `libXlorie.so`, assets の .so）がすべて 16 KB アライン。
      proot は確認済み（LOAD align 0x4000）。lorie は NDK 側で `-Wl,-z,max-page-size=16384` を指定。Debian arm64 のユーザランドは既定で 64 KB アラインなので影響なし
- [ ] **App Bundle (.aab)** で提出。jniLibs は `arm64-v8a` のみ（32bit 端末は対象外 → `abiFilters`）
- [ ] **フォアグラウンドサービス種別**: `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`。Play Console の申告欄に
      「ユーザーが開始した Linux ブラウザセッションを画面を離れても維持するため（Termux/UserLAnd 等と同種）」と記載。動画で挙動を示す
- [ ] **権限**: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `WAKE_LOCK` のみ。
      `MANAGE_EXTERNAL_STORAGE` は使わない（ダウンロード書き出しは SAF）
- [ ] **Data safety**: アプリ自身は個人データを収集・送信しない。rootfs/manifest 取得先（GitHub）と、利用者が選んだ場合の Google（Chrome 取得）を「アプリの機能に必要な通信」として記載

## B. コンテンツポリシー

- [ ] **実行コードのダウンロード**（Device and Network Abuse）: rootfs とブラウザは Play 外から取得する。
      ポリシーの例外「仮想マシンやインタプリタ上で動くコード」に該当する構成（proot = ユーザ空間のシステムコール変換）として設計し、
      説明文に明記。前例: UserLAnd, Andronix（いずれも Play で配布中）。**アプリ自身（APK）の自己更新は絶対にしない**
- [ ] **改ざん検証**: `manifest.json` の SHA-256 で検証してから展開。HTTPS のみ
- [ ] **年齢区分 (IARC)**: 「制限のない Web アクセス」に該当 → 高めのレーティング。質問票で正直に回答
- [ ] **商標**: アプリ名・アイコンに Firefox / Chrome / Chromium / Edge のロゴや名称を使わない。
      選択画面での名称表示は説明のための使用（nominative use）に留め、「各社と無関係」を About に明記
- [ ] **Chrome の再配布禁止**: イメージに Chrome を含めない。端末上で利用者操作により Google から取得（規約表示 → 同意 → ダウンロード）
- [ ] **GPLv3**: ソース全文を公開（このリポジトリ）。About 画面からライセンスとソースへのリンク。
      Termux:X11 (lorie) の著作権表示を保持
- [ ] **ユーザー生成コンテンツ**には該当しない（一般ブラウザ）

## C. UX / 審査対策

- [ ] 初回ウィザードで「約 xxx MB をダウンロードします」「Wi-Fi 推奨」を明示（審査員はモバイル回線で試すことがある）
- [ ] rootfs 取得に失敗した場合の再試行・エラー文言（日本語/英語）
- [ ] 通知権限が拒否されてもセッションは動く（通知は任意）
- [ ] 端末の空き容量チェック（イメージ展開後サイズ × 1.5 未満なら中断して案内）
- [ ] 審査用メモ: 「Debian の公式パッケージで配布される Firefox/Chromium をユーザー空間で実行する。端末のシステム領域は変更しない。root 不要」

## D. リリース前テスト（実機）

| 項目 | 端末 | 合格基準 |
|---|---|---|
| 新規インストール → Firefox セットアップ | Pixel 10a (Android 17) | 5 分以内に完了、再起動後も起動可 |
| 3 ブラウザの全画面・回転・IME | 同上 | タブバーがノッチに隠れない。回転後も最大化。Gboard で日本語入力できる |
| バックグラウンド復帰 | 同上 | 30 分放置後にアイコンから復帰しても再起動しない |
| 低メモリ | 4 GB RAM 端末（あれば） | 省メモリモードでタブ 5 枚が開ける |
| Play 事前審査 | Play Console | ポリシー警告ゼロ |
