package io.legado.app.ui.config

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import kotlin.math.roundToInt

@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit,
    onOpenCoverSettings: () -> Unit,
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var showFontScaleDialog by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val preferences = context.defaultSharedPreferences
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            refreshTick++
            when (key) {
                PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(context.getPrefString(key))
                PreferKey.transparentStatusBar,
                PreferKey.immNavigationBar,
                PreferKey.disablePredictiveBack -> postEvent(EventBus.RECREATE, "")
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.theme_setting),
                onBack = onBack,
                actions = {
                    TextButton(onClick = {
                        AppConfig.isNightTheme = !AppConfig.isNightTheme
                        ThemeConfig.applyDayNight(context)
                    }) {
                        Text(stringResource(R.string.theme_mode))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ThemeSettingsContent(
                refreshTick = refreshTick,
                onPreferenceClick = { key ->
                    when (key) {
                        PreferKey.fontScale -> showFontScaleDialog = true
                        "coverConfig" -> onOpenCoverSettings()
                    }
                },
            )
        }
    }

    if (showFontScaleDialog) {
        FontScaleDialog(
            currentScale = AppContextWrapper.getFontScale(context),
            onDismiss = { showFontScaleDialog = false },
            onSave = { scale ->
                context.putPrefInt(PreferKey.fontScale, (scale * 10).roundToInt())
                postEvent(EventBus.RECREATE, "")
                showFontScaleDialog = false
            },
            onReset = {
                context.putPrefInt(PreferKey.fontScale, 0)
                postEvent(EventBus.RECREATE, "")
                showFontScaleDialog = false
            },
        )
    }
}

@Composable
private fun FontScaleDialog(
    currentScale: Float,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
    onReset: () -> Unit,
) {
    var scale by remember(currentScale) { mutableFloatStateOf(currentScale.coerceIn(0.8f, 1.6f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.font_scale)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.font_scale_summary, scale))
                Slider(
                    value = scale,
                    onValueChange = { scale = it },
                    valueRange = 0.8f..1.6f,
                    steps = 7,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(scale) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.btn_default_s))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
