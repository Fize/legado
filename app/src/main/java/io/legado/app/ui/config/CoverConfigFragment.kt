package io.legado.app.ui.config

import android.content.SharedPreferences
import android.net.Uri
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
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.BookCover
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.compose.prefs.rememberPrefString
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

class CoverConfigFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var refreshTick by mutableIntStateOf(0)
    private val coverKeys = listOf(
        PreferKey.defaultCover,
        PreferKey.defaultCoverDark,
        PreferKey.readRecordCover,
        PreferKey.readRecordCoverDark,
    )
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            it.value?.takeIf { key -> key in coverKeys }?.let { key -> setCoverFromUri(key, uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
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
                    CoverConfigScreen(refreshTick = refreshTick)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.cover_config)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        refreshTick++
        when (key) {
            PreferKey.useDefaultCover -> {
                BookCover.upDefaultCover()
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }
            PreferKey.defaultCover,
            PreferKey.defaultCoverDark,
            PreferKey.readRecordCover,
            PreferKey.readRecordCoverDark -> {
                upPreferenceSummary(key, getPrefString(key))
            }

            PreferKey.coverShowName -> {
                BookCover.upDefaultCover()
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }

            PreferKey.coverShowNameN -> {
                BookCover.upDefaultCover()
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }

            PreferKey.coverShowAuthor,
            PreferKey.coverShowAuthorN -> {
                BookCover.upDefaultCover()
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }
        }
    }

    private fun onCoverKeyClick(preferenceKey: String) {
        when (preferenceKey) {
            ConfigTag.COVER_FONT_CONFIG -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.COVER_FONT_CONFIG)
            }
            "coverRule" -> showDialogFragment(CoverRuleConfigDialog())
            in coverKeys ->
                if (getPrefString(preferenceKey).isNullOrEmpty()) {
                    selectImage.launch {
                        value = preferenceKey
                        mode = HandleFileContract.IMAGE
                    }
                } else {
                    context?.selector(
                        items = arrayListOf(
                            getString(R.string.delete),
                            getString(R.string.select_image)
                        )
                    ) { _, i ->
                        if (i == 0) {
                            removePref(preferenceKey)
                            BookCover.upDefaultCover()
                            refreshTick++
                        } else {
                            selectImage.launch {
                                value = preferenceKey
                                mode = HandleFileContract.IMAGE
                            }
                        }
                    }
                }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        refreshTick++
    }

    @Composable
    private fun CoverConfigScreen(@Suppress("UNUSED_PARAMETER") refreshTick: Int) {
        val coverShowName by rememberPrefBoolean(PreferKey.coverShowName, true)
        val coverShowNameN by rememberPrefBoolean(PreferKey.coverShowNameN, true)
        val defaultCoverPath by rememberPrefString(PreferKey.defaultCover, "")
        val defaultCoverDarkPath by rememberPrefString(PreferKey.defaultCoverDark, "")
        val readRecordCoverPath by rememberPrefString(PreferKey.readRecordCover, "")
        val readRecordCoverDarkPath by rememberPrefString(PreferKey.readRecordCoverDark, "")
        val selectImageLabel = stringResource(R.string.select_image)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = PreferKey.loadCoverOnlyWifi) {
                SwitchPreferenceItem(
                    key = PreferKey.loadCoverOnlyWifi,
                    default = false,
                    title = stringResource(R.string.only_wifi),
                    summary = stringResource(R.string.only_wifi_summary),
                )
            }
            item(key = "coverRule") {
                PreferenceItem(
                    title = stringResource(R.string.cover_rule),
                    summary = stringResource(R.string.cover_rule_summary),
                    onClick = { onCoverKeyClick("coverRule") },
                )
            }
            item(key = PreferKey.useDefaultCover) {
                SwitchPreferenceItem(
                    key = PreferKey.useDefaultCover,
                    default = false,
                    title = stringResource(R.string.use_default_cover),
                    summary = stringResource(R.string.use_default_cover_s),
                )
            }
            item(key = ConfigTag.COVER_FONT_CONFIG) {
                PreferenceItem(
                    title = stringResource(R.string.cover_font_config),
                    onClick = { onCoverKeyClick(ConfigTag.COVER_FONT_CONFIG) },
                )
            }

            item(key = "dayThemeCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.day))
            }
            item(key = PreferKey.defaultCover) {
                PreferenceItem(
                    title = stringResource(R.string.default_cover),
                    summary = defaultCoverPath.ifBlank { selectImageLabel },
                    onClick = { onCoverKeyClick(PreferKey.defaultCover) },
                )
            }
            item(key = PreferKey.coverShowName) {
                SwitchPreferenceItem(
                    key = PreferKey.coverShowName,
                    default = true,
                    title = stringResource(R.string.cover_show_name),
                    summary = stringResource(R.string.cover_show_name_summary),
                )
            }
            item(key = PreferKey.coverShowAuthor) {
                SwitchPreferenceItem(
                    key = PreferKey.coverShowAuthor,
                    default = true,
                    title = stringResource(R.string.cover_show_author),
                    summary = stringResource(R.string.cover_show_author_summary),
                    enabled = coverShowName,
                )
            }

            item(key = "nightThemeCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.night))
            }
            item(key = PreferKey.defaultCoverDark) {
                PreferenceItem(
                    title = stringResource(R.string.default_cover),
                    summary = defaultCoverDarkPath.ifBlank { selectImageLabel },
                    onClick = { onCoverKeyClick(PreferKey.defaultCoverDark) },
                )
            }
            item(key = PreferKey.coverShowNameN) {
                SwitchPreferenceItem(
                    key = PreferKey.coverShowNameN,
                    default = true,
                    title = stringResource(R.string.cover_show_name),
                    summary = stringResource(R.string.cover_show_name_summary),
                )
            }
            item(key = PreferKey.coverShowAuthorN) {
                SwitchPreferenceItem(
                    key = PreferKey.coverShowAuthorN,
                    default = true,
                    title = stringResource(R.string.cover_show_author),
                    summary = stringResource(R.string.cover_show_author_summary),
                    enabled = coverShowNameN,
                )
            }

            item(key = "readRecordCoverCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.read_record_covers))
            }
            item(key = PreferKey.readRecordCover) {
                PreferenceItem(
                    title = stringResource(R.string.read_record_cover_day),
                    summary = readRecordCoverPath.ifBlank { selectImageLabel },
                    onClick = { onCoverKeyClick(PreferKey.readRecordCover) },
                )
            }
            item(key = PreferKey.readRecordCoverDark) {
                PreferenceItem(
                    title = stringResource(R.string.read_record_cover_night),
                    summary = readRecordCoverDarkPath.ifBlank { selectImageLabel },
                    onClick = { onCoverKeyClick(PreferKey.readRecordCoverDark) },
                )
            }
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                putPrefString(preferenceKey, file.absolutePath)
                BookCover.upDefaultCover()
                refreshTick++
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
