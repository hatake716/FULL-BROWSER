# PERFORMANCE — 起動速度とメモリの設計

目標（Pixel 10a 級の端末）: アイコンタップからブラウザ表示まで **初回起動 5 秒以内、復帰 1 秒以内**。
アイドル時の追加メモリ（アプリ + X サーバ + proot + openbox、ブラウザ除く）**60 MB 以下**。

## 1. 起動経路を短くする

| 段階 | 施策 | 効果の見込み |
|---|---|---|
| アプリ起動 | Activity は LorieView を置くだけ。セットアップ済み判定はファイル存在チェック 1 回 | ~100 ms |
| X サーバ | アプリ内 `app_process`。Termux:X11 アプリとの Binder/ソケット往復がない。keepWarm=true なら次回はスキップ | 0.5–1 s → 0 |
| proot | `--kill-on-exit` で子の後始末を任せる。`PROOT_TMP_DIR` を内部ストレージに固定。seccomp 加速（既定） | proot 自体は ~50 ms |
| WM | openbox（依存最小、~10 MB）。デスクトップ環境なし | XFCE 比で −2 s, −150 MB |
| ブラウザ | 下記 §3 | 2–4 s（コールド） |
| 2 回目以降 | ブラウザを終了せずサービスで維持。「起動」= Activity を前面に出すだけ | <1 s |

## 2. proot のオーバーヘッドを抑える

- proot は ptrace ベース。システムコールが多い処理（ファイルオープン、プロセス生成）で遅くなる
- 対策: ブラウザの **プロセス数を減らす**（§3）、`/tmp` `/dev/shm` を rootfs 内に置く、`link2symlink` 以外の拡張は入れない
- seccomp 加速が壊れる端末（まれ）向けに設定で `PROOT_NO_SECCOMP=1` を切替可能

## 3. ブラウザ側

### Firefox ESR（`/etc/fullbrowser/firefox-user.js`）
- `dom.ipc.processCount=2`, `dom.ipc.processCount.webIsolated=1`, `fission.autostart=false`（プロセス数削減。サイト分離は proot 内では保護効果が限定的）
- `gfx.webrender.software=true`（llvmpipe より SWGL が速い）
- `browser.sessionstore.resume_from_crash=false`, `browser.startup.page=1`, `browser.shell.checkDefaultBrowser=false`
- テレメトリ / Pocket / スポンサード新規タブ / アップデータをオフ（更新は apt で行う）
- `browser.tabs.unloadOnLowMemory=true`, `browser.sessionhistory.max_total_viewers=1`
- `layout.css.devPixelsPerPx=<倍率>`（アプリが env から書く）

### Chromium / Chrome（`/etc/fullbrowser/chromium-flags.conf`）
- 常時: `--no-sandbox --disable-dev-shm-usage --password-store=basic --no-first-run --no-default-browser-check --touch-events=enabled --force-device-scale-factor=<倍率>`
- 省メモリモード（既定 ON）: `--disable-gpu --renderer-process-limit=3 --process-per-site --enable-low-end-device-mode`
- `--disable-gpu` は GPU プロセスを作らない。WebGL は無効になる（設定で切替）

## 4. メモリ

| 項目 | 施策 |
|---|---|
| rootfs | doc/man/info 除外、ロケールは ja/en のみ、apt リスト削除、`--no-install-recommends` |
| フォント | Android の `/system/fonts` を参照（Noto Sans CJK 約 100 MB を rootfs に入れない） |
| X サーバ | 解像度 native。`displayScale` は使わず DPI で拡大（フレームバッファ 1 枚のみ） |
| 常駐 | X サーバ ~15 MB、proot ~3 MB、openbox ~10 MB、サービス ~10 MB |
| 低メモリ端末 | 省メモリモード + `browser.tabs.unloadOnLowMemory` / low-end-device-mode |

## 5. 計測方法（実機）

```sh
# 起動時間: アイコンタップ → 最初のフレーム
adb shell am start -W io.github.hatake716.fullbrowser/.MainActivity
# メモリ（PSS）
adb shell dumpsys meminfo io.github.hatake716.fullbrowser
adb shell dumpsys meminfo io.github.hatake716.fullbrowser:xserver   # X サーバは別プロセス
```
結果は `docs/BENCHMARKS.md` に追記する（端末名・Android バージョン・日付を必ず書く）。

## 6. やらないこと（理由付き）

- GPU アクセラレーション（virgl / turnip）: 起動が遅くなり、端末依存のクラッシュ要因。ブラウザ用途では効果が薄い
- VNC 経由の表示: エンコード/デコードの CPU 負荷と遅延が大きい
- rootfs の tmpfs 配置: Android アプリは tmpfs を作れない
- 事前展開した rootfs を APK に同梱: Play の 200 MB 制限と更新性の問題。Asset Delivery を使うと更新のたびに再審査が必要
