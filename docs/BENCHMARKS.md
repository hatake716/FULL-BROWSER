# BENCHMARKS — 実機計測ログ

計測方法は docs/PERFORMANCE.md §5。端末名・Android バージョン・アプリ版・日付を必ず記録する。

| 日付 | 端末 / OS | アプリ版 | ブラウザ | 初回起動 (s) | 復帰 (s) | 常駐 PSS (MB) | ブラウザ込み PSS (MB) | 備考 |
|---|---|---|---|---|---|---|---|---|
| (未計測) | Pixel 10a / Android 17 | 0.1.0 | Firefox ESR | | | | | |

## 動作確認ログ

### 2026-08-28 — 初回 end-to-end 成功 (Pixel, debug 0.1.0, Firefox ESR)

- セットアップ: manifest 取得 → 139MB DL → SHA-256 検証 → 614MB 展開、一発成功
  (manifest-url.override + adb reverse + ローカル HTTP で実施。経路はリリースと同一コード)
- セッション: XServerService (:x11) の Xorg 起動 → proot fb-session → Firefox ESR 起動、
  タップから概ね 10 秒前後で全画面表示
- 表示: 日本語 UI・日本語フォント描画 OK (/system/fonts + fontconfig)。タッチスクロール OK (touchMode=3)
- 既知の課題: 既定スケール (auto ≈ 2.5) では UI がかなり大きい。Phase 3 の設定画面で調整可能にする
- ログ上の無害な警告: glxtest (GPU 無効なので想定どおり)、DBus machine-id、a11y Bus
- HANDOVER 未検証項目の解消: #1 (targetSdk 36 で jniLibs の proot を直接 exec → 動作)、
  #5 (Firefox sandbox 無効化 env で起動 → 動作)、#6 (Chrome Depends を trixie で解決 → chromebase ビルド成功)、
  #8 (/system/fonts バインドと fontconfig → 日本語表示 OK)
