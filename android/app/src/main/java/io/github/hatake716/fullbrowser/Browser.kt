package io.github.hatake716.fullbrowser

/**
 * 選択肢の定義。imageVariant は rootfs イメージ名 (manifest.json の images キー)。
 * needsOnDeviceInstall = true のものは、イメージ展開後に rootfs 内で fb-install-<id> を実行する (Chrome)。
 */
enum class Browser(
    val id: String,
    val imageVariant: String,
    val labelRes: Int,
    val descRes: Int,
    val supported: Boolean,
    val needsOnDeviceInstall: Boolean,
) {
    FIREFOX("firefox", "firefox", R.string.browser_firefox, R.string.browser_firefox_desc, true, false),
    CHROMIUM("chromium", "chromium", R.string.browser_chromium, R.string.browser_chromium_desc, true, false),
    CHROME("chrome", "chromebase", R.string.browser_chrome, R.string.browser_chrome_desc, true, true),
    EDGE("edge", "", R.string.browser_edge, R.string.browser_edge_desc, false, false);

    companion object {
        fun byId(id: String?): Browser? = entries.firstOrNull { it.id == id }
        val selectable: List<Browser> get() = entries.filter { it.supported }
    }
}
