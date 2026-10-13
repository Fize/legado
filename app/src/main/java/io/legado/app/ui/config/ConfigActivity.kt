package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.EventBus
import io.legado.app.ui.compose.component.LegadoDropdownMenuItem
import io.legado.app.ui.compose.component.LegadoOverflowMenuButton
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.main.my.MyFragment.MyPreferenceFragment
import io.legado.app.utils.observeEvent

class ConfigActivity : BaseComposeActivity() {

    val viewModel by viewModels<ConfigViewModel>()
    private var titleResId by mutableIntStateOf(R.string.setting)
    private var currentConfigTag by mutableStateOf("")
    @PublishedApi
    internal var containerViewId: Int = View.NO_ID

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val configTag = intent.getStringExtra("configTag")
        if (configTag.isNullOrBlank()) {
            finish()
            return
        }
        currentConfigTag = configTag
        titleResId = when (configTag) {
            ConfigTag.MY_MORE -> R.string.reader_menu_more
            ConfigTag.OTHER_CONFIG -> R.string.other_setting
            ConfigTag.THEME_CONFIG -> R.string.theme_setting
            ConfigTag.BACKUP_CONFIG -> R.string.backup_restore
            ConfigTag.COVER_CONFIG -> R.string.cover_config
            ConfigTag.COVER_FONT_CONFIG -> R.string.cover_font_config
            ConfigTag.WELCOME_CONFIG -> R.string.welcome_style
            else -> R.string.setting
        }
    }

    @Composable
    override fun Content() {
        val hostId = remember {
            View.generateViewId().also { containerViewId = it }
        }
        val tag = currentConfigTag.ifBlank { intent.getStringExtra("configTag").orEmpty() }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(titleResId),
                    onNavigationClick = { finish() },
                    actions = {
                        when (tag) {
                            ConfigTag.THEME_CONFIG -> {
                                TextButton(
                                    onClick = {
                                        (supportFragmentManager.findFragmentByTag(tag)
                                            as? ThemeConfigFragment)?.toggleThemeMode()
                                    }
                                ) {
                                    Text(stringResource(R.string.theme_mode))
                                }
                            }
                            ConfigTag.BACKUP_CONFIG -> {
                                LegadoOverflowMenuButton { dismiss ->
                                    LegadoDropdownMenuItem(
                                        text = stringResource(R.string.menu_import_old_version),
                                        onClick = {
                                            dismiss()
                                            (supportFragmentManager.findFragmentByTag(tag)
                                                as? BackupConfigFragment)?.launchImportOld()
                                        }
                                    )
                                    LegadoDropdownMenuItem(
                                        text = stringResource(R.string.log),
                                        onClick = {
                                            dismiss()
                                            (supportFragmentManager.findFragmentByTag(tag)
                                                as? BackupConfigFragment)?.showAppLog()
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            id = hostId
                            when (tag) {
                                ConfigTag.MY_MORE -> replaceFragment<MyPreferenceFragment>(tag)
                                ConfigTag.OTHER_CONFIG -> replaceFragment<OtherConfigFragment>(tag)
                                ConfigTag.THEME_CONFIG -> replaceFragment<ThemeConfigFragment>(tag)
                                ConfigTag.BACKUP_CONFIG -> replaceFragment<BackupConfigFragment>(tag)
                                ConfigTag.COVER_CONFIG -> replaceFragment<CoverConfigFragment>(tag)
                                ConfigTag.COVER_FONT_CONFIG -> replaceFragment<CoverFontConfigFragment>(tag)
                                ConfigTag.WELCOME_CONFIG -> replaceFragment<WelcomeConfigFragment>(tag)
                                else -> finish()
                            }
                        }
                    }
                )
            }
        }
    }

    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        titleResId = resId
    }

    inline fun <reified T : Fragment> replaceFragment(configTag: String) {
        intent.putExtra("configTag", configTag)
        if (containerViewId == View.NO_ID) return
        @Suppress("DEPRECATION")
        val configFragment = supportFragmentManager.findFragmentByTag(configTag)
            ?: T::class.java.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(containerViewId, configFragment, configTag)
            .commitAllowingStateLoss()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
    }
}

internal fun configPreferenceMatches(
    query: String,
    title: CharSequence?,
    summary: CharSequence?,
    categories: List<CharSequence>,
): Boolean {
    if (query.isBlank()) return false
    return title?.contains(query, ignoreCase = true) == true ||
            summary?.contains(query, ignoreCase = true) == true ||
            categories.any { it.contains(query, ignoreCase = true) }
}
