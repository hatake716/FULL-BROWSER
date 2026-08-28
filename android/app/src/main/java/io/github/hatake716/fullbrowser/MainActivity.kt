package io.github.hatake716.fullbrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 入口。rootfs が無ければ SetupActivity、あれば SessionService を起動して X 画面 (LorieView) を全画面表示する。
 * LorieView の配置は lorie 組み込み後に行う (docs/HANDOVER.md Phase 2)。
 */
class MainActivity : ComponentActivity() {
    private lateinit var rootfs: RootfsManager
    private lateinit var prefs: Prefs

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootfs = RootfsManager(this)
        prefs = Prefs(this)
        setupFullscreen()

        val browser = resolveBrowser(intent)
        if (browser == null || !rootfs.isBrowserReady(browser)) {
            startActivity(Intent(this, SetupActivity::class.java).apply { browser?.let { putExtra(SessionService.EXTRA_BROWSER, it.id) } })
            finish()
            return
        }
        requestNotificationPermissionIfNeeded()
        SessionService.start(this, browser)

        setContent {
            val state by SessionService.state.collectAsState()
            // Running になったらビューア (com.termux.x11.MainActivity + LorieView) を前面に出す。
            // Binder 注入はサービス bind 経由 (LDFA 方式)。Activity が前面のここから呼ぶことで
            // Android 10+ のバックグラウンド Activity 起動制限に掛からない。
            androidx.compose.runtime.LaunchedEffect(state) {
                if (state is SessionService.State.Running) {
                    applyLorieViewerPrefs()
                    SessionService.controller()?.openViewer(this@MainActivity)
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                StatusOverlay(state, onRestart = { SessionService.start(this@MainActivity, browser) })
            }
        }
    }

    /**
     * lorie (ビューア) の設定を docs/ARCHITECTURE.md §4 の値で初期化する。
     * lorie はプロセス既定の SharedPreferences ("<pkg>_preferences") を読む。
     * touchMode は ListPreference なので文字列で書く。
     */
    private fun applyLorieViewerPrefs() {
        getSharedPreferences("${packageName}_preferences", MODE_PRIVATE).edit()
            .putBoolean("fullscreen", true)
            .putBoolean("hideCutout", true)
            .putString("touchMode", prefs.touchMode.toString())
            .putBoolean("Reseed", true)
            .putBoolean("showAdditionalKbd", false)
            .putString("displayResolutionMode", "native")
            .putBoolean("clipboardEnable", true)
            .apply()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val b = resolveBrowser(intent) ?: return
        if (rootfs.isBrowserReady(b)) SessionService.start(this, b)
    }

    private fun resolveBrowser(i: Intent?): Browser? =
        Browser.byId(i?.getStringExtra(SessionService.EXTRA_BROWSER))
            ?: prefs.defaultBrowser?.takeIf { rootfs.isBrowserReady(it) }
            ?: rootfs.readyBrowsers().firstOrNull()

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // ノッチの領域にはコンテンツを置かない (タブバーがカメラ穴に隠れないように)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@androidx.compose.runtime.Composable
private fun StatusOverlay(state: SessionService.State, onRestart: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (state) {
                is SessionService.State.Idle, is SessionService.State.Starting -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.main_connecting), color = Color.White, modifier = Modifier.padding(top = 16.dp))
                    Text(stringResource(R.string.main_hint), color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                }
                is SessionService.State.Running -> { /* LorieView が前面。何も描かない */ }
                is SessionService.State.Exited -> {
                    Text(stringResource(R.string.main_exited), color = Color.White)
                    Button(onClick = onRestart, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.setup_launch)) }
                }
                is SessionService.State.Failed -> {
                    Text(stringResource(R.string.setup_error, state.message), color = Color.White)
                    Button(onClick = onRestart, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.setup_retry)) }
                }
            }
        }
    }
}
