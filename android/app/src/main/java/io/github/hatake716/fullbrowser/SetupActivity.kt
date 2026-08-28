package io.github.hatake716.fullbrowser

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * 初回ウィザード: ブラウザ選択 → 容量確認 → ダウンロード/検証/展開 → (Chrome なら本体取得) → 起動。
 * 2 回目以降はアイコン長押し「ブラウザの追加・設定」から同じ画面で追加できる。
 */
class SetupActivity : ComponentActivity() {

    private sealed class Step {
        object LoadingManifest : Step()
        data class Choose(val manifest: RootfsManager.Manifest) : Step()
        data class Working(val message: String, val percent: Int?) : Step()
        data class Done(val browser: Browser) : Step()
        data class Error(val message: String, val manifest: RootfsManager.Manifest?) : Step()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootfs = RootfsManager(this)
        val prefs = Prefs(this)
        val preselect = Browser.byId(intent.getStringExtra(SessionService.EXTRA_BROWSER)) ?: Browser.FIREFOX

        setContent {
            FbTheme {   // ダークモード時に黒背景+黒文字にならないよう端末設定に追従する
                var step by remember { mutableStateOf<Step>(Step.LoadingManifest) }
                var selected by remember { mutableStateOf(preselect) }
                var acceptChromeTerms by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    step = try { Step.Choose(rootfs.fetchManifest()) } catch (e: Exception) { Step.Error(e.message ?: e.toString(), null) }
                }

                fun install(manifest: RootfsManager.Manifest) {
                    val img = manifest.images[selected.imageVariant]
                    if (img == null) { step = Step.Error("image not found: ${selected.imageVariant}", manifest); return }
                    scope.launch {
                        try {
                            step = Step.Working(getString(R.string.setup_downloading, 0), 0)
                            withContext(Dispatchers.IO) {
                                if (!rootfs.isInstalled(img.variant)) {
                                    rootfs.install(manifest, img) { p ->
                                        step = when (p) {
                                            is RootfsManager.Progress.Downloading -> Step.Working(getString(R.string.setup_downloading, p.percent), p.percent)
                                            is RootfsManager.Progress.Verifying -> Step.Working(getString(R.string.setup_verifying), null)
                                            is RootfsManager.Progress.Extracting -> Step.Working(getString(R.string.setup_extracting, p.percent), p.percent)
                                            is RootfsManager.Progress.Done -> Step.Working(getString(R.string.setup_done), 100)
                                        }
                                    }
                                }
                                if (selected.needsOnDeviceInstall && !rootfs.isBrowserReady(selected)) {
                                    step = Step.Working(getString(R.string.setup_chrome_fetch), null)
                                    rootfs.ensureRuntimeLibs()
                                    rootfs.prepareForSession(img.variant, prefs)
                                    val paths = rootfs.prootPaths(img.variant)
                                    val rc = ProotRunner.run(
                                        ProotCommand.installChromeArgv(paths, TimeZone.getDefault().id),
                                        ProotCommand.environment(paths, prefs.noSeccomp),
                                    )
                                    if (rc != 0 || !rootfs.isBrowserReady(selected)) throw IllegalStateException("fb-install-chrome rc=$rc")
                                }
                            }
                            if (prefs.defaultBrowser == null || !rootfs.isBrowserReady(prefs.defaultBrowser!!)) prefs.defaultBrowser = selected
                            Shortcuts.update(this@SetupActivity, rootfs)
                            step = Step.Done(selected)
                        } catch (e: RootfsManager.NotEnoughSpace) {
                            step = Step.Error(getString(R.string.setup_no_space, Formatter.formatShortFileSize(this@SetupActivity, e.neededBytes)), manifest)
                        } catch (e: Exception) {
                            step = Step.Error(e.message ?: e.toString(), manifest)
                        }
                    }
                }

                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    when (val s = step) {
                        Step.LoadingManifest -> Text(stringResource(R.string.setup_manifest_loading))
                        is Step.Choose -> ChooseScreen(
                            manifest = s.manifest, selected = selected, onSelect = { selected = it },
                            acceptChromeTerms = acceptChromeTerms, onAcceptChange = { acceptChromeTerms = it },
                            ready = { rootfs.isBrowserReady(it) },
                            onInstall = { install(s.manifest) },
                        )
                        is Step.Working -> {
                            Text(s.message, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(16.dp))
                            if (s.percent != null) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is Step.Done -> {
                            Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                startActivity(Intent(this@SetupActivity, MainActivity::class.java).putExtra(SessionService.EXTRA_BROWSER, s.browser.id))
                                finish()
                            }) { Text(stringResource(R.string.setup_launch)) }
                        }
                        is Step.Error -> {
                            Text(stringResource(R.string.setup_error, s.message))
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { step = s.manifest?.let { Step.Choose(it) } ?: Step.LoadingManifest }) {
                                Text(stringResource(R.string.setup_retry))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChooseScreen(
        manifest: RootfsManager.Manifest,
        selected: Browser,
        onSelect: (Browser) -> Unit,
        acceptChromeTerms: Boolean,
        onAcceptChange: (Boolean) -> Unit,
        ready: (Browser) -> Boolean,
        onInstall: () -> Unit,
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        for (b in Browser.entries) {
            val img = manifest.images[b.imageVariant]
            Row(
                Modifier.fillMaxWidth().clickable(enabled = b.supported) { onSelect(b) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == b, onClick = { onSelect(b) }, enabled = b.supported)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(stringResource(b.labelRes) + if (ready(b)) " ✓" else "", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(b.descRes), style = MaterialTheme.typography.bodyMedium)
                    if (b.supported && img != null) {
                        val dl = Formatter.formatShortFileSize(this@SetupActivity, img.size)
                        val ex = Formatter.formatShortFileSize(this@SetupActivity, img.extracted)
                        Text(stringResource(R.string.setup_size, dl, ex), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (selected == Browser.CHROME) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = acceptChromeTerms, onCheckedChange = onAcceptChange)
                Text(stringResource(R.string.setup_chrome_terms))
            }
            Text("https://www.google.com/chrome/terms/", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onInstall, enabled = selected.supported && (selected != Browser.CHROME || acceptChromeTerms)) {
            Text(stringResource(R.string.setup_install))
        }
    }
}
