# Google Chrome 用ベース: 依存パッケージだけを先に入れる。Chrome 本体は再配布禁止のため
# 端末上で fb-install-chrome が Google のサーバから取得して dpkg -i する。
FB_BROWSER_ID="chrome"
FB_PKGS="wget xdg-utils fonts-liberation"

# ビルドホストで .deb をダウンロードし、Depends を取り出して apt-get satisfy に渡す。
fb_prepare() {
  local work="$1"
  local url="${FB_CHROME_DEB_URL:-https://dl.google.com/linux/direct/google-chrome-stable_current_arm64.deb}"
  echo ">> fetching Chrome .deb (only to read its Depends): $url"
  curl -fsSL --retry 3 -o "$work/chrome.deb" "$url"
  dpkg-deb -f "$work/chrome.deb" Depends > "$work/chrome-depends.txt"
  dpkg-deb -f "$work/chrome.deb" Version  > "$work/chrome-version.txt"
  echo ">> Chrome $(cat "$work/chrome-version.txt") depends: $(cat "$work/chrome-depends.txt")"
  FB_HOOKS=(
    --customize-hook="copy-in $work/chrome-depends.txt /etc/fullbrowser/"
    --customize-hook='chroot "$1" sh -c '"'"'apt-get satisfy -y --no-install-recommends "$(cat /etc/fullbrowser/chrome-depends.txt)"'"'"''
  )
}
