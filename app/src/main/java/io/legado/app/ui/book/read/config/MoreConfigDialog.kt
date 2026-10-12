package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
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
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.compose.prefs.ListPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SeekBarPreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.removePref

class MoreConfigDialog : BasePrefDialogFragment() {
    private val readPreferTag = "readPreferenceFragment"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 360.dpToPx())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as ReadBookActivity).bottomDialog++
        val view = LinearLayout(context)
        view.setBackgroundColor(requireContext().bottomBackground)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readPreferTag)
            .commit()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    class ReadPreferenceFragment : Fragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val slopSquare by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }
        private var refreshTick by mutableIntStateOf(0)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (!CanvasRecorderFactory.isSupport) {
                removePref(PreferKey.optimizeRender)
            }
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
                        ReadConfigScreen(refreshTick = refreshTick)
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            requireContext().defaultSharedPreferences
                .registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            requireContext().defaultSharedPreferences
                .unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            refreshTick++
            when (key) {
                PreferKey.readBodyToLh -> activity?.recreate()
                PreferKey.hideStatusBar -> {
                    ReadBookConfig.hideStatusBar = getPrefBoolean(PreferKey.hideStatusBar)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.hideNavigationBar -> {
                    ReadBookConfig.hideNavigationBar = getPrefBoolean(PreferKey.hideNavigationBar)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.keepLight -> postEvent(key, true)
                PreferKey.textSelectAble -> postEvent(key, getPrefBoolean(key))
                PreferKey.screenOrientation -> {
                    (activity as? ReadBookActivity)?.setOrientation()
                }

                PreferKey.textFullJustify,
                PreferKey.textBottomJustify,
                PreferKey.hangingPunctuation,
                PreferKey.punctuationCompress,
                PreferKey.useZhLayout,
                PreferKey.adaptSpecialStyle -> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                }

                PreferKey.showBrightnessView -> {
                    postEvent(PreferKey.showBrightnessView, "")
                }

                PreferKey.doublePageHorizontal -> {
                    ChapterProvider.upLayout()
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5))
                }

                PreferKey.showReadTitleAddition,
                PreferKey.showReadTitleChapterNameOnly,
                PreferKey.readBarStyleFollowPage -> {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }

                PreferKey.progressBarBehavior -> {
                    postEvent(EventBus.UP_SEEK_BAR, true)
                }

                PreferKey.noAnimScrollPage -> {
                    ReadBook.callBack?.upPageAnim()
                }

                PreferKey.pullToToggleBookmark -> {
                    (activity as? ReadBookActivity)?.upBookmarkIndicator()
                }

                PreferKey.optimizeRender -> {
                    ChapterProvider.upStyle()
                    ReadBook.callBack?.upPageAnim(true)
                    ReadBook.loadContent(false)
                }

                PreferKey.paddingDisplayCutouts -> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }
            }
        }

        private data class PreferenceKeyRef(val key: String)

        private fun onPreferenceTreeClick(preference: PreferenceKeyRef): Boolean {
            when (preference.key) {
                "customPageKey" -> PageKeyDialog(requireContext()).show()
                "clickRegionalConfig" -> {
                    (activity as? ReadBookActivity)?.showClickRegionalConfig()
                }

                "customTextMenu" -> {
                    (activity as? ReadBookActivity)?.showTextSelectMenuConfig()
                }

                "customReaderMenu" -> {
                    (activity as? ReadBookActivity)?.showReaderMenuConfig()
                }

                PreferKey.pageTouchSlop -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_touch_slop_dialog_title))
                        .setMaxValue(9999)
                        .setMinValue(0)
                        .setValue(AppConfig.pageTouchSlop)
                        .show {
                            AppConfig.pageTouchSlop = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(4))
                            refreshTick++
                        }
                }

                PreferKey.pullBookmarkDistance -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.pull_bookmark_distance_dialog_title))
                        .setMaxValue(9999)
                        .setMinValue(0)
                        .setValue(AppConfig.pullBookmarkDistance)
                        .show {
                            AppConfig.pullBookmarkDistance = it
                            refreshTick++
                        }
                }

                PreferKey.pageTouchClick -> {
                    NumberPickerDialog(requireContext())
                        .setTitle(getString(R.string.page_touch_click_dialog_title))
                        .setMaxValue(399)
                        .setMinValue(0)
                        .setValue(AppConfig.pageTouchClick)
                        .show {
                            AppConfig.pageTouchClick = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(12))
                            refreshTick++
                        }
                }
            }
            return true
        }

        @Composable
        private fun ReadConfigScreen(@Suppress("UNUSED_PARAMETER") refreshTick: Int) {
            val mouseWheelPage by rememberPrefBoolean(PreferKey.mouseWheelPage, true)
            val touchSlopSummary = remember(slopSquare) {
                getString(R.string.page_touch_slop_summary, slopSquare.toString())
            }
            val pullBookmarkSummary = remember(slopSquare) {
                getString(R.string.pull_bookmark_distance_summary, (slopSquare * 6).toString())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item(key = PreferKey.screenOrientation) {
                    ListPreferenceItem(
                        key = PreferKey.screenOrientation,
                        default = "0",
                        title = stringResource(R.string.screen_direction),
                        entries = stringArrayResource(R.array.screen_direction_title),
                        entryValues = stringArrayResource(R.array.screen_direction_value),
                    )
                }
                item(key = PreferKey.keepLight) {
                    ListPreferenceItem(
                        key = PreferKey.keepLight,
                        default = "0",
                        title = stringResource(R.string.keep_light),
                        entries = stringArrayResource(R.array.screen_time_out),
                        entryValues = stringArrayResource(R.array.screen_time_out_value),
                    )
                }
                item(key = PreferKey.hideStatusBar) {
                    SwitchPreferenceItem(
                        key = PreferKey.hideStatusBar,
                        default = false,
                        title = stringResource(R.string.pt_hide_status_bar),
                    )
                }
                item(key = PreferKey.hideNavigationBar) {
                    SwitchPreferenceItem(
                        key = PreferKey.hideNavigationBar,
                        default = false,
                        title = stringResource(R.string.pt_hide_navigation_bar),
                    )
                }
                item(key = PreferKey.readBodyToLh) {
                    SwitchPreferenceItem(
                        key = PreferKey.readBodyToLh,
                        default = true,
                        title = stringResource(R.string.read_body_to_lh),
                    )
                }
                item(key = PreferKey.paddingDisplayCutouts) {
                    SwitchPreferenceItem(
                        key = PreferKey.paddingDisplayCutouts,
                        default = false,
                        title = stringResource(R.string.padding_display_cutouts),
                    )
                }
                item(key = PreferKey.doublePageHorizontal) {
                    ListPreferenceItem(
                        key = PreferKey.doublePageHorizontal,
                        default = "2",
                        title = stringResource(R.string.double_page_horizontal),
                        entries = stringArrayResource(R.array.double_page_title),
                        entryValues = stringArrayResource(R.array.double_page_value),
                    )
                }
                item(key = PreferKey.progressBarBehavior) {
                    ListPreferenceItem(
                        key = PreferKey.progressBarBehavior,
                        default = "page",
                        title = stringResource(R.string.progress_bar_behavior),
                        entries = stringArrayResource(R.array.progress_bar_behavior_title),
                        entryValues = stringArrayResource(R.array.progress_bar_behavior_value),
                    )
                }
                item(key = PreferKey.useZhLayout) {
                    SwitchPreferenceItem(
                        key = PreferKey.useZhLayout,
                        default = false,
                        title = stringResource(R.string.use_zh_layout),
                    )
                }
                item(key = PreferKey.textFullJustify) {
                    SwitchPreferenceItem(
                        key = PreferKey.textFullJustify,
                        default = true,
                        title = stringResource(R.string.text_full_justify),
                    )
                }
                item(key = PreferKey.hangingPunctuation) {
                    SwitchPreferenceItem(
                        key = PreferKey.hangingPunctuation,
                        default = false,
                        title = stringResource(R.string.hanging_punctuation),
                        summary = stringResource(R.string.hanging_punctuation_summary),
                    )
                }
                item(key = PreferKey.punctuationCompress) {
                    ListPreferenceItem(
                        key = PreferKey.punctuationCompress,
                        default = "none",
                        title = stringResource(R.string.punctuation_compress),
                        summaryOverride = stringResource(R.string.punctuation_compress_summary),
                        entries = stringArrayResource(R.array.punctuation_compress_title),
                        entryValues = stringArrayResource(R.array.punctuation_compress_value),
                    )
                }
                item(key = PreferKey.textBottomJustify) {
                    SwitchPreferenceItem(
                        key = PreferKey.textBottomJustify,
                        default = true,
                        title = stringResource(R.string.text_bottom_justify),
                    )
                }
                item(key = PreferKey.adaptSpecialStyle) {
                    SwitchPreferenceItem(
                        key = PreferKey.adaptSpecialStyle,
                        default = true,
                        title = stringResource(R.string.adapt_special_style),
                    )
                }
                item(key = PreferKey.mouseWheelPage) {
                    SwitchPreferenceItem(
                        key = PreferKey.mouseWheelPage,
                        default = true,
                        title = stringResource(R.string.mouse_wheel_page),
                    )
                }
                item(key = PreferKey.mouseWheelScrollSpeed) {
                    SeekBarPreferenceItem(
                        key = PreferKey.mouseWheelScrollSpeed,
                        default = 100,
                        min = 10,
                        max = 400,
                        step = 10,
                        title = stringResource(R.string.mouse_wheel_scroll_speed),
                        summary = stringResource(R.string.mouse_wheel_scroll_speed_summary),
                        enabled = mouseWheelPage,
                    )
                }
                item(key = PreferKey.volumeKeyPage) {
                    SwitchPreferenceItem(
                        key = PreferKey.volumeKeyPage,
                        default = true,
                        title = stringResource(R.string.volume_key_page),
                    )
                }
                item(key = PreferKey.volumeKeyPageOnPlay) {
                    SwitchPreferenceItem(
                        key = PreferKey.volumeKeyPageOnPlay,
                        default = false,
                        title = stringResource(R.string.volume_key_page_on_play),
                    )
                }
                item(key = PreferKey.keyPageOnLongPress) {
                    SwitchPreferenceItem(
                        key = PreferKey.keyPageOnLongPress,
                        default = false,
                        title = stringResource(R.string.key_page_on_long_press),
                    )
                }
                item(key = PreferKey.pullToToggleBookmark) {
                    SwitchPreferenceItem(
                        key = PreferKey.pullToToggleBookmark,
                        default = false,
                        title = stringResource(R.string.pull_to_toggle_bookmark),
                        summary = stringResource(R.string.pull_to_toggle_bookmark_summary),
                    )
                }
                item(key = PreferKey.pullBookmarkDistance) {
                    PreferenceItem(
                        title = stringResource(R.string.pull_bookmark_distance_title),
                        summary = pullBookmarkSummary,
                        onClick = { onPreferenceTreeClick(PreferenceKeyRef(PreferKey.pullBookmarkDistance)) },
                    )
                }
                item(key = PreferKey.pageTouchSlop) {
                    PreferenceItem(
                        title = stringResource(R.string.page_touch_slop_title),
                        summary = touchSlopSummary,
                        onClick = { onPreferenceTreeClick(PreferenceKeyRef(PreferKey.pageTouchSlop)) },
                    )
                }
                item(key = PreferKey.pageTouchClick) {
                    PreferenceItem(
                        title = stringResource(R.string.page_touch_click_title),
                        summary = stringResource(R.string.page_touch_click_summary),
                        onClick = { onPreferenceTreeClick(PreferenceKeyRef(PreferKey.pageTouchClick)) },
                    )
                }
                item(key = PreferKey.autoChangeSource) {
                    SwitchPreferenceItem(
                        key = PreferKey.autoChangeSource,
                        default = true,
                        title = stringResource(R.string.auto_change_source),
                    )
                }
                item(key = PreferKey.textSelectAble) {
                    SwitchPreferenceItem(
                        key = PreferKey.textSelectAble,
                        default = true,
                        title = stringResource(R.string.selectText),
                    )
                }
                item(key = PreferKey.longPressSelectParagraph) {
                    SwitchPreferenceItem(
                        key = PreferKey.longPressSelectParagraph,
                        default = false,
                        title = stringResource(R.string.long_press_select_paragraph),
                        summary = stringResource(R.string.long_press_select_paragraph_summary),
                    )
                }
                item(key = PreferKey.twoFingerReplacePreview) {
                    SwitchPreferenceItem(
                        key = PreferKey.twoFingerReplacePreview,
                        default = false,
                        title = stringResource(R.string.two_finger_replace_preview),
                        summary = stringResource(R.string.two_finger_replace_preview_summary),
                    )
                }
                item(key = PreferKey.showBrightnessView) {
                    SwitchPreferenceItem(
                        key = PreferKey.showBrightnessView,
                        default = true,
                        title = stringResource(R.string.show_brightness_view),
                    )
                }
                item(key = PreferKey.noAnimScrollPage) {
                    SwitchPreferenceItem(
                        key = PreferKey.noAnimScrollPage,
                        default = false,
                        title = stringResource(R.string.no_anim_scroll_page),
                    )
                }
                item(key = PreferKey.clickImgWay) {
                    ListPreferenceItem(
                        key = PreferKey.clickImgWay,
                        default = "0",
                        title = stringResource(R.string.click_image_way),
                        entries = stringArrayResource(R.array.click_image_way_title),
                        entryValues = stringArrayResource(R.array.click_image_way_value),
                    )
                }
                item(key = PreferKey.highlightActionTrigger) {
                    ListPreferenceItem(
                        key = PreferKey.highlightActionTrigger,
                        default = "click",
                        title = stringResource(R.string.highlight_action_trigger),
                        entries = stringArrayResource(R.array.highlight_action_trigger_title),
                        entryValues = stringArrayResource(R.array.highlight_action_trigger_value),
                    )
                }
                if (CanvasRecorderFactory.isSupport) {
                    item(key = PreferKey.optimizeRender) {
                        SwitchPreferenceItem(
                            key = PreferKey.optimizeRender,
                            default = false,
                            title = stringResource(R.string.enable_optimize_render),
                        )
                    }
                }
                item(key = "clickRegionalConfig") {
                    PreferenceItem(
                        title = stringResource(R.string.click_regional_config),
                        onClick = { onPreferenceTreeClick(PreferenceKeyRef("clickRegionalConfig")) },
                    )
                }
                item(key = "disableReturnKey") {
                    SwitchPreferenceItem(
                        key = "disableReturnKey",
                        default = false,
                        title = stringResource(R.string.disable_return_key),
                    )
                }
                item(key = "customPageKey") {
                    PreferenceItem(
                        title = stringResource(R.string.custom_page_key),
                        onClick = { onPreferenceTreeClick(PreferenceKeyRef("customPageKey")) },
                    )
                }
                item(key = "customTextMenu") {
                    PreferenceItem(
                        title = stringResource(R.string.text_select_menu_config),
                        summary = stringResource(R.string.text_select_menu_config_summary),
                        onClick = { onPreferenceTreeClick(PreferenceKeyRef("customTextMenu")) },
                    )
                }
                item(key = "customReaderMenu") {
                    PreferenceItem(
                        title = stringResource(R.string.reader_menu_config),
                        summary = stringResource(R.string.reader_menu_config_summary),
                        onClick = { onPreferenceTreeClick(PreferenceKeyRef("customReaderMenu")) },
                    )
                }
                item(key = PreferKey.showReadTitleAddition) {
                    SwitchPreferenceItem(
                        key = PreferKey.showReadTitleAddition,
                        default = true,
                        title = stringResource(R.string.show_read_title_addition),
                    )
                }
                item(key = PreferKey.showReadTitleChapterNameOnly) {
                    SwitchPreferenceItem(
                        key = PreferKey.showReadTitleChapterNameOnly,
                        default = false,
                        title = stringResource(R.string.show_read_title_chapter_name_only),
                    )
                }
                item(key = PreferKey.readBarStyleFollowPage) {
                    SwitchPreferenceItem(
                        key = PreferKey.readBarStyleFollowPage,
                        default = false,
                        title = stringResource(R.string.read_bar_style_follow_page),
                    )
                }
                item(key = PreferKey.showBookMemo) {
                    SwitchPreferenceItem(
                        key = PreferKey.showBookMemo,
                        default = false,
                        title = stringResource(R.string.show_book_memo),
                        summary = stringResource(R.string.show_book_memo_summary),
                    )
                }
            }
        }
    }
}
