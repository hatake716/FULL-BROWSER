# Microsoft Edge: Linux ARM64 版が存在しない (2026-08 時点、x86_64 のみ)。
# x86_64 エミュレーション (box64/qemu-user) は速度・メモリの面で実用にならないため非対応。
FB_BROWSER_ID="edge"
FB_PKGS=""
echo "Microsoft Edge は非対応です (Linux ARM64 版が存在しません)" >&2
exit 3
