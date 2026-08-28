package io.github.hatake716.fullbrowser

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * X サーバ (Termux:X11 lorie) の起動・停止。
 *
 * 組み込み方 (docs/HANDOVER.md Phase 0/2):
 *  - external/termux-x11 の lorie モジュールをこのアプリに含めると、APK 内に com.termux.x11.CmdEntryPoint と
 *    libXlorie.so が入る。X サーバは Termux:X11 と同じく app_process の別プロセスとして動かす
 *    (chroot/proot 向け手順: CLASSPATH=<APK> app_process / com.termux.x11.CmdEntryPoint :0)。
 *  - TMPDIR は rootfs の tmp/ を指す。ソケットは <rootfs>/tmp/.X11-unix/X0 に作られ、proot 内からは /tmp/.X11-unix/X0 に見える。
 *  - XKB_CONFIG_ROOT は rootfs の /usr/share/X11/xkb (xkb-data パッケージ)。無いと xkbcomp エラーになる。
 *  - 画面側 (LorieView) は CmdEntryPoint からの Binder を受け取って Surface を渡す。Termux:X11 では
 *    ACTION_START ブロードキャストの宛先が com.termux.x11 固定なので、自アプリのパッケージ名に合わせて変更する
 *    (LDFA で実施済みの変更と同じ)。
 */
interface XServerController {
    val display: Int
    fun start(tmpDir: File, xkbConfigRoot: File)
    fun stop()
    fun isRunning(): Boolean
    fun socketFile(tmpDir: File): File = File(tmpDir, ".X11-unix/X$display")

    /** ソケットが現れるまで待つ (最大 timeoutMs) */
    fun awaitSocket(tmpDir: File, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val f = socketFile(tmpDir)
        while (System.currentTimeMillis() < deadline) {
            if (f.exists()) return true
            Thread.sleep(100)
        }
        return f.exists()
    }
}

class LorieXServerController(private val context: Context, override val display: Int = 0) : XServerController {
    private var process: Process? = null

    override fun isRunning(): Boolean = process?.isAlive == true

    override fun start(tmpDir: File, xkbConfigRoot: File) {
        if (isRunning()) return
        tmpDir.mkdirs(); File(tmpDir, ".X11-unix").mkdirs()
        val apk = context.applicationInfo.sourceDir
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val cmd = listOf(
            "/system/bin/app_process",
            "-Xnoimage-dex2oat",
            "-Djava.library.path=$nativeLibDir",
            "/",
            "--nice-name=${context.packageName}:xserver",
            "com.termux.x11.CmdEntryPoint",
            ":$display",
        )
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            put("CLASSPATH", apk)
            put("TMPDIR", tmpDir.absolutePath)
            put("XKB_CONFIG_ROOT", xkbConfigRoot.absolutePath)
            put("LD_LIBRARY_PATH", nativeLibDir)
            remove("LD_PRELOAD")
        }
        Log.i(App.TAG, "xserver start: $cmd")
        val p = pb.start()
        process = p
        thread(name = "xserver-log", isDaemon = true) {
            p.inputStream.bufferedReader().forEachLine { Log.i(App.TAG, "xserver: $it") }
            Log.i(App.TAG, "xserver exited: ${p.exitValue()}")
        }
    }

    override fun stop() {
        process?.let { if (it.isAlive) it.destroy() }
        process = null
    }
}
