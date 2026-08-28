// FULL-BROWSER: Firefox ESR 初期設定 (fb-session が倍率などを末尾に追記してプロファイルへコピーする)
// --- 起動を速く ---
user_pref("browser.shell.checkDefaultBrowser", false);
user_pref("browser.startup.page", 1);
user_pref("browser.sessionstore.resume_from_crash", false);
user_pref("browser.sessionstore.max_tabs_undo", 3);
user_pref("browser.sessionhistory.max_total_viewers", 1);
user_pref("browser.aboutConfig.showWarning", false);
user_pref("app.update.auto", false);
user_pref("app.update.enabled", false);
// --- 描画: GPU なし前提。SWGL (ソフトウェア WebRender) が最速 ---
user_pref("gfx.webrender.software", true);
user_pref("layers.acceleration.disabled", true);
user_pref("media.ffmpeg.vaapi.enabled", false);
user_pref("widget.gtk.overlay-scrollbars.enabled", true);
// --- proot 内で使えないサンドボックスを無効化 (外側は Android のアプリサンドボックス) ---
user_pref("security.sandbox.content.level", 0);
user_pref("security.sandbox.gpu.level", 0);
user_pref("security.sandbox.rdd.level", 0);
user_pref("security.sandbox.socket.process.level", 0);
// --- プロセス数 (メモリ) ---
user_pref("dom.ipc.processCount.webIsolated", 1);
user_pref("fission.autostart", false);
user_pref("browser.cache.disk.capacity", 131072);
// --- タッチ操作 ---
user_pref("dom.w3c_touch_events.enabled", 1);
user_pref("apz.allow_zooming", true);
user_pref("browser.compactmode.show", true);
user_pref("browser.uidensity", 1);
user_pref("browser.tabs.inTitlebar", 0);
// --- テレメトリ / 広告 / 不要機能 ---
user_pref("toolkit.telemetry.enabled", false);
user_pref("toolkit.telemetry.unified", false);
user_pref("toolkit.telemetry.archive.enabled", false);
user_pref("datareporting.healthreport.uploadEnabled", false);
user_pref("datareporting.policy.dataSubmissionEnabled", false);
user_pref("browser.newtabpage.activity-stream.showSponsored", false);
user_pref("browser.newtabpage.activity-stream.showSponsoredTopSites", false);
user_pref("browser.newtabpage.activity-stream.feeds.section.topstories", false);
user_pref("browser.urlbar.suggest.quicksuggest.sponsored", false);
user_pref("extensions.pocket.enabled", false);
user_pref("browser.discovery.enabled", false);
// --- 言語 / ダウンロード ---
user_pref("intl.locale.requested", "ja,en-US");
user_pref("intl.accept_languages", "ja,en-US,en");
user_pref("browser.download.folderList", 2);
user_pref("browser.download.useDownloadDir", true);
