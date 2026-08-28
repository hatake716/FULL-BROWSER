package io.github.hatake716.fullbrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

/**
 * 設定画面 (docs/HANDOVER.md Phase 3-2)。
 * 既定ブラウザ / 表示倍率 / タッチ操作 / 省メモリ / 常駐 / 互換モード / ホームページ /
 * ブラウザの追加・更新・削除 / ダウンロードの書き出し (SAF)。
 * 倍率などゲスト側の値は次回セッション開始時に /root/.config/fullbrowser/env へ反映される。
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var rootfs: RootfsManager
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootfs = RootfsManager(this)
        prefs = Prefs(this)
        setContent { FbTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        val sessionState by SessionService.state.collectAsState()
        val sessionBusy = sessionState is SessionService.State.Starting || sessionState is SessionService.State.Running

        var defaultBrowser by remember { mutableStateOf(prefs.defaultBrowser) }
        var autoScale by remember { mutableStateOf(prefs.scale == 0f) }
        var scale by remember { mutableStateOf(if (prefs.scale == 0f) prefs.effectiveScale() else prefs.scale) }
        var touchMode by remember { mutableStateOf(prefs.touchMode) }
        var lowMemory by remember { mutableStateOf(prefs.lowMemory) }
        var keepWarm by remember { mutableStateOf(prefs.keepWarm) }
        var noSeccomp by remember { mutableStateOf(prefs.noSeccomp) }
        var homepage by remember { mutableStateOf(prefs.homepage) }
        var working by remember { mutableStateOf<String?>(null) }
        var resultMessage by remember { mutableStateOf<String?>(null) }
        var confirmDelete by remember { mutableStateOf<Browser?>(null) }
        var installedTick by remember { mutableStateOf(0) }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            if (uri != null) scope.launch {
                working = getString(R.string.settings_exporting)
                val n = withContext(Dispatchers.IO) { exportDownloads(uri) }
                working = null
                resultMessage = getString(R.string.settings_export_done, n)
            }
        }

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_note_next_session), style = MaterialTheme.typography.bodySmall)

            // 実行中のセッションがあれば、その場で作り直して設定を反映できる
            val runningBrowser = (sessionState as? SessionService.State.Running)?.browser
            if (runningBrowser != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    SessionService.restart(this@SettingsActivity, runningBrowser)
                    // ビューアのタスクから開かれた場合もあるので、再接続を担う MainActivity を
                    // 明示的に前面へ (Running 遷移で openViewer が走り、ビューアが再び前面に出る)
                    startActivity(
                        Intent(this@SettingsActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    finish()
                }) { Text(stringResource(R.string.settings_apply_restart)) }
            }

            working?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            resultMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }

            // ---- 既定のブラウザ -------------------------------------------------
            SectionTitle(R.string.settings_default_browser)
            val ready = remember(installedTick) { rootfs.readyBrowsers() }
            if (ready.isEmpty()) {
                Text(stringResource(R.string.settings_no_browser), style = MaterialTheme.typography.bodyMedium)
            }
            for (b in ready) {
                Row(
                    Modifier.fillMaxWidth().clickable { defaultBrowser = b; prefs.defaultBrowser = b }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = defaultBrowser == b, onClick = { defaultBrowser = b; prefs.defaultBrowser = b })
                    Text(stringResource(b.labelRes))
                }
            }

            // ---- 表示倍率 -------------------------------------------------------
            SectionTitle(R.string.settings_scale)
            Row(
                Modifier.fillMaxWidth().clickable {
                    autoScale = true; prefs.scale = 0f; scale = prefs.effectiveScale()
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = autoScale, onClick = { autoScale = true; prefs.scale = 0f; scale = prefs.effectiveScale() })
                Text(stringResource(R.string.settings_scale_auto, prefs.effectiveScale().toString()))
            }
            Row(
                Modifier.fillMaxWidth().clickable { autoScale = false; prefs.scale = scale }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = !autoScale, onClick = { autoScale = false; prefs.scale = scale })
                Text(stringResource(R.string.settings_scale_manual, scale.toString()))
            }
            if (!autoScale) {
                Slider(
                    value = scale,
                    onValueChange = { v -> scale = (Math.round(v * 4) / 4f) },
                    onValueChangeFinished = { prefs.scale = scale },
                    valueRange = 1.0f..3.0f,
                    steps = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- タッチ操作 -----------------------------------------------------
            SectionTitle(R.string.settings_touch)
            for ((mode, label) in listOf(
                3 to R.string.touch_direct,
                2 to R.string.touch_touchpad,
                1 to R.string.touch_trackpad,
            )) {
                Row(
                    Modifier.fillMaxWidth().clickable { touchMode = mode; prefs.touchMode = mode; applyTouchModeLive(mode) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = touchMode == mode, onClick = { touchMode = mode; prefs.touchMode = mode; applyTouchModeLive(mode) })
                    Text(stringResource(label))
                }
            }

            // ---- トグル群 -------------------------------------------------------
            SectionTitle(R.string.settings_behavior)
            ToggleRow(R.string.settings_lowmem, R.string.settings_lowmem_desc, lowMemory) { lowMemory = it; prefs.lowMemory = it }
            ToggleRow(R.string.settings_keepwarm, R.string.settings_keepwarm_desc, keepWarm) { keepWarm = it; prefs.keepWarm = it }
            ToggleRow(R.string.settings_noseccomp, R.string.settings_noseccomp_desc, noSeccomp) { noSeccomp = it; prefs.noSeccomp = it }

            // ---- ホームページ ---------------------------------------------------
            SectionTitle(R.string.settings_homepage)
            OutlinedTextField(
                value = homepage,
                onValueChange = { homepage = it; prefs.homepage = it.trim() },
                placeholder = { Text("https://…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- ブラウザの管理 -------------------------------------------------
            SectionTitle(R.string.settings_browsers)
            if (sessionBusy) {
                Text(stringResource(R.string.settings_session_busy), style = MaterialTheme.typography.bodySmall)
            }
            // 管理対象は「インストール済み」基準 (supported=false へ降格した variant も削除できるように)
            for (b in Browser.entries) {
                if (b.imageVariant.isEmpty() || !rootfs.isInstalled(b.imageVariant)) continue
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(b.labelRes), Modifier.weight(1f))
                    OutlinedButton(
                        enabled = !sessionBusy && working == null,
                        onClick = {
                            scope.launch {
                                working = getString(R.string.settings_updating, getString(b.labelRes))
                                val rc = withContext(Dispatchers.IO) {
                                    rootfs.ensureRuntimeLibs()
                                    rootfs.prepareForSession(b.imageVariant, prefs)
                                    val paths = rootfs.prootPaths(b.imageVariant)
                                    ProotRunner.run(
                                        ProotCommand.updateArgv(paths, b, TimeZone.getDefault().id),
                                        ProotCommand.environment(paths, prefs.noSeccomp),
                                    )
                                }
                                working = null
                                resultMessage = if (rc == 0) getString(R.string.settings_update_done)
                                else getString(R.string.settings_update_failed, rc)
                            }
                        },
                    ) { Text(stringResource(R.string.settings_update)) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !sessionBusy && working == null,
                        onClick = { confirmDelete = b },
                    ) { Text(stringResource(R.string.settings_delete)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                startActivity(Intent(this@SettingsActivity, SetupActivity::class.java))
            }) { Text(stringResource(R.string.settings_add_browser)) }

            // ---- ダウンロードの書き出し -----------------------------------------
            SectionTitle(R.string.settings_export_downloads)
            Text(stringResource(R.string.settings_export_desc), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(enabled = working == null, onClick = { exportLauncher.launch(null) }) {
                Text(stringResource(R.string.settings_export_button))
            }
            Spacer(Modifier.height(24.dp))
        }

        confirmDelete?.let { b ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text(stringResource(R.string.settings_delete)) },
                text = { Text(stringResource(R.string.settings_delete_confirm, stringResource(b.labelRes))) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = null
                        scope.launch {
                            working = getString(R.string.settings_deleting)
                            withContext(Dispatchers.IO) { rootfs.rootfsDir(b.imageVariant).deleteRecursively() }
                            if (prefs.defaultBrowser == b) prefs.defaultBrowser = null
                            Shortcuts.update(this@SettingsActivity, rootfs)
                            if (defaultBrowser == b) defaultBrowser = null
                            installedTick++
                            working = null
                            resultMessage = getString(R.string.settings_delete_done)
                        }
                    }) { Text(stringResource(R.string.settings_delete)) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun SectionTitle(res: Int) {
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(res), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun ToggleRow(title: Int, desc: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title))
                Text(stringResource(desc), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    /**
     * タッチ方式は lorie (ビューア) 側の設定なので、実行中でも broadcast で即時反映できる。
     * lorie はプロセス既定 SharedPreferences を読み、ACTION_PREFERENCES_CHANGED で再読込する。
     */
    private fun applyTouchModeLive(mode: Int) {
        getSharedPreferences("${packageName}_preferences", MODE_PRIVATE)
            .edit().putString("touchMode", mode.toString()).apply()
        sendBroadcast(
            Intent("com.termux.x11.ACTION_PREFERENCES_CHANGED")
                .setPackage(packageName)
                .putExtra("key", "touchMode")
                .putExtra("fromBroadcast", true)
        )
    }

    /** 各ブラウザの rootfs 内 /root/Downloads を SAF で選んだフォルダへコピーする。戻り値はコピーした数 */
    private fun exportDownloads(treeUri: Uri): Int {
        val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return 0
        val dest = tree.findFile(EXPORT_DIR)?.takeIf { it.isDirectory } ?: tree.createDirectory(EXPORT_DIR) ?: return 0
        var count = 0
        for (b in rootfs.readyBrowsers()) {
            val src = File(rootfs.rootfsDir(b.imageVariant), "root/Downloads")
            val files = src.listFiles()?.filter { it.isFile } ?: continue
            for (f in files) {
                val existing = dest.findFile(f.name)
                if (existing != null && existing.length() == f.length()) continue
                existing?.delete()
                val doc = dest.createFile("application/octet-stream", f.name) ?: continue
                contentResolver.openOutputStream(doc.uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: continue
                count++
            }
        }
        return count
    }

    private companion object { const val EXPORT_DIR = "FULL-BROWSER" }
}
