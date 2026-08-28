package io.github.hatake716.fullbrowser

import android.util.Log
import kotlin.concurrent.thread

/** proot を起動して終了を待つ薄いラッパ。セッション以外の単発コマンド (Chrome 取得, ブラウザ更新) に使う。 */
object ProotRunner {
    class Handle(val process: Process)

    fun start(argv: List<String>, env: Map<String, String>, onLine: (String) -> Unit): Handle {
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.environment().apply {
            remove("LD_PRELOAD")
            putAll(env)
        }
        Log.i(App.TAG, "proot: ${argv.joinToString(" ")}")
        val p = pb.start()
        thread(name = "proot-log", isDaemon = true) {
            p.inputStream.bufferedReader().forEachLine { line ->
                Log.i(App.TAG, "guest: $line")
                onLine(line)
            }
        }
        return Handle(p)
    }

    /** 単発コマンドを実行して終了コードを返す (ブロッキング。IO スレッドで呼ぶこと) */
    fun run(argv: List<String>, env: Map<String, String>, onLine: (String) -> Unit = {}): Int =
        start(argv, env, onLine).process.waitFor()
}
