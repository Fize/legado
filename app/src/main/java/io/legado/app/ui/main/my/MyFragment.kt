package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceGroup
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.prefs.NameListPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.checkAppUpdate
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.BackupConfigFragment
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.OtherConfigFragment
import io.legado.app.ui.config.ThemeConfigFragment
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.utils.LogUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isExpandedScreen
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

class MyFragment() : BaseFragment(R.layout.fragment_my_config), MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)
    private var currentDetailTag: String = ConfigTag.OTHER_CONFIG

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        currentDetailTag = savedInstanceState?.getString("currentDetailTag") ?: ConfigTag.OTHER_CONFIG
        val fragmentTag = "prefFragment"
        var preferenceFragment = childFragmentManager.findFragmentByTag(fragmentTag)
        if (preferenceFragment == null) preferenceFragment = MyPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.pre_fragment, preferenceFragment, fragmentTag).commit()
        upTwoPaneLayout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentDetailTag", currentDetailTag)
    }

    private fun upTwoPaneLayout() {
        val expanded = requireContext().isExpandedScreen
        binding.preFragment.layoutParams = binding.preFragment.layoutParams.apply {
            width = if (expanded) 360.dpToPx() else ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.paneDivider.isVisible = expanded
        binding.detailContainer.isVisible = expanded
        if (expanded) {
            if (childFragmentManager.findFragmentById(R.id.detail_fragment_container) == null) {
                openConfigDetail(currentDetailTag)
            } else {
                updateDetailTitle(currentDetailTag)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (view != null) {
            upTwoPaneLayout()
        }
    }

    fun openConfigDetail(configTag: String): Boolean {
        if (!requireContext().isExpandedScreen) return false
        currentDetailTag = configTag
        updateDetailTitle(configTag)
        val existing = childFragmentManager.findFragmentByTag("detail:$configTag")
        val detailFragment: Fragment = existing ?: when (configTag) {
            ConfigTag.BACKUP_CONFIG -> BackupConfigFragment()
            ConfigTag.THEME_CONFIG -> ThemeConfigFragment()
            ConfigTag.MY_MORE -> MyPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putString("configTag", ConfigTag.MY_MORE)
                }
            }
            else -> OtherConfigFragment()
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.detail_fragment_container, detailFragment, "detail:$configTag")
            .commitAllowingStateLoss()
        return true
    }

    private fun updateDetailTitle(configTag: String) {
        binding.tvDetailTitle.setText(
            when (configTag) {
                ConfigTag.BACKUP_CONFIG -> R.string.backup_restore
                ConfigTag.THEME_CONFIG -> R.string.theme_setting
                ConfigTag.MY_MORE -> R.string.reader_menu_more
                else -> R.string.other_setting
            }
        )
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_my, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_customize_my -> (childFragmentManager.findFragmentByTag("prefFragment")
                as? MyPreferenceFragment)?.showCustomization()
        }
    }

    /**
     * 配置
     */
    class MyPreferenceFragment : PreferenceFragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val isMore: Boolean
            get() = arguments?.getString("configTag") == ConfigTag.MY_MORE ||
                activity?.intent?.getStringExtra("configTag") == ConfigTag.MY_MORE
        private lateinit var customization: MultiSelectListPreference

        fun showCustomization() = onDisplayPreferenceDialog(customization)

        private fun applyVisibility(group: PreferenceGroup = preferenceScreen) {
            val moreItems = customization.values
            repeat(group.preferenceCount) { index ->
                val preference = group.getPreference(index)
                if (preference is PreferenceGroup) {
                    applyVisibility(preference)
                    preference.isVisible = (0 until preference.preferenceCount)
                        .any { preference.getPreference(it).isVisible }
                } else {
                    preference.isVisible = when (preference.key) {
                        PreferKey.myMoreItems -> false
                        "myMore", "exit" -> !isMore
                        else -> (preference.key in moreItems) == isMore
                    }
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_main)
            if (isMore && activity is ConfigActivity) activity?.setTitle(R.string.reader_menu_more)
            val available = mutableListOf<Preference>()
            fun collect(group: PreferenceGroup) {
                repeat(group.preferenceCount) { index ->
                    val preference = group.getPreference(index)
                    if (preference is PreferenceGroup) collect(preference)
                    else if (preference.key !in setOf("exit", "myMore")) available.add(preference)
                }
            }
            collect(preferenceScreen)
            customization = MultiSelectListPreference(requireContext()).apply {
                key = PreferKey.myMoreItems
                title = getString(R.string.customize_my)
                dialogTitle = getString(R.string.my_more_items)
                entries = available.map { it.title }.toTypedArray()
                entryValues = available.map { it.key }.toTypedArray()
                setDefaultValue(setOf("check_update", "check_beta_update"))
                isVisible = false
            }
            preferenceScreen.addPreference(customization)
            applyVisibility()
            findPreference<NameListPreference>(PreferKey.themeMode)?.let {
                it.setOnPreferenceChangeListener { _, _ ->
                    view?.post { ThemeConfig.applyDayNight(requireContext()) }
                    true
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            customization.values = preferenceManager.sharedPreferences?.getStringSet(
                PreferKey.myMoreItems,
                setOf("check_update", "check_beta_update")
            ).orEmpty()
            applyVisibility()
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.myMoreItems -> {
                    customization.values = sharedPreferences?.getStringSet(
                        key,
                        setOf("check_update", "check_beta_update")
                    ).orEmpty()
                    applyVisibility()
                }
                "recordLog" -> LogUtils.upLevel()
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                "myMore" -> {
                    if ((parentFragment as? MyFragment)?.openConfigDetail(ConfigTag.MY_MORE) != true) {
                        startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.MY_MORE) }
                    }
                }
                "check_update" -> checkAppUpdate()
                "check_beta_update" -> checkAppUpdate(beta = true)
                "bookSourceManage" -> startActivity<BookSourceActivity>()
                "replaceManage" -> startActivity<ReplaceRuleActivity>()
                "dictRuleManage" -> startActivity<DictRuleActivity>()
                "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
                "bookmark" -> startActivity<AllBookmarkActivity>()
                "setting" -> {
                    if ((parentFragment as? MyFragment)?.openConfigDetail(ConfigTag.OTHER_CONFIG) != true) {
                        startActivity<ConfigActivity> {
                            putExtra("configTag", ConfigTag.OTHER_CONFIG)
                        }
                    }
                }

                "web_dav_setting" -> {
                    if ((parentFragment as? MyFragment)?.openConfigDetail(ConfigTag.BACKUP_CONFIG) != true) {
                        startActivity<ConfigActivity> {
                            putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                        }
                    }
                }

                "theme_setting" -> {
                    if ((parentFragment as? MyFragment)?.openConfigDetail(ConfigTag.THEME_CONFIG) != true) {
                        startActivity<ConfigActivity> {
                            putExtra("configTag", ConfigTag.THEME_CONFIG)
                        }
                    }
                }

                "fileManage" -> startActivity<FileManageActivity>()
                "readRecord" -> startActivity<ReadRecordActivity>()
                "about" -> startActivity<AboutActivity>()
                "exit" -> activity?.finish()
            }
            return super.onPreferenceTreeClick(preference)
        }

    }
}

