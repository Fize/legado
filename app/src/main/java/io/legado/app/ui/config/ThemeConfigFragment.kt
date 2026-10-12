package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Build
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.ui.compose.prefs.IconListPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt

@Suppress("SameParameterValue")
class ThemeConfigFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var refreshTick by mutableIntStateOf(0)

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
                    ThemeConfigScreen(
                        refreshTick = refreshTick,
                        onPreferenceClick = ::onPreferenceKeyClick,
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
    }

    fun toggleThemeMode() {
        AppConfig.isNightTheme = !AppConfig.isNightTheme
        ThemeConfig.applyDayNight(requireContext())
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        refreshTick++
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(getPrefString(key))
            PreferKey.transparentStatusBar,
            PreferKey.immNavigationBar,
            PreferKey.disablePredictiveBack -> recreateActivities()
        }
    }

    private fun onPreferenceKeyClick(key: String) {
        when (key) {
            PreferKey.fontScale -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.font_scale))
                .setMaxValue(16)
                .setMinValue(8)
                .setValue(
                    (AppContextWrapper.getFontScale(requireContext()) * 10)
                        .roundToInt()
                        .coerceIn(8, 16)
                )
                .setCustomButton((R.string.btn_default_s)) {
                    putPrefInt(PreferKey.fontScale, 0)
                    recreateActivities()
                }
                .show {
                    putPrefInt(PreferKey.fontScale, it)
                    recreateActivities()
                }

            "coverConfig" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.COVER_CONFIG)
            }

            "welcomeStyle" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.WELCOME_CONFIG)
            }
        }
    }

    @Composable
    private fun ThemeConfigScreen(
        refreshTick: Int,
        onPreferenceClick: (String) -> Unit,
    ) {
        val context = requireContext()
        val wallpaperAvailable = remember { WallpaperTheme.isAvailable() }
        val wallpaperFollow by rememberPrefBoolean(PreferKey.wallpaperColorFollow, true)
        val fontScaleSummary = remember(refreshTick) {
            val fontScale = AppContextWrapper.getFontScale(context)
            getString(R.string.font_scale_summary, fontScale)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (wallpaperAvailable) {
                item(key = PreferKey.wallpaperColorFollow) {
                    SwitchPreferenceItem(
                        key = PreferKey.wallpaperColorFollow,
                        default = true,
                        title = stringResource(R.string.wallpaper_color_follow),
                        summary = stringResource(R.string.wallpaper_color_follow_summary),
                        onValueChange = { enabled ->
                            val autoUpdate = getPrefBoolean(PreferKey.wallpaperColorAutoUpdate, true)
                            if (!WallpaperTheme.setFollow(requireContext(), enabled, autoUpdate)) {
                                toastOnUi(R.string.wallpaper_colors_unavailable)
                                false
                            } else {
                                true
                            }
                        }
                    )
                }
                item(key = PreferKey.wallpaperColorAutoUpdate) {
                    SwitchPreferenceItem(
                        key = PreferKey.wallpaperColorAutoUpdate,
                        default = true,
                        title = stringResource(R.string.wallpaper_color_auto_update),
                        summary = stringResource(R.string.wallpaper_color_auto_update_summary),
                        enabled = wallpaperFollow,
                        onValueChange = { next ->
                            if (WallpaperTheme.isFollowEnabled(requireContext())) {
                                val updated = WallpaperTheme.setFollow(
                                    requireContext(),
                                    enabled = true,
                                    autoUpdate = next,
                                )
                                if (!updated) {
                                    toastOnUi(R.string.wallpaper_colors_unavailable)
                                    return@SwitchPreferenceItem false
                                }
                            }
                            true
                        }
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= 26) {
                item(key = PreferKey.launcherIcon) {
                    val iconEntries = stringArrayResource(R.array.icon_names)
                    val iconValues = stringArrayResource(R.array.icons)
                    IconListPreferenceItem(
                        key = PreferKey.launcherIcon,
                        default = "ic_launcher",
                        title = stringResource(R.string.change_icon),
                        summary = stringResource(R.string.change_icon_summary),
                        entries = iconEntries,
                        entryValues = iconValues,
                        iconNames = iconValues,
                    )
                }
            }

            item(key = "welcomeStyle") {
                PreferenceItem(
                    title = stringResource(R.string.welcome_style),
                    summary = stringResource(R.string.welcome_style_summary),
                    onClick = { onPreferenceClick("welcomeStyle") },
                )
            }

            item(key = "coverConfig") {
                PreferenceItem(
                    title = stringResource(R.string.cover_config),
                    summary = stringResource(R.string.cover_config_summary),
                    onClick = { onPreferenceClick("coverConfig") },
                )
            }

            item(key = PreferKey.fontScale) {
                PreferenceItem(
                    title = stringResource(R.string.font_scale),
                    summary = fontScaleSummary,
                    onClick = { onPreferenceClick(PreferKey.fontScale) },
                )
            }

            item(key = PreferKey.transparentStatusBar) {
                SwitchPreferenceItem(
                    key = PreferKey.transparentStatusBar,
                    default = true,
                    title = stringResource(R.string.immersion_status_bar),
                    summary = stringResource(R.string.status_bar_immersion),
                )
            }

            item(key = PreferKey.immNavigationBar) {
                SwitchPreferenceItem(
                    key = PreferKey.immNavigationBar,
                    default = true,
                    title = stringResource(R.string.imm_navigation_bar),
                    summary = stringResource(R.string.imm_navigation_bar_s),
                )
            }

            item(key = PreferKey.disablePredictiveBack) {
                SwitchPreferenceItem(
                    key = PreferKey.disablePredictiveBack,
                    default = false,
                    title = stringResource(R.string.disable_predictive_back),
                    summary = stringResource(R.string.disable_predictive_back_summary),
                )
            }
        }
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }
}
