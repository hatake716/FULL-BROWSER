package io.github.hatake716.fullbrowser

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import com.termux.x11.EmbeddedX11ServerBridge
import com.termux.x11.ICmdEntryInterface
import java.io.File

/**
 * ネイティブ X サーバ (Termux:X11 lorie / libXlorie.so) を専用プロセス :x11 でホストする。
 *
 * app_process + CLASSPATH 方式 (docs/HANDOVER.md の当初案) は使わない。LDFA が Android 17 で
 * app_process 起動の失敗を踏んで v0.8 で本方式に移行し実績があるため、それに合わせる:
 *  - Xorg はプロセス全域のグローバルを持つので、専用プロセスなら onDestroy で killProcess しても安全
 *  - ビューア (com.termux.x11.MainActivity) は本サービスに bind して ICmdEntryInterface を受け取る
 *  - ソケットは TMPDIR 由来: <rootfs>/tmp/.X11-unix/X<display>。proot 内からは /tmp/.X11-unix/X<display>
 */
class XServerService : Service() {
    private var serverStarted = false
    private var display = 0

    private val connectionBinder = object : ICmdEntryInterface.Stub() {
        override fun getXConnection() = EmbeddedX11ServerBridge.getXConnection()
        override fun getLogcatOutput() = null
    }

    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: IllegalStateException) {
            // バックグラウンドからの FGS 昇格拒否 (S+)。:x11 プロセスを落とさず正常な起動失敗として扱う
            Log.e(App.TAG, "xserver: startForeground denied", e)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder = connectionBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 復旧はコントローラ側の責務。Android が古い/空の Intent で Xorg を蘇生してはならない
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val generation = intent.getStringExtra(EXTRA_GENERATION)
        val tmpDir = intent.getStringExtra(EXTRA_TMPDIR)
        val xkbRoot = intent.getStringExtra(EXTRA_XKB_ROOT)
        if (generation.isNullOrBlank() || tmpDir.isNullOrBlank() || xkbRoot.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (serverStarted) {
            // 起動済みサーバへの start は来ない設計 (引き継ぎは bind のみ)。古い Intent は無視する
            Log.w(App.TAG, "xserver: ignoring start intent on live server")
            return START_NOT_STICKY
        }
        if (!serverStarted) {
            display = intent.getIntExtra(EXTRA_DISPLAY, 0)
            try {
                writeServiceState(generation, tmpDir)
                prepareEnvironment(File(tmpDir), File(xkbRoot))
                // -noreset: 起動確認プローブ等の短命クライアント切断でソケット/ロックが
                // 作り直され、ビューアの世代検証と競合するのを防ぐ (LDFA の知見)
                val args = arrayOf(":$display", "-noreset")
                Log.i(App.TAG, "xserver: starting display=:$display tmp=$tmpDir pid=${Process.myPid()}")
                if (!EmbeddedX11ServerBridge.start(args)) {
                    Log.e(App.TAG, "xserver: native bridge rejected startup")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serverStarted = true
            } catch (t: Throwable) {
                Log.e(App.TAG, "xserver: startup failed", t)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanupEndpoint()
        super.onDestroy()
        // Xorg はプロセス全域の状態を持つネイティブスレッド。専用 :x11 プロセスごと殺すのが決定的
        Process.killProcess(Process.myPid())
    }

    private fun prepareEnvironment(tmpDir: File, xkbRoot: File) {
        val socketDir = File(tmpDir, ".X11-unix")
        tmpDir.mkdirs()
        socketDir.mkdirs()
        runCatching { Os.chmod(socketDir.absolutePath,
            "1777".toInt(8)) }

        cleanupStaleEndpoint(tmpDir)

        check(xkbRoot.isDirectory) { "XKB config missing: $xkbRoot (rootfs の xkb-data が必要)" }

        val xdgRuntime = File(filesDir, "tmp/xdg-x11")
        xdgRuntime.mkdirs()
        runCatching { Os.chmod(xdgRuntime.absolutePath, "700".toInt(8)) }

        // app_process 時代はシェルから継承していた環境変数を、通常の Service では明示的に与える
        Os.setenv("HOME", filesDir.absolutePath, true)
        Os.setenv("TMPDIR", tmpDir.absolutePath, true)
        Os.setenv("XDG_RUNTIME_DIR", xdgRuntime.absolutePath, true)
        Os.setenv("XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)
        currentTmpDir = tmpDir
    }

    private fun cleanupStaleEndpoint(tmpDir: File) {
        val lock = File(tmpDir, ".X$display-lock")
        val socket = File(File(tmpDir, ".X11-unix"), "X$display")
        if (lock.exists()) {
            val pid = lock.readText().trim().toIntOrNull()
            val alive = pid != null && runCatching { Os.kill(pid, 0); true }.getOrDefault(false)
            if (alive && pid != Process.myPid()) error("DISPLAY :$display is already owned by pid=$pid")
            lock.delete()
        }
        if (socket.exists()) socket.delete()
    }

    private fun cleanupEndpoint() {
        val tmpDir = currentTmpDir ?: return
        val lock = File(tmpDir, ".X$display-lock")
        val owner = runCatching { lock.readText().trim().toIntOrNull() }.getOrNull()
        // 遅延した旧世代の onDestroy が新世代のエンドポイントを消してはならない
        if (owner == Process.myPid()) {
            runCatching { lock.delete() }
            runCatching { File(File(tmpDir, ".X11-unix"), "X$display").delete() }
        }
        val state = File(filesDir, STATE_FILE)
        val stateOwner = runCatching { state.useLines { it.firstOrNull()?.trim()?.toIntOrNull() } }.getOrNull()
        if (stateOwner == Process.myPid()) runCatching { state.delete() }
    }

    private fun writeServiceState(generation: String, tmpDir: String) {
        val state = File(filesDir, STATE_FILE)
        val tmp = File(filesDir, ".$STATE_FILE.${Process.myPid()}")
        // 3 行目の tmpDir は引き継ぎ判定用: どの variant の tmp を配信しているかを示す
        tmp.writeText("${Process.myPid()}\n$generation\n$tmpDir\n")
        if (!tmp.renameTo(state)) {
            tmp.delete()
            error("could not publish x11 service state")
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, App.CHANNEL_SESSION)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notif_xserver))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val ACTION_START = "io.github.hatake716.fullbrowser.action.XSERVER_START"
        const val EXTRA_DISPLAY = "display"
        const val EXTRA_TMPDIR = "tmpdir"
        const val EXTRA_XKB_ROOT = "xkb_root"
        const val EXTRA_GENERATION = "generation"
        const val STATE_FILE = "xserver-service.state"
        private const val NOTIFICATION_ID = 2

        /** onDestroy の後始末用 (この :x11 プロセス内でのみ意味を持つ) */
        @Volatile private var currentTmpDir: File? = null
    }
}
