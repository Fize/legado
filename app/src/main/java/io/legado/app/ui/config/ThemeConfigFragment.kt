package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt

@Suppress("SameParameterValue")
class ThemeConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_theme)
        if (Build.VERSION.SDK_INT < 26) {
            preferenceScreen.removePreferenceRecursively(PreferKey.launcherIcon)
        }
        if (!WallpaperTheme.isAvailable()) {
            preferenceScreen.removePreferenceRecursively(PreferKey.wallpaperColorFollow)
            preferenceScreen.removePreferenceRecursively(PreferKey.wallpaperColorAutoUpdate)
        }
        upPreferenceSummary(PreferKey.fontScale)
        findPreference<SwitchPreference>(PreferKey.wallpaperColorFollow)
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val autoUpdate = getPrefBoolean(PreferKey.wallpaperColorAutoUpdate, true)
                if (!WallpaperTheme.setFollow(requireContext(), enabled, autoUpdate)) {
                    toastOnUi(R.string.wallpaper_colors_unavailable)
                    false
                } else {
                    true
                }
            }
        findPreference<SwitchPreference>(PreferKey.wallpaperColorAutoUpdate)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (WallpaperTheme.isFollowEnabled(requireContext())) {
                    val updated = WallpaperTheme.setFollow(
                        requireContext(),
                        enabled = true,
                        autoUpdate = newValue as Boolean,
                    )
                    if (!updated) {
                        toastOnUi(R.string.wallpaper_colors_unavailable)
                        return@setOnPreferenceChangeListener false
                    }
                }
                true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.theme_config, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_theme_mode -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                ThemeConfig.applyDayNight(requireContext())
                return true
            }
        }
        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(getPrefString(key))
            PreferKey.wallpaperColorFollow ->
                findPreference<SwitchPreference>(PreferKey.wallpaperColorFollow)?.isChecked =
                    WallpaperTheme.isFollowEnabled(requireContext())
            PreferKey.transparentStatusBar,
            PreferKey.immNavigationBar,
            PreferKey.disablePredictiveBack -> recreateActivities()
        }
    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
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
        return super.onPreferenceTreeClick(preference)
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String? = null) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.fontScale -> {
                val fontScale = AppContextWrapper.getFontScale(requireContext())
                preference.summary = getString(R.string.font_scale_summary, fontScale)
            }

            else -> preference.summary = value
        }
    }

}
