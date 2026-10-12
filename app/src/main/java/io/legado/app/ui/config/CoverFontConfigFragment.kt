package io.legado.app.ui.config

import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.model.BookCover
import io.legado.app.ui.compose.prefs.CoverPreviewItem
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.compose.prefs.rememberPrefInt
import io.legado.app.ui.compose.prefs.rememberPrefString
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.font.installFontFile
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.openInputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CoverFontConfigFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    FontSelectDialog.CallBack {

    private var refreshTick by mutableIntStateOf(0)
    private val sizes = listOf(
        PreferKey.coverTitleLargeSize,
        PreferKey.coverTitleSmallSize,
        PreferKey.coverAuthorLargeSize,
        PreferKey.coverAuthorSmallSize,
    )
    private val styleKeys = sizes + listOf(
        PreferKey.coverHorizontal,
        PreferKey.coverTitleAdaptive,
        PreferKey.coverKeepPunctuation,
        PreferKey.coverFont,
        PreferKey.coverCustomFontSize,
    )
    private var fontJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    CoverFontConfigScreen(refreshTrigger = refreshTick)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.cover_font_config)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!isAdded) return
        if (key !in styleKeys) return
        BookCover.upDefaultCover()
        refreshTick++
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    override val curFontPath: String get() = getPrefString(PreferKey.coverFont).orEmpty()
    override val selectSystemTypefaceOnDefault = false

    override fun selectFont(path: String) {
        fontJob?.cancel()
        if (path.isEmpty()) {
            putPrefString(PreferKey.coverFont, "")
            return
        }
        val directory = File(requireContext().externalFiles, "font")
        fontJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = FileDoc.fromFile(path)
                    source.openInputStream().getOrThrow().use { input ->
                        installFontFile(input, source.name, directory) {
                            runCatching { Typeface.createFromFile(it) }.isSuccess
                        }.absolutePath
                    }
                }
            }
            result.onSuccess { putPrefString(PreferKey.coverFont, it) }
                .onFailure { requireContext().toastOnUi(it.localizedMessage) }
        }
    }

    private fun showFontSizePicker(key: String, title: String) {
        NumberPickerDialog(requireContext())
            .setTitle(title)
            .setMinValue(50).setMaxValue(200)
            .setValue(getPrefInt(key, 100).coerceIn(50, 200))
            .setCustomButton(R.string.btn_default_s) { putPrefInt(key, 100) }
            .show { putPrefInt(key, it) }
    }

    @Composable
    private fun CoverFontConfigScreen(refreshTrigger: Int) {
        val customFontSize by rememberPrefBoolean(PreferKey.coverCustomFontSize, false)
        val fontPath by rememberPrefString(PreferKey.coverFont, "")
        val titleLargeSize by rememberPrefInt(PreferKey.coverTitleLargeSize, 100)
        val titleSmallSize by rememberPrefInt(PreferKey.coverTitleSmallSize, 100)
        val authorLargeSize by rememberPrefInt(PreferKey.coverAuthorLargeSize, 100)
        val authorSmallSize by rememberPrefInt(PreferKey.coverAuthorSmallSize, 100)

        val defaultFontName = stringResource(R.string.default_font)
        val fontSummary = fontPath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: defaultFontName

        val titleLargeLabel = stringResource(R.string.cover_title_large_size)
        val titleSmallLabel = stringResource(R.string.cover_title_small_size)
        val authorLargeLabel = stringResource(R.string.cover_author_large_size)
        val authorSmallLabel = stringResource(R.string.cover_author_small_size)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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
                    onClick = { showDialogFragment(FontSelectDialog()) },
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
                    onClick = { showFontSizePicker(PreferKey.coverTitleLargeSize, titleLargeLabel) },
                )
            }
            item(key = PreferKey.coverTitleSmallSize) {
                PreferenceItem(
                    title = titleSmallLabel,
                    summary = "${titleSmallSize.coerceIn(50, 200)}%",
                    enabled = customFontSize,
                    onClick = { showFontSizePicker(PreferKey.coverTitleSmallSize, titleSmallLabel) },
                )
            }
            item(key = PreferKey.coverAuthorLargeSize) {
                PreferenceItem(
                    title = authorLargeLabel,
                    summary = "${authorLargeSize.coerceIn(50, 200)}%",
                    enabled = customFontSize,
                    onClick = { showFontSizePicker(PreferKey.coverAuthorLargeSize, authorLargeLabel) },
                )
            }
            item(key = PreferKey.coverAuthorSmallSize) {
                PreferenceItem(
                    title = authorSmallLabel,
                    summary = "${authorSmallSize.coerceIn(50, 200)}%",
                    enabled = customFontSize,
                    onClick = { showFontSizePicker(PreferKey.coverAuthorSmallSize, authorSmallLabel) },
                )
            }
            item(key = "coverPreview") {
                CoverPreviewItem(refreshTrigger = refreshTrigger)
            }
        }
    }
}
