# ALTERNATIVES — 検討した案と採否

| 案 | 起動速度 | メモリ | Play 配布 | 判定 |
|---|---|---|---|---|
| **A. proot + 最小 Debian（採用）** | ○（2 回目以降は復帰のみ） | ○ | ○（UserLAnd 等の前例） | 採用 |
| B. Termux ネイティブ版 Firefox/Chromium（x11-repo） | ◎ 最速 | ◎ | × Termux 依存 + com.termux プレフィックス固定 | 不採用（Termux 利用者向けの別配布なら最良。将来 `termux-edition/` として復活可） |
| C. 自前プレフィックスで Termux パッケージを全再ビルド | ◎ | ◎ | ○ | 不採用: Chromium/Firefox 含む全パッケージの CI 維持コストが大きすぎる |
| D. Android 公式 Linux ターミナル（AVF 仮想マシン, Android 16 QPR2+） | △ VM 起動 + ソフト描画 | △ VM に RAM を予約 | × サードパーティが AVF の VM を作る API は非公開 | 不採用（Pixel 利用者が手動で Chrome を動かす手順としては有用。§下記） |
| E. chroot（root 必須） | ◎ | ◎ | × | 不採用 |
| F. VNC（TigerVNC + ビューア） | △ | △ | ○ | 不採用: lorie より遅い |
| G. Edge を box64 で x86_64 エミュレーション | × | × | △ | 不採用: 実用速度が出ない |

## Pixel 端末で「公式 Linux ターミナル」を使って Chrome を動かす（参考手順・アプリ外）

Android 16 QPR2 以降の Pixel では開発者向けオプション →「Linux 開発環境」を有効化すると
Debian の VM が使える。グラフィカルアプリは画面右上の Display ボタンで表示する。

```sh
sudo apt update && sudo apt install -y weston
wget https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb
sudo apt install -y ./google-chrome-stable_current_arm64.deb
# Display を開いてから:
weston &
WAYLAND_DISPLAY=wayland-1 google-chrome-stable --ozone-platform=wayland
```
物理キーボードが無いとソフトキーボードが出ない、GPU アクセラレーションは今後の QPR 待ち、といった制約があるため、
本アプリの代替にはならないが、Widevine を含む「完全な Chrome」を最も堅実に動かせる方法ではある。
