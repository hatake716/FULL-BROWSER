#!/usr/bin/env bash
# dev-serve.sh — 開発用: rootfs イメージをローカル配信して実機から取得できるようにする。
#
# リポジトリがプライベートの間は GitHub Releases を端末から匿名ダウンロードできないため、
# out/serve/ を PC 上で配信し、adb reverse で端末の 127.0.0.1:8000 に見せる。
# 注意: adb reverse は USB 再接続のたびに消えるので、その都度このスクリプトを実行し直す。
#
# 使い方:
#   scripts/dev-serve.sh          # サーバ起動 (未起動なら) + adb reverse + 端末側 override 設定
#   scripts/dev-serve.sh stop     # サーバ停止 + 端末側 override 解除 (GitHub URL に戻す)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=io.github.hatake716.fullbrowser
PORT=8000

if [ "${1:-}" = "stop" ]; then
  pkill -f "http.server $PORT" 2>/dev/null || true
  adb shell "run-as $PKG rm -f files/manifest-url.override" 2>/dev/null || true
  echo "stopped (override removed)"
  exit 0
fi

[ -f "$HERE/out/serve/manifest.json" ] || {
  echo "out/serve/manifest.json がありません。先にイメージを配置してください:" >&2
  echo "  FB_BASE_URL=http://127.0.0.1:$PORT/ python3 rootfs/make-manifest.py out/serve/" >&2
  exit 1
}

pgrep -f "http.server $PORT" >/dev/null || {
  (cd "$HERE" && python3 -m http.server "$PORT" --bind 127.0.0.1 --directory out/serve >/dev/null 2>&1 &)
  sleep 1
}
curl -fsS -o /dev/null "http://127.0.0.1:$PORT/manifest.json" && echo "server: OK (127.0.0.1:$PORT)"

adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null && echo "adb reverse: OK"
adb shell "run-as $PKG sh -c 'mkdir -p files; echo http://127.0.0.1:$PORT/manifest.json > files/manifest-url.override'" \
  && echo "override: http://127.0.0.1:$PORT/manifest.json"
