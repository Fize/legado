package io.legado.app.ui.config

import android.content.Context
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.model.BookCover
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.prefs.CoverPreviewItem
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.compose.prefs.rememberPrefInt
import io.legado.app.ui.compose.prefs.rememberPrefString
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.font.installFontFile
import io.legado.app.utils.FileDoc
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.openInputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val coverFontSettingKeys = setOf(
    PreferKey.coverTitleLargeSize,
    PreferKey.coverTitleSmallSize,
    PreferKey.coverAuthorLargeSize,
    PreferKey.coverAuthorSmallSize,
    PreferKey.coverHorizontal,
    PreferKey.coverTitleAdaptive,
    PreferKey.coverKeepPunctuation,
    PreferKey.coverFont,
    PreferKey.coverCustomFontSize,
)

@Composable
fun CoverFontSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFontPicker by remember { mutableStateOf(false) }
    var fontFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var fontSizeKey by remember { mutableStateOf<String?>(null) }
    val fontPath by rememberPrefString(PreferKey.coverFont, "")
    val fontDirectory = remember(context) { File(context.externalFiles, "font") }

    fun refreshFonts() {
        scope.launch {
            fontFiles = withContext(Dispatchers.IO) {
                fontDirectory.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
                    .sortedBy { it.name.lowercase() }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(fontDirectory) { refreshFonts() }

    DisposableEffect(context) {
        val preferences = context.defaultSharedPreferences
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in coverFontSettingKeys) {
                BookCover.upDefaultCover()
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val importFont = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = FileDoc.fromUri(uri, false)
                    source.openInputStream().getOrThrow().use { input ->
                        installFontFile(input, source.name, fontDirectory) { file ->
                            runCatching {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    Typeface.Builder(file).build()
                                } else {
                                    Typeface.createFromFile(file)
                                }
                            }.isSuccess
                        }
                    }
                }
            }.onSuccess { installed ->
                context.putPrefString(PreferKey.coverFont, installed.absolutePath)
                context.toastOnUi(R.string.success)
                refreshFonts()
            }.onFailure { error ->
                context.toastOnUi(
                    if (error is IllegalArgumentException) R.string.wrong_format
                    else R.string.error_read_file
                )
            }
        }
    }

    val customFontSize by rememberPrefBoolean(PreferKey.coverCustomFontSize, false)
    val titleLargeSize by rememberPrefInt(PreferKey.coverTitleLargeSize, 100)
    val titleSmallSize by rememberPrefInt(PreferKey.coverTitleSmallSize, 100)
    val authorLargeSize by rememberPrefInt(PreferKey.coverAuthorLargeSize, 100)
    val authorSmallSize by rememberPrefInt(PreferKey.coverAuthorSmallSize, 100)
    val importTitle = stringResource(R.string.import_str)
    val defaultFontName = stringResource(R.string.default_font)
    val fontSummary = fontPath.takeIf(String::isNotBlank)?.let { File(it).name } ?: defaultFontName
    val titleLargeLabel = stringResource(R.string.cover_title_large_size)
    val titleSmallLabel = stringResource(R.string.cover_title_small_size)
    val authorLargeLabel = stringResource(R.string.cover_author_large_size)
    val authorSmallLabel = stringResource(R.string.cover_author_small_size)

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.cover_font_config),
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = PreferKey.coverHorizontal) {
                SwitchPreferenceItem(
                    key = PreferKey.coverHorizontal,
                    default = false,
                    title = stringResource(R.string.cover_horizontal),
                    summary = stringResource(R.string.cover_horizontal_summary),
                )
            }
            item(key = PreferKey.coverTitleAdaptive) {
                SwitchPreferenceItem(
                    key = PreferKey.coverTitleAdaptive,
                    default = true,
                    title = stringResource(R.string.cover_title_adaptive),
                    summary = stringResource(R.string.cover_title_adaptive_summary),
                )
            }
            item(key = PreferKey.coverKeepPunctuation) {
                SwitchPreferenceItem(
                    key = PreferKey.coverKeepPunctuation,
                    default = false,
                    title = stringResource(R.string.cover_keep_punctuation),
                    summary = stringResource(R.string.cover_keep_punctuation_summary),
                )
            }
            item(key = PreferKey.coverFont) {
                PreferenceItem(
                    title = stringResource(R.string.cover_font_select),
                    summary = fontSummary,
                    onClick = { showFontPicker = true },
                )
            }
            item(key = PreferKey.coverCustomFontSize) {
                SwitchPreferenceItem(
                    key = PreferKey.coverCustomFontSize,
                    default = false,
                    title = stringResource(R.string.cover_custom_font_size),
                    summary = stringResource(R.string.cover_custom_font_size_summary),
                )
            }
            item(key = PreferKey.coverTitleLargeSize) {
                PreferenceItem(
                    title = titleLargeLabel,
                    summary = "${titleLargeSize.coerceIn(50, 200)}%",
                    enabled = customFontSize,
                    onClick = { fontSizeKey = PreferKey.coverTitleLargeSize },
                )
            }
            item(key = PreferKey.coverTitleSmallSize) {
                PreferenceItem(
                    title = titleSmallLabel,
                    summary = "${titleSmallSize.coerceIn(50, 200)}%",
                    enabled = customFontSize,
                    onClick = { fontSizeKey = PreferKey.coverTitleSmallSize },
                )
            }
            item(key = PreferKey.coverAuthorLargeSize) {
                PreferenceItem(
                    title = authorLargeLabel,
                    summary = "${authorLargeSize.coerceIn(50, 200)}%",
                    enabled = customFontSize,
                    onClick = { fontSizeKey = PreferKey.coverAuthorLargeSize },
                )
            }
            item(key = PreferKey.coverAuthorSmallSize) {
                PreferenceItem(
                    title = authorSmallLabel,
                    summary = "${authorSmallSize.coerceIn(50, 200)}%",
                    enabled = customFontSize,
                    onClick = { fontSizeKey = PreferKey.coverAuthorSmallSize },
                )
            }
            item(key = "coverPreview") {
                CoverPreviewItem()
            }
        }
    }

    if (showFontPicker) {
        AlertDialog(
            onDismissRequest = { showFontPicker = false },
            title = { Text(stringResource(R.string.select_font)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(fontFiles, key = File::getAbsolutePath) { file ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    context.putPrefString(PreferKey.coverFont, file.absolutePath)
                                    showFontPicker = false
                                },
                            ) {
                                Text(file.name, fontFamily = remember(file) {
                                    FontFamily(runCatching { Typeface.createFromFile(file) }.getOrDefault(Typeface.DEFAULT))
                                })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.putPrefString(PreferKey.coverFont, "")
                    showFontPicker = false
                }) { Text(stringResource(R.string.default_font)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    importFont.launch {
                        mode = HandleFileContract.FILE
                        title = importTitle
                    }
                }) { Text(stringResource(R.string.import_str)) }
            },
        )
    }

    fontSizeKey?.let { key ->
        val title = when (key) {
            PreferKey.coverTitleLargeSize -> titleLargeLabel
            PreferKey.coverTitleSmallSize -> titleSmallLabel
            PreferKey.coverAuthorLargeSize -> authorLargeLabel
            else -> authorSmallLabel
        }
        CoverFontSizeDialog(
            title = title,
            initialValue = context.getPrefInt(key, 100).coerceIn(50, 200),
            onDismiss = { fontSizeKey = null },
            onSave = { value ->
                context.putPrefInt(key, value)
                fontSizeKey = null
            },
            onReset = {
                context.putPrefInt(key, 100)
                fontSizeKey = null
            },
        )
    }
}

@Composable
private fun CoverFontSizeDialog(
    title: String,
    initialValue: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onReset: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("${value.toInt()}%")
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 50f..200f,
                    steps = 14,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.toInt()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.btn_default_s)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
