#!/usr/bin/env python3
"""out/ にある fullbrowser-rootfs-*.tar.xz から manifest.json を生成する。

manifest.json の形式 (schema 1):
{
  "schema": 1, "suite": "trixie", "arch": "arm64", "build": "20260828",
  "base_url": "https://github.com/hatake716/FULL-BROWSER/releases/download/rootfs-latest/",
  "images": {
    "firefox":    {"file": "...tar.xz", "sha256": "...", "size": 123, "extracted": 456, "browser": "firefox"},
    ...
  }
}
"""
import hashlib, json, os, re, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
BASE_URL = os.environ.get("FB_BASE_URL", "https://github.com/hatake716/FULL-BROWSER/releases/download/rootfs-latest/")
PAT = re.compile(r"^fullbrowser-rootfs-(?P<variant>[a-z]+)-(?P<arch>[a-z0-9]+)-(?P<build>\d{8})\.tar\.xz$")
BROWSER_OF = {"base": "none", "firefox": "firefox", "chromium": "chromium", "chromebase": "chrome"}

images, build, arch = {}, None, None
for name in sorted(os.listdir(OUT)):
    m = PAT.match(name)
    if not m:
        continue
    path = os.path.join(OUT, name)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    extracted = 0
    eb = path[:-len(".tar.xz")] + ".extracted-bytes"
    if os.path.exists(eb):
        extracted = int(open(eb).read().strip() or 0)
    v = m.group("variant")
    images[v] = {"file": name, "sha256": h.hexdigest(), "size": os.path.getsize(path),
                 "extracted": extracted, "browser": BROWSER_OF.get(v, v)}
    build, arch = m.group("build"), m.group("arch")

if not images:
    sys.exit(f"no images found in {OUT}")

manifest = {"schema": 1, "suite": os.environ.get("FB_SUITE", "trixie"), "arch": arch,
            "build": build, "base_url": BASE_URL, "images": images}
with open(os.path.join(OUT, "manifest.json"), "w") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(json.dumps(manifest, indent=2))
