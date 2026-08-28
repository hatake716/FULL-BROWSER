# BENCHMARKS — 実機計測ログ

計測方法は docs/PERFORMANCE.md §5。端末名・Android バージョン・アプリ版・日付を必ず記録する。

| 日付 | 端末 / OS | アプリ版 | ブラウザ | 初回起動 (s) | 復帰 (s) | 常駐 PSS (MB) | ブラウザ込み PSS (MB) | 備考 |
|---|---|---|---|---|---|---|---|---|
| (未計測) | Pixel 10a / Android 17 | 0.1.0 | Firefox ESR | | | | | |

## 動作確認ログ

### 2026-08-28 — 動画再生の計測と改善 (Pixel, Chrome, YouTube 1080p ページ)

| 条件 | X 提示レート | ボトルネック |
|---|---|---|
| 描画解像度 100% | 19〜23 FPS | Chrome 表示合成プロセス (SW) が単核 84% で飽和。体感コマ送り |
| 描画解像度 75% (新既定) | **79〜113 FPS** | 合成コスト約半減で解消。renderer 119%(デコード)・proot 57% |

- メモリ: 7.7GB 端末で swap 3.4GB 使用を観測 → 省メモリ既定の閾値を 6GB→10GB に変更
  (renderer-process-limit=3 / low-end-device-mode が有効になる)
- 音声途切れ対策: 上記 CPU 改善 + FIFO を F_SETPIPE_SZ で 1MB に拡大
- 今後の最適化候補: lorie の present が非同期フリーラン (112FPS) で合成 CPU を浪費 →
  リフレッシュ同期のフレームキャップを入れれば合成プロセスの 80% をさらに削れる見込み

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
