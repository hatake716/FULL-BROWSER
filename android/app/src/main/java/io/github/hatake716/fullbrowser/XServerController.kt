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
    fun viewerOpen(): Boolean
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

    /**
     * @Synchronized: ブラウザ切替時は複数の runSession が並行に呼ぶため直列化する。
     * さらに起動要求後は「自分の世代が状態ファイルに公開されたか」を検証し、
     * 競合 (別世代のサーバが上がった/起動途中のサーバに intent を無視された) なら
     * 相手を止めて再試行する。これで並行 start は必ず最後の要求に収束する。
     */
    @Synchronized
    override fun start(tmpDir: File, xkbConfigRoot: File) {
        this.tmpDir = tmpDir
        // 引き継ぎ (keepWarm) の条件は 3 点セットで検証する:
        //  1. 状態ファイルの tmpDir が今回の variant と一致 (X は前回 variant の tmp に束縛される)
        //  2. 記録された pid が生存 (ただし PID は再利用されるので 3 が本命)
        //  3. ソケットへ実際に connect できる (ファイルの存在は死んだサーバでも残る)
        val adopt = serviceState()
        if (adopt != null && isPidAlive(adopt.pid) &&
            adopt.tmpDir == tmpDir.absolutePath && isSocketLive(socketFile(tmpDir))
        ) {
            generation = adopt.generation
            EmbeddedX11Display.restoreLaunchGeneration(adopt.generation)
            Log.i(App.TAG, "xserver: adopting live server pid=${adopt.pid}")
            return
        }
        repeat(3) { attempt ->
            stopCurrentServer()
            // 死んだサーバの残骸 (ソケット/ロック/状態) を awaitSocket が
            // 「起動済み」と誤認しないよう、サービス起動前にここ (アプリプロセス) で消す
            runCatching { socketFile(tmpDir).delete() }
            runCatching { File(tmpDir, ".X$display-lock").delete() }
            runCatching { File(context.filesDir, XServerService.STATE_FILE).delete() }
            val g = UUID.randomUUID().toString()
            generation = g
            Log.i(App.TAG, "xserver: fresh start gen=$g tmp=${tmpDir.absolutePath} attempt=$attempt")
            val intent = Intent(context, XServerService::class.java).apply {
                action = XServerService.ACTION_START
                putExtra(XServerService.EXTRA_DISPLAY, display)
                putExtra(XServerService.EXTRA_TMPDIR, tmpDir.absolutePath)
                putExtra(XServerService.EXTRA_XKB_ROOT, xkbConfigRoot.absolutePath)
                putExtra(XServerService.EXTRA_GENERATION, g)
            }
            ContextCompat.startForegroundService(context, intent)
            if (awaitPublished(g, 5_000)) return
            Log.w(App.TAG, "xserver: generation $g not published (attempt=$attempt), retrying")
        }
        Log.e(App.TAG, "xserver: could not obtain a server for ${tmpDir.absolutePath}")
    }

    /** 現在の X サービス/プロセスを止めて死亡を待つ */
    private fun stopCurrentServer() {
        context.stopService(Intent(context, XServerService::class.java))
        val state = serviceState() ?: return
        if (!isPidAlive(state.pid)) return
        // PID 再利用で無関係な自 uid プロセスを殺さないよう、:x11 と確認できたときだけシグナル
        if (isX11Process(state.pid)) {
            Log.i(App.TAG, "xserver: stopping old server pid=${state.pid}")
            if (!awaitPidDead(state.pid, 3_000)) {
                runCatching { Os.kill(state.pid, OsConstants.SIGTERM) }
                if (!awaitPidDead(state.pid, 2_000)) {
                    runCatching { Os.kill(state.pid, OsConstants.SIGKILL) }
                    awaitPidDead(state.pid, 2_000)
                }
            }
        }
    }

    /** 状態ファイルに自分の世代が公開されるまで待つ。別世代が現れたら即 false (競合) */
    private fun awaitPublished(g: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = serviceState()
            if (s != null) {
                if (s.generation == g) return true
                if (isPidAlive(s.pid)) return false   // 別世代のサーバが上がった (競合)
            }
            Thread.sleep(100)
        }
        return serviceState()?.generation == g
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
        var expected = generation
        if (expected == null) { Log.w(App.TAG, "openViewer: no generation"); return false }
        val state = serviceState()
        if (state == null) { Log.w(App.TAG, "openViewer: no service state file"); return false }
        if (state.generation != expected) {
            // 開始要求の競合などで世代がずれても、状態ファイルの X サーバが
            // 「この variant の tmp を配信していて実際に接続できる」なら健全なので採用する
            val t = tmpDir
            if (t != null && state.tmpDir == t.absolutePath && isPidAlive(state.pid) && isSocketLive(socketFile(t))) {
                Log.i(App.TAG, "openViewer: adopting live generation from state file")
                generation = state.generation
                EmbeddedX11Display.restoreLaunchGeneration(state.generation)
                expected = state.generation
            } else {
                Log.w(App.TAG, "openViewer: generation mismatch")
                return false
            }
        }
        if (!isPidAlive(state.pid)) { Log.w(App.TAG, "openViewer: server pid ${state.pid} dead"); return false }
        val appContext = context.applicationContext
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val current = serviceState()
                    if (binder != null && binder.isBinderAlive && current?.generation == expected) {
                        runCatching { EmbeddedX11Display.connect(appContext, binder, expected) }
                            .onSuccess { Log.i(App.TAG, "openViewer: viewer connected") }
                            .onFailure { Log.e(App.TAG, "openViewer: viewer launch failed", it) }
                    } else {
                        Log.w(App.TAG, "openViewer: stale binder/generation at connect")
                    }
                } finally {
                    runCatching { appContext.unbindService(connection) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                runCatching { appContext.unbindService(connection) }
            }
            override fun onNullBinding(name: ComponentName?) {
                Log.w(App.TAG, "openViewer: null binding")
                runCatching { appContext.unbindService(connection) }
            }
        }
        val ok = runCatching {
            appContext.bindService(Intent(appContext, XServerService::class.java), connection, 0)
        }.getOrDefault(false)
        if (!ok) Log.w(App.TAG, "openViewer: bindService returned false")
        return ok
    }

    /** ビューアが表に出ているか (接続注入済みの Activity が生きているか) */
    override fun viewerOpen(): Boolean = EmbeddedX11Display.isOpen()

    override fun closeViewer(context: Context) {
        runCatching { EmbeddedX11Display.close(context.applicationContext) }
    }

    private data class ServiceState(val pid: Int, val generation: String, val tmpDir: String)

    private fun serviceState(): ServiceState? = runCatching {
        val lines = File(context.filesDir, XServerService.STATE_FILE).readLines()
        val pid = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return@runCatching null
        val g = lines.getOrNull(1)?.trim().orEmpty()
        val t = lines.getOrNull(2)?.trim().orEmpty()
        if (g.isBlank()) null else ServiceState(pid, g, t)
    }.getOrNull()

    private fun isPidAlive(pid: Int): Boolean =
        runCatching { Os.kill(pid, 0); true }.getOrDefault(false)

    /** ソケットファイルの存在ではなく、実際に accept してもらえるかで生死を判定する */
    private fun isSocketLive(f: File): Boolean = runCatching {
        android.net.LocalSocket().use { s ->
            s.connect(android.net.LocalSocketAddress(f.absolutePath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
        }
        true
    }.getOrDefault(false)

    /** PID 再利用対策: シグナルを送ってよいのは :x11 プロセスと確認できたときだけ */
    private fun isX11Process(pid: Int): Boolean = runCatching {
        File("/proc/$pid/cmdline").readText().trim { it == '\u0000' || it.isWhitespace() } == "${context.packageName}:x11"
    }.getOrDefault(false)

    private fun awaitPidDead(pid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isPidAlive(pid)) return true
            Thread.sleep(100)
        }
        return !isPidAlive(pid)
    }
}
