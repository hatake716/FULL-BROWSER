package io.github.hatake716.fullbrowser

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * ブラウザセッションの寿命を管理するフォアグラウンドサービス。
 * X サーバ起動 → proot (fb-session) 起動 → 終了待ち → 後始末。
 * ホームボタンで離れてもここが生きている限りブラウザは動き続ける (Play の specialUse 申告対象)。
 */
class SessionService : Service() {

    sealed class State {
        object Idle : State()
        data class Starting(val browser: Browser) : State()
        data class Running(val browser: Browser) : State()
        data class Exited(val browser: Browser, val code: Int) : State()
        data class Failed(val message: String) : State()
    }

    private lateinit var rootfs: RootfsManager
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proot: ProotRunner.Handle? = null
    private var current: Browser? = null

    /** セッション世代。ACTION_START/STOP のたびに進み、古い runSession の後始末を無効化する */
    @Volatile private var epoch = 0

    override fun onCreate() {
        super.onCreate()
        rootfs = RootfsManager(this)
        prefs = Prefs(this)
        if (xserver == null) xserver = LorieXServerController(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { epoch++; stopSession(); stopSelf() }
            ACTION_START -> {
                val browser = Browser.byId(intent.getStringExtra(EXTRA_BROWSER)) ?: return START_NOT_STICKY
                val force = intent.getBooleanExtra(EXTRA_FORCE, false)
                // Starting も「使用中」扱いにする: タスク復元で onCreate(旧 intent) と
                // onNewIntent(新 intent) が連続すると同一ブラウザの開始が 2 連発になり、
                // 起動途中の X サービスと競合して世代の握手が壊れる
                val busy = proot?.process?.isAlive == true || _state.value is State.Starting
                Log.i(App.TAG, "session: start req browser=${browser.id} force=$force busy=$busy current=${current?.id} epoch=$epoch")
                if (busy && current == browser && !force) {
                    // 既に動いている/起動中: 前面に出すだけ (MainActivity 側)
                    return START_NOT_STICKY
                }
                // 旧セッションの runSession は epoch 不一致になり、後始末で新セッションを壊さない
                epoch++
                val myEpoch = epoch
                if (busy) stopSession()
                current = browser
                _state.value = State.Starting(browser)
                startAsForeground(browser)
                scope.launch { runSession(browser, myEpoch) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(browser: Browser) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(browser),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun buildNotification(browser: Browser): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(EXTRA_BROWSER, browser.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SESSION)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notif_running, getString(browser.labelRes)))
            .setContentText(getString(R.string.main_hint))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private suspend fun runSession(browser: Browser, myEpoch: Int) {
        val variant = browser.imageVariant
        val isCurrent = { myEpoch == epoch }
        // wakelock はセッションローカルに所有する (メンバー共有だと切替時に旧セッション分がリークする)
        var wl: PowerManager.WakeLock? = null
        try {
            rootfs.ensureRuntimeLibs()
            rootfs.prepareForSession(variant, prefs)
            val xs = xserver!!
            val guestTmp = rootfs.guestTmpDir(variant)
            xs.start(guestTmp, rootfs.xkbConfigRoot(variant))
            if (!xs.awaitSocket(guestTmp)) throw IllegalStateException("X server did not start")
            if (!isCurrent()) return

            wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:session").apply { acquire() }
            val paths = rootfs.prootPaths(variant)
            val argv = ProotCommand.sessionArgv(paths, browser, TimeZone.getDefault().id, xs.display)
            val env = ProotCommand.environment(paths, prefs.noSeccomp)
            val h = ProotRunner.start(argv, env) { }
            if (!isCurrent()) { h.process.destroy(); return }
            proot = h
            _state.value = State.Running(browser)
            val rc = h.process.waitFor()
            Log.i(App.TAG, "session exited rc=$rc (epoch=$myEpoch current=${isCurrent()})")
            if (isCurrent()) _state.value = State.Exited(browser, rc)
        } catch (e: Exception) {
            Log.e(App.TAG, "session failed", e)
            if (isCurrent()) _state.value = State.Failed(e.message ?: e.toString())
        } finally {
            wl?.let { if (it.isHeld) it.release() }
            // 新しいセッションに置き換えられた (epoch 不一致) 場合、共有状態と
            // サービス自体には触れない。触ると新セッションを壊す
            if (isCurrent()) {
                proot = null
                xserver?.closeViewer(this)   // ブラウザ終了 → ビューアを閉じて MainActivity に戻す
                if (!prefs.keepWarm) xserver?.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopSession() {
        proot?.process?.let { if (it.isAlive) it.destroy() }   // --kill-on-exit で子プロセスも止まる
        proot = null
    }

    override fun onDestroy() {
        epoch++
        stopSession()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.hatake716.fullbrowser.action.START"
        const val ACTION_STOP = "io.github.hatake716.fullbrowser.action.STOP"
        const val EXTRA_BROWSER = "browser"
        const val EXTRA_FORCE = "force"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state

        /** X サーバは :x11 プロセスの FGS。keepWarm のためセッションより長生きさせる */
        @Volatile private var xserver: XServerController? = null

        /** ビューア接続 (openViewer) を前面の Activity から呼ぶための参照 */
        fun controller(): XServerController? = xserver

        fun start(context: Context, browser: Browser) {
            context.startForegroundService(
                Intent(context, SessionService::class.java).setAction(ACTION_START).putExtra(EXTRA_BROWSER, browser.id)
            )
        }

        /** 設定変更を反映するため、同じブラウザでもセッションを作り直す */
        fun restart(context: Context, browser: Browser) {
            context.startForegroundService(
                Intent(context, SessionService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_BROWSER, browser.id).putExtra(EXTRA_FORCE, true)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
