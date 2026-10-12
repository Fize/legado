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
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SeekBarPreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefString
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

class WelcomeConfigFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var refreshTick by mutableIntStateOf(0)
    private val requestWelcomeImage = 221
    private val requestWelcomeImageDark = 222
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestWelcomeImage -> setCoverFromUri(PreferKey.welcomeImage, uri)
                requestWelcomeImageDark -> setCoverFromUri(PreferKey.welcomeImageDark, uri)
            }
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
                    WelcomeConfigScreen(refreshTick = refreshTick)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.welcome_style)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        refreshTick++
    }

    private data class PreferenceKeyHolder(val key: String)

    private fun onWelcomePreferenceClick(preference: PreferenceKeyHolder) {
        when (preference.key) {
            PreferKey.welcomeImage ->
                if (getPrefString(preference.key).isNullOrEmpty()) {
                    selectImage.launch {
                        requestCode = requestWelcomeImage
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
                            deleteStoredImage(getPrefString(preference.key))
                            removePref(preference.key)
                            BookCover.upDefaultCover()
                            refreshTick++
                        } else {
                            selectImage.launch {
                                requestCode = requestWelcomeImage
                                mode = HandleFileContract.IMAGE
                            }
                        }
                    }
                }

            PreferKey.welcomeImageDark ->
                if (getPrefString(preference.key).isNullOrEmpty()) {
                    selectImage.launch {
                        requestCode = requestWelcomeImageDark
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
                            deleteStoredImage(getPrefString(preference.key))
                            removePref(preference.key)
                            BookCover.upDefaultCover()
                            refreshTick++
                        } else {
                            selectImage.launch {
                                requestCode = requestWelcomeImageDark
                                mode = HandleFileContract.IMAGE
                            }
                        }
                    }
                }
        }
    }

    @Composable
    private fun WelcomeConfigScreen(@Suppress("UNUSED_PARAMETER") refreshTick: Int) {
        val welcomeImage by rememberPrefString(PreferKey.welcomeImage, "")
        val welcomeImageDark by rememberPrefString(PreferKey.welcomeImageDark, "")
        val selectImageLabel = stringResource(R.string.select_image)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "welcomeShowTime") {
                SeekBarPreferenceItem(
                    key = "welcomeShowTime",
                    default = 500,
                    min = 0,
                    max = 800,
                    title = stringResource(R.string.welcome_show_time),
                    summary = stringResource(R.string.welcome_show_time_summary),
                )
            }
            item(key = PreferKey.customWelcome) {
                SwitchPreferenceItem(
                    key = PreferKey.customWelcome,
                    default = false,
                    title = stringResource(R.string.custom_welcome),
                    summary = stringResource(R.string.custom_welcome_summary),
                )
            }
            item(key = "dayThemeCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.day))
            }
            item(key = PreferKey.welcomeImage) {
                PreferenceItem(
                    title = stringResource(R.string.background_image),
                    summary = welcomeImage.ifBlank { selectImageLabel },
                    onClick = { onWelcomePreferenceClick(PreferenceKeyHolder(PreferKey.welcomeImage)) },
                )
            }
            item(key = PreferKey.welcomeShowText) {
                SwitchPreferenceItem(
                    key = PreferKey.welcomeShowText,
                    default = true,
                    title = stringResource(R.string.show_welcome_text),
                    summary = stringResource(R.string.welcome_text),
                )
            }
            item(key = PreferKey.welcomeShowIcon) {
                SwitchPreferenceItem(
                    key = PreferKey.welcomeShowIcon,
                    default = true,
                    title = stringResource(R.string.show_icon),
                    summary = stringResource(R.string.show_default_book_icon),
                )
            }

            item(key = "nightThemeCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.night))
            }
            item(key = PreferKey.welcomeImageDark) {
                PreferenceItem(
                    title = stringResource(R.string.background_image),
                    summary = welcomeImageDark.ifBlank { selectImageLabel },
                    onClick = { onWelcomePreferenceClick(PreferenceKeyHolder(PreferKey.welcomeImageDark)) },
                )
            }
            item(key = PreferKey.welcomeShowTextDark) {
                SwitchPreferenceItem(
                    key = PreferKey.welcomeShowTextDark,
                    default = true,
                    title = stringResource(R.string.show_welcome_text),
                    summary = stringResource(R.string.welcome_text),
                )
            }
            item(key = PreferKey.welcomeShowIconDark) {
                SwitchPreferenceItem(
                    key = PreferKey.welcomeShowIconDark,
                    default = true,
                    title = stringResource(R.string.show_icon),
                    summary = stringResource(R.string.show_default_book_icon),
                )
            }
        }
    }

    private fun setCoverFromUri(preferenceKey: String, uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载图片中...")
                    val analyzeUrl = AnalyzeUrl(uri.toString())
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    val oldPath = getPrefString(preferenceKey)
                    putPrefString(preferenceKey, file.absolutePath)
                    if (oldPath != file.absolutePath) deleteStoredImage(oldPath)
                    refreshTick++
                }.onSuccess {
                    appCtx.toastOnUi("设定成功")
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
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
                val oldPath = getPrefString(preferenceKey)
                putPrefString(preferenceKey, file.absolutePath)
                if (oldPath != file.absolutePath) deleteStoredImage(oldPath)
                refreshTick++
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun deleteStoredImage(path: String?) {
        if (path.isNullOrEmpty()) return
        val file = File(path)
        val coversDir = File(requireContext().externalFiles, "covers")
        val isStoredImage = runCatching {
            file.canonicalFile.parentFile == coversDir.canonicalFile
        }.getOrDefault(false)
        if (isStoredImage) FileUtils.delete(file)
    }
}
