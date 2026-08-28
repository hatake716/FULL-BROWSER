package io.github.hatake716.fullbrowser

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log

/** アイコン長押しメニューの動的更新。インストール済みブラウザだけを出す (docs/HANDOVER.md Phase 3-3)。 */
object Shortcuts {

    fun update(context: Context, rootfs: RootfsManager) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        try {
            val max = (sm.maxShortcutCountPerActivity - 1).coerceAtLeast(1)
            sm.dynamicShortcuts = rootfs.readyBrowsers().take(max).map { b ->
                ShortcutInfo.Builder(context, "browser_${b.id}")
                    .setShortLabel(context.getString(labelOf(b)))
                    .setLongLabel(context.getString(labelOf(b)))
                    .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(ACTION_LAUNCH)
                            .putExtra(SessionService.EXTRA_BROWSER, b.id)
                    )
                    .build()
            }
        } catch (e: Exception) {
            Log.w(App.TAG, "shortcut update failed: $e")
        }
    }

    private fun labelOf(b: Browser): Int = when (b) {
        Browser.FIREFOX -> R.string.shortcut_firefox
        Browser.CHROMIUM -> R.string.shortcut_chromium
        Browser.CHROME -> R.string.shortcut_chrome
        else -> b.labelRes
    }

    private const val ACTION_LAUNCH = "io.github.hatake716.fullbrowser.LAUNCH"
}
