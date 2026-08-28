package io.github.hatake716.fullbrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import com.termux.x11.EmbeddedX11Display
import java.io.File
import java.util.UUID

/**
 * X サーバ (XServerService, :x11 プロセス) の起動・停止と、ビューアへの接続。
 *
 * 起動フロー: start() で FGS を上げ、awaitSocket() でソケット出現を待ち、proot セッション開始後に
 * openViewer() で bind → ICmdEntryInterface の Binder を com.termux.x11.MainActivity (ビューア) に
 * 渡して LorieView を接続する。世代 (generation) はサービスの状態ファイルと突き合わせ、
 * 死んだ/古いサーバへの接続を防ぐ (LDFA の EmbeddedX11ServiceController の簡約版)。
 */
interface XServerController {
    val display: Int
    fun start(tmpDir: File, xkbConfigRoot: File)
    fun stop()
    fun isRunning(): Boolean
    fun openViewer(context: Context): Boolean
    fun closeViewer(context: Context)
    fun socketFile(tmpDir: File): File = File(tmpDir, ".X11-unix/X$display")

    /** ソケットが現れるまで待つ (最大 timeoutMs) */
    fun awaitSocket(tmpDir: File, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val f = socketFile(tmpDir)
        while (System.currentTimeMillis() < deadline) {
            if (isSocket(f)) return true
            Thread.sleep(100)
        }
        return isSocket(f)
    }

    fun isSocket(f: File): Boolean =
        runCatching { OsConstants.S_ISSOCK(Os.stat(f.absolutePath).st_mode) }.getOrDefault(false)
}

class LorieXServerController(private val context: Context, override val display: Int = 0) : XServerController {
    private var generation: String? = null
    private var tmpDir: File? = null

    override fun isRunning(): Boolean {
        val state = serviceState() ?: return false
        val socket = tmpDir?.let { socketFile(it) } ?: return false
        return isPidAlive(state.pid) && isSocket(socket)
    }

    override fun start(tmpDir: File, xkbConfigRoot: File) {
        this.tmpDir = tmpDir
        // アプリプロセスだけが死んで :x11 が生きている場合は、その世代をそのまま引き継ぐ
        val state = serviceState()
        if (state != null && isPidAlive(state.pid) && isSocket(socketFile(tmpDir))) {
            generation = state.generation
            EmbeddedX11Display.restoreLaunchGeneration(state.generation)
            Log.i(App.TAG, "xserver: adopting live server pid=${state.pid}")
            return
        }
        val g = UUID.randomUUID().toString()
        generation = g
        val intent = Intent(context, XServerService::class.java).apply {
            action = XServerService.ACTION_START
            putExtra(XServerService.EXTRA_DISPLAY, display)
            putExtra(XServerService.EXTRA_TMPDIR, tmpDir.absolutePath)
            putExtra(XServerService.EXTRA_XKB_ROOT, xkbConfigRoot.absolutePath)
            putExtra(XServerService.EXTRA_GENERATION, g)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop() {
        context.stopService(Intent(context, XServerService::class.java))
        // stopService 後もプロセスが残るケースに備えて SIGTERM (kill は自 uid のプロセスにのみ届く)
        serviceState()?.let { state ->
            if (isPidAlive(state.pid)) runCatching { Os.kill(state.pid, OsConstants.SIGTERM) }
        }
        generation = null
    }

    /** サービスに bind して Binder をビューアへ注入する。準備未完なら false */
    override fun openViewer(context: Context): Boolean {
        val expected = generation ?: return false
        val state = serviceState() ?: return false
        if (state.generation != expected || !isPidAlive(state.pid)) return false
        val appContext = context.applicationContext
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val current = serviceState()
                    if (binder != null && binder.isBinderAlive && current?.generation == expected) {
                        runCatching { EmbeddedX11Display.connect(appContext, binder, expected) }
                            .onFailure { Log.e(App.TAG, "xserver: viewer launch failed", it) }
                    }
                } finally {
                    runCatching { appContext.unbindService(connection) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                runCatching { appContext.unbindService(connection) }
            }
            override fun onNullBinding(name: ComponentName?) {
                runCatching { appContext.unbindService(connection) }
            }
        }
        return runCatching {
            appContext.bindService(Intent(appContext, XServerService::class.java), connection, 0)
        }.getOrDefault(false)
    }

    override fun closeViewer(context: Context) {
        runCatching { EmbeddedX11Display.close(context.applicationContext) }
    }

    private data class ServiceState(val pid: Int, val generation: String)

    private fun serviceState(): ServiceState? = runCatching {
        val lines = File(context.filesDir, XServerService.STATE_FILE).readLines()
        val pid = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return@runCatching null
        val g = lines.getOrNull(1)?.trim().orEmpty()
        if (g.isBlank()) null else ServiceState(pid, g)
    }.getOrNull()

    private fun isPidAlive(pid: Int): Boolean =
        runCatching { Os.kill(pid, 0); true }.getOrDefault(false)
}
