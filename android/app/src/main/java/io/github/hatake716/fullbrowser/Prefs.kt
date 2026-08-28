package io.github.hatake716.fullbrowser

import android.content.Context
import kotlin.math.roundToInt

/** アプリ設定。rootfs 側の /root/.config/fullbrowser/env はこの値から生成する。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("fullbrowser", Context.MODE_PRIVATE)
    private val densityDpi = context.resources.displayMetrics.densityDpi

    var defaultBrowser: Browser?
        get() = Browser.byId(sp.getString("default_browser", null))
        set(v) = sp.edit().putString("default_browser", v?.id).apply()

    /** 0f = 自動 (画面密度から算出) */
    var scale: Float
        get() = sp.getFloat("scale", 0f)
        set(v) = sp.edit().putFloat("scale", v).apply()

    /** lorie の touchMode: 1=トラックパッド, 2=擬似タッチ, 3=直接タッチ */
    var touchMode: Int
        get() = sp.getInt("touch_mode", 3)
        set(v) = sp.edit().putInt("touch_mode", v).apply()

    var lowMemory: Boolean
        get() = sp.getBoolean("low_memory", true)
        set(v) = sp.edit().putBoolean("low_memory", v).apply()

    var gpu: Boolean
        get() = sp.getBoolean("gpu", false)
        set(v) = sp.edit().putBoolean("gpu", v).apply()

    /** ブラウザ終了後も X サーバを残して次回起動を速くする */
    var keepWarm: Boolean
        get() = sp.getBoolean("keep_warm", true)
        set(v) = sp.edit().putBoolean("keep_warm", v).apply()

    /** proot の seccomp 加速を切る (相性問題のある端末向け) */
    var noSeccomp: Boolean
        get() = sp.getBoolean("no_seccomp", false)
        set(v) = sp.edit().putBoolean("no_seccomp", v).apply()

    var homepage: String
        get() = sp.getString("homepage", "") ?: ""
        set(v) = sp.edit().putString("homepage", v).apply()

    /** 初回チュートリアル (戻るキー/通知の説明) を表示済みか */
    var tutorialShown: Boolean
        get() = sp.getBoolean("tutorial_shown", false)
        set(v) = sp.edit().putBoolean("tutorial_shown", v).apply()

    /** 実際に使う倍率。自動なら 密度/160 を 0.25 刻みに丸め、1.0〜4.0 に収める。 */
    fun effectiveScale(): Float {
        if (scale > 0f) return scale
        val raw = densityDpi / 160f
        return ((raw * 4).roundToInt() / 4f).coerceIn(1.0f, 4.0f)
    }

    /** rootfs の /root/.config/fullbrowser/env に書く内容 */
    fun guestEnvFile(): String = buildString {
        appendLine("FB_SCALE=${effectiveScale()}")
        appendLine("FB_LOWMEM=${if (lowMemory) 1 else 0}")
        appendLine("FB_GPU=${if (gpu) 1 else 0}")
        appendLine("FB_LANG=ja")
        appendLine("FB_HOMEPAGE=${shellQuote(homepage)}")
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
