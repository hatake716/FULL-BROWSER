package com.termux.x11

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import java.io.File

/**
 * 「テキストボックスをタップしたらキーボードが出る」ためのブリッジ。
 *
 * ゲスト側の GTK IM モジュール (rootfs/im-fb) が、テキスト入力欄のフォーカス
 * in/out を <rootfs>/tmp/.fb-ime に書く (1=フォーカス, 0=解除)。ここではその
 * ファイルを inotify (FileObserver) で監視し、ビューア (LorieView) に対して
 * ソフトキーボードを表示/非表示する。スマホのブラウザと同じ使い勝手になる。
 *
 * com.termux.x11 パッケージに置くのはビューア内部 (MainActivity/LorieView) に
 * 同一パッケージとしてアクセスするため。
 */
class LorieImeBridge(private val stateFile: File) {
    private val handler = Handler(Looper.getMainLooper())
    private var observer: FileObserver? = null
    private val hideRunnable = Runnable { setImeShown(false) }

    fun start() {
        stop()
        val dir = stateFile.parentFile ?: return
        runCatching { dir.mkdirs() }
        observer = object : FileObserver(dir, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != stateFile.name) return
                val focused = runCatching { stateFile.readText().trim() == "1" }.getOrDefault(false)
                handler.removeCallbacks(hideRunnable)
                if (focused) {
                    handler.post { setImeShown(true) }
                } else {
                    // 入力欄から入力欄へ移るときは out→in が連続するので、少し待ってから隠す
                    handler.postDelayed(hideRunnable, 300)
                }
            }
        }.also { it.startWatching() }
        Log.i("FB", "ime-bridge: watching ${stateFile.absolutePath}")
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
        handler.removeCallbacks(hideRunnable)
    }

    private fun setImeShown(show: Boolean) {
        val activity = MainActivity.getInstance() ?: return
        Log.i("FB", "ime-bridge: setImeShown($show)")
        activity.runOnUiThread {
            val view = activity.lorieView ?: return@runOnUiThread
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (show) {
                view.requestFocus()
                imm.showSoftInput(view, 0)
            } else {
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }
}
