# ARCHITECTURE — 設計と決定理由

最終更新: 2026-08-28（v0.1.0）

## 0. 前提の変化と結論

| 前提 | 当初案 | Google Play 前提での結論 |
|---|---|---|
| 配布 | Termux 上のスクリプト（ワンライナー） | **単体 Android アプリ**（applicationId `io.github.hatake716.fullbrowser`） |
| ブラウザ実行 | Termux x11-repo のネイティブ Firefox/Chromium（proot 不要） | **proot + 最小 Debian**（Termux パッケージは `/data/data/com.termux` 固定のため流用不可） |
| 画面 | Termux:X11 アプリ | Termux:X11 の **lorie を組み込み**（GPLv3） |
| ホーム画面 | Termux:Widget | アプリのアイコン自体がランチャー。App Shortcuts で切替 |
| Chrome | 最小コンテナ | 同左（Google 公式 ARM64 .deb を端末上で取得） |
| Edge | 非対応 | 非対応（Linux ARM64 版なし） |

「コンテナより良い方法」は Play 前提では存在しない、というのが調査結果です。代替案の評価は ALTERNATIVES.md。

## 1. コンポーネント

```
┌───────────────────────────── FULL-BROWSER.apk (GPLv3) ─────────────────────────────┐
│ SetupActivity      ブラウザ選択 → rootfs ダウンロード/検証/展開 → 完了              │
│ MainActivity       LorieView (X 画面) をフルスクリーン表示。起動/復帰の入口          │
│ SessionService     FGS(specialUse)。X サーバ起動 → proot 起動 → 監視 → 終了処理      │
│ RootfsManager      manifest 取得, .tar.xz 展開(symlink/権限維持), resolv.conf/TZ 書込 │
│ ProotCommand       proot の argv/env を組み立てる純粋関数（単体テスト対象）          │
│ jniLibs/           libproot.so, libloader.so（Termux 公式 proot を改名して同梱）     │
│ assets/proot/      libtalloc.so.2, libandroid-shmem.so（初回に files/lib へコピー）   │
│ external/termux-x11/lorie   X サーバ本体 (libXlorie.so + LorieView + CmdEntryPoint)  │
└──────────────────────────────────────────────────────────────────────────────────────┘
                     │ app_process (CmdEntryPoint :0, TMPDIR=<rootfs>/tmp)
                     │ ProcessBuilder(libproot.so ...)
                     ▼
 files/rootfs/<variant>/   Debian 13 trixie minbase (arm64)
   /usr/local/bin/fb-session         ← proot のエントリポイント
   /etc/fullbrowser/openbox-rc.xml   ← 全画面 WM 設定
   /etc/fullbrowser/firefox-user.js  ← Firefox 初期 prefs
   /etc/fullbrowser/env              ← 既定値。/root/.config/fullbrowser/env をアプリが上書き
   /etc/fonts/conf.d/99-android-fonts.conf  ← /system/fonts を参照
```

## 2. 実行の流れ（2 回目以降の通常起動）

1. アイコンタップ → `MainActivity` → rootfs が無ければ `SetupActivity` へ。あれば `SessionService.start(browser)`
2. `SessionService`
   1. `files/lib` にランタイム lib が無ければ assets から展開（初回のみ）
   2. `/etc/resolv.conf`（Android の DNS）, `/root/.config/fullbrowser/env`（倍率・省メモリ等の設定）, `TZ` を rootfs に書く
   3. X サーバ起動: `app_process` で `com.termux.x11.CmdEntryPoint :0`。環境変数 `TMPDIR=<rootfs>/tmp`, `XKB_CONFIG_ROOT=<rootfs>/usr/share/X11/xkb`, `CLASSPATH=<自分の APK>`
   4. `<rootfs>/tmp/.X11-unix/X0` ができるのを待つ（最大 10 秒）
   5. proot 起動（§3）。子コマンドは `/usr/local/bin/fb-session <browser>`
   6. WakeLock 取得、通知（「FULL-BROWSER 実行中 — 終了」）
3. `fb-session`
   1. `Xft.dpi` を xrdb で設定（倍率 × 96）
   2. `openbox --config-file /etc/fullbrowser/openbox-rc.xml &`
   3. ブラウザ起動（プロファイルは `/root/.fullbrowser/<browser>`）
   4. ブラウザ終了を待って openbox を止め、終了コードを返す
4. proot 終了 → `SessionService` が X サーバを止め（keepWarm=false 時）、WakeLock を離し、通知を消す
5. ホームボタンで離れた場合はサービスもブラウザも生存。戻ると LorieView が再接続して即表示

## 3. proot の起動コマンド

`ProotCommand.kt` が生成する argv（proot-distro 5.8 の Termux 向け構成に準拠）:

```
libproot.so
  --kill-on-exit
  --link2symlink                  # アプリ領域では hardlink 不可のため
  --sysvipc                       # Android カーネルに SysV IPC がない。Chromium/Firefox が使う
  --kernel-release=\Linux\localhost\6.6.0-fullbrowser\#1 SMP PREEMPT\aarch64\localdomain\-1\
  -L                              # lstat の修正（dpkg 警告対策）
  --change-id=0:0
  --rootfs=<files>/rootfs/<variant>
  --cwd=/root
  --bind=/dev --bind=/proc --bind=/sys
  --bind=/dev/urandom:/dev/random
  --bind=/proc/self/fd:/dev/fd
  --bind=/proc/self/fd/0:/dev/stdin --bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/2:/dev/stderr
  --bind=<rootfs>/tmp/.shm:/dev/shm          # Android には /dev/shm がない。Chromium は --disable-dev-shm-usage も併用
  --bind=<rootfs>/etc/fullbrowser/proc/stat:/proc/stat          # 以下、SELinux で読めない /proc の偽装
  --bind=<rootfs>/etc/fullbrowser/proc/version:/proc/version
  --bind=<rootfs>/etc/fullbrowser/proc/loadavg:/proc/loadavg
  --bind=<rootfs>/etc/fullbrowser/proc/uptime:/proc/uptime
  --bind=<rootfs>/etc/fullbrowser/proc/vmstat:/proc/vmstat
  --bind=<rootfs>/etc/fullbrowser/proc/cap_last_cap:/proc/sys/kernel/cap_last_cap
  --bind=<rootfs>/etc/fullbrowser/proc/max_user_watches:/proc/sys/fs/inotify/max_user_watches
  --bind=/system/fonts:/system/fonts         # 日本語フォント流用（fontconfig 側で参照）
  --bind=/system/etc/security/cacerts:/system/etc/security/cacerts   # 任意（Debian の ca-certificates で十分）
  /usr/bin/env -i HOME=/root USER=root TERM=xterm-256color LANG=C.UTF-8 LANGUAGE=ja TZ=<Android の TZ>
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
      DISPLAY=:0 PULSE_SERVER=127.0.0.1 MOZ_USE_XINPUT2=1 FB_BROWSER=<browser>
      /usr/local/bin/fb-session <browser>
```

環境変数（proot 自身向け）: `PROOT_LOADER=<nativeLibraryDir>/libloader.so`, `PROOT_TMP_DIR=<files>/tmp`,
`PROOT_L2S_DIR=<files>/tmp/l2s`, `LD_LIBRARY_PATH=<files>/lib`。`PROOT_NO_SECCOMP` は設定しない（seccomp 加速を使う。
不安定な端末向けに設定でオフにできる）。

Termux 公式 proot 5.1.107.92 (aarch64) は `PROOT_LOADER` / `PROOT_TMP_DIR` / `PROOT_L2S_DIR` に対応し、
LOAD セグメントのアライメントは 0x4000（16 KB ページ対応）であることを確認済み。依存は `libtalloc.so.2`, `libandroid-shmem.so`, `libc.so`。

## 4. 全画面の実現

| 層 | 設定 | 理由 |
|---|---|---|
| Android | `WindowInsetsController` で immersive、cutout はコンテンツ外へ（`LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER`） | ノッチにタブバーが隠れない |
| lorie | `fullscreen=true`, `hideCutout=true`, `touchMode=3`(Direct touch), `Reseed=true`, `showAdditionalKbd=false`, `displayResolutionMode=native`, `clipboardEnable=true` | Termux:X11 と同じ preference キー。Reseed=true でソフトキーボード表示時に X 画面が縮み、openbox が再最大化するので入力欄が隠れない |
| openbox | `type="normal"` は `<decor>no</decor><maximized>yes</maximized>`。dialog は装飾あり中央 | タイトルバーなしで常に画面いっぱい。回転で X 画面サイズが変わっても再最大化 |
| DPI | `Xft.dpi = 96 × 倍率`（xrdb） + Chromium 系は `--force-device-scale-factor`、Firefox は `layout.css.devPixelsPerPx` | 既定倍率は `ro.sf.lcd_density / 160` を 0.25 刻みに丸め（Pixel 10a ≒ 2.5） |

## 5. ブラウザ別の扱い

| | Firefox ESR | Chromium | Google Chrome |
|---|---|---|---|
| 入手 | イメージ `firefox` に同梱（Debian） | イメージ `chromium` に同梱（Debian） | イメージ `chromebase`（依存のみ）+ 端末上で `google-chrome-stable_current_arm64.deb` を取得し `dpkg -i` |
| 起動 | `firefox-esr --profile /root/.fullbrowser/firefox` | `chromium --user-data-dir=...` | `google-chrome-stable --user-data-dir=...` |
| サンドボックス | `MOZ_DISABLE_*_SANDBOX=1` + `security.sandbox.content.level=0`（proot 内では user namespace/seccomp が使えない） | `--no-sandbox` | `--no-sandbox`（「サポートされていないフラグ」バーが出る。仕様として案内） |
| 日本語 UI | `firefox-esr-l10n-ja` | `chromium-l10n` | 同梱 |
| 更新 | 設定 →「ブラウザを更新」= rootfs 内で `apt-get install --only-upgrade`。または新イメージへ差し替え | 同左 | 同左（Google の apt リポジトリが .deb 導入時に登録される） |
| DRM/同期 | ×/× | ×/× | ○/○ |

Chrome の再配布は Google Chrome 利用規約で禁止されているため、イメージには含めず、利用者の操作で Google のサーバから直接取得する（Debian の各種インストーラと同じ方式）。

## 6. rootfs イメージ

- Debian 13 "trixie", `mmdebstrap --variant=minbase`, arm64
- 共通パッケージ: `openbox fontconfig fonts-dejavu-core xkb-data x11-xserver-utils ca-certificates tzdata procps`
- `dpkg` の `path-exclude` で doc/man/info/locale(ja 以外) を除外、`APT::Install-Recommends false`
- `rootfs/overlay/` をそのまま `/` に同期（fb-session, 設定, /proc スタブ, fontconfig）
- 成果物: `fullbrowser-rootfs-<variant>-arm64-<YYYYMMDD>.tar.xz` + `manifest.json`
- 配布: GitHub Release `rootfs-latest`（移動タグ）。アプリは `manifest.json` の `sha256` で検証してから展開
- 展開先: `files/rootfs/<variant>.tmp` に展開 → 検証 → `files/rootfs/<variant>` にリネーム（途中失敗は自動でやり直し）

## 7. データ配置（端末内）

```
/data/data/io.github.hatake716.fullbrowser/
  files/lib/            libtalloc.so.2, libandroid-shmem.so
  files/tmp/            PROOT_TMP_DIR, l2s
  files/images/         ダウンロード中の .tar.xz（展開後に削除）
  files/rootfs/firefox/  files/rootfs/chromium/  files/rootfs/chromebase/
  files/rootfs/<v>/root/.fullbrowser/<browser>/   ブラウザプロファイル（設定・履歴・Cookie）
  files/rootfs/<v>/root/Downloads/                ブラウザのダウンロード先（設定画面から SAF で書き出し）
```

## 8. 参照した一次情報（2026-08-28 時点）

- Termux proot 5.1.107.92 (aarch64): `PROOT_LOADER` 等の環境変数、NEEDED、16 KB アライメントをバイナリから確認
- proot-distro 5.8.0（Python 版）: proot 引数構成、/proc スタブ、/dev/shm バインド
- Termux:X11 (lorie): preference キー一覧（`lorie/src/main/res/xml/preferences.xml`）、`termux-x11-preference` の受信処理、chroot/proot 向けの `TMPDIR` / `XKB_CONFIG_ROOT` 要件
- Google Chrome for ARM64 Linux: 2026-07-30 提供開始、`https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb`
- Microsoft Edge for Linux: x86_64 のみ
