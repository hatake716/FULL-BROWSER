package io.github.hatake716.fullbrowser

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 端末のダーク/ライト設定に追従する共通テーマ。
 * - MaterialTheme{} を素で使うとライト配色固定になり、ダークモードで
 *   「黒背景に黒文字」になる (SetupActivity で発生した不具合)
 * - targetSdk 35+ は edge-to-edge が強制され、コンテンツがステータスバーや
 *   カメラカットアウトの下に潜るため、safeDrawing インセットで避ける
 */
@Composable
fun FbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                content()
            }
        }
    }
}
