package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.toColorInt
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.buttonDisabledColor
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.view.ThemeSeekBar
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.TitleBar
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.ui.widget.seekbar.VerticalSeekBarWrapper
import io.legado.app.ui.widget.text.AccentBgTextView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConstraintModify
import io.legado.app.utils.activity
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.loadAnimation
import io.legado.app.utils.modifyBegin
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.visible
import splitties.views.onClick
import splitties.views.onLongClick

/**
 * 阅读界面菜单
 */
class ReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    class ViewReadMenuViewHolder(context: Context, parent: ViewGroup) {
        private val borderlessBgResId: Int = TypedValue().let {
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true
            )
            it.resourceId
        }
        private val compatBorderlessBgResId: Int = TypedValue().let {
            context.theme.resolveAttribute(
                androidx.appcompat.R.attr.selectableItemBackgroundBorderless,
                it,
                true
            )
            it.resourceId
        }

        val vwMenuBg = View(context).apply {
            id = R.id.vw_menu_bg
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentDescription = context.getString(R.string.content)
        }
        val tvChapterName = TextView(context).apply {
            id = R.id.tv_chapter_name
            setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            visibility = View.GONE
            layoutParams = ConstraintLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToLeft = R.id.tv_custom_btn
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvChapterUrl = TextView(context).apply {
            id = R.id.tv_chapter_url
            setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            visibility = View.GONE
            layoutParams = ConstraintLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToLeft = R.id.tv_custom_btn
                topToBottom = R.id.tv_chapter_name
            }
        }
        val tvCustomBtn = AppCompatImageView(context).apply {
            id = R.id.tv_custom_btn
            setPaddingRelative(8.dpToPx(), 0, 4.dpToPx(), 0)
            setImageResource(R.drawable.ic_custom)
            contentDescription = context.getString(R.string.custom_button)
            visibility = View.GONE
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0
            ).apply {
                val m = 1.dpToPx()
                setMargins(m, m, m, m)
                bottomToBottom = R.id.tv_chapter_url
                rightToLeft = R.id.tv_source_action
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvSourceAction = AccentBgTextView(context).apply {
            id = R.id.tv_source_action
            gravity = Gravity.CENTER
            maxWidth = 120.dpToPx()
            setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
            setText(R.string.book_source)
            setRadius(2)
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0
            ).apply {
                val m = 1.dpToPx()
                setMargins(m, m, m, m)
                bottomToBottom = R.id.tv_chapter_url
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val titleBarAddition = ConstraintLayout(context).apply {
            id = R.id.title_bar_addition
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(tvChapterName)
            addView(tvChapterUrl)
            addView(tvCustomBtn)
            addView(tvSourceAction)
        }
        val titleBar = TitleBar(
            ContextThemeWrapper(
                context,
                TypedValue().let {
                    context.theme.resolveAttribute(androidx.appcompat.R.attr.actionBarStyle, it, true)
                    it.resourceId
                }
            ),
            defaultOpaque = true
        ).apply {
            id = R.id.title_bar
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
            addView(titleBarAddition)
        }
        val ivBrightnessAuto = ImageView(context).apply {
            id = R.id.iv_brightness_auto
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply {
                val m = 8.dpToPx()
                setMargins(m, m, m, m)
            }
            contentDescription = context.getString(R.string.brightness_auto)
            setImageResource(R.drawable.ic_brightness_auto)
        }
        val seekBrightness = AppCompatSeekBar(context).apply {
            id = R.id.seek_brightness
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            max = 255
            rotation = -90f
        }
        val vwBrightnessPosAdjust = AppCompatImageView(context).apply {
            id = R.id.vw_brightness_pos_adjust
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply {
                val m = 8.dpToPx()
                setMargins(m, m, m, m)
            }
            contentDescription = context.getString(R.string.adjust_pos)
            setImageResource(R.drawable.ic_swap_horiz)
        }
        val llBrightness = LinearLayout(context).apply {
            id = R.id.ll_brightness
            setBackgroundResource(R.color.transparent30)
            gravity = Gravity.CENTER_HORIZONTAL
            orientation = LinearLayout.VERTICAL
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0
            ).apply {
                leftMargin = 16.dpToPx()
                rightMargin = 16.dpToPx()
                topMargin = 16.dpToPx()
                bottomToTop = R.id.bottom_menu
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = R.id.title_bar
            }
            addView(ivBrightnessAuto)
            addView(
                VerticalSeekBarWrapper(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0,
                        1f
                    )
                    addView(seekBrightness)
                }
            )
            addView(vwBrightnessPosAdjust)
        }
        val fabSearch = FloatingActionButton(context).apply {
            id = R.id.fabSearch
            size = FloatingActionButton.SIZE_MINI
            contentDescription = context.getString(R.string.search_content)
            setImageResource(R.drawable.ic_search)
            compatElevation = 2f.dpToPx()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = 16.dpToPx()
                setMargins(m, m, m, m)
            }
        }
        val fabAutoPage = FloatingActionButton(context).apply {
            id = R.id.fabAutoPage
            size = FloatingActionButton.SIZE_MINI
            contentDescription = context.getString(R.string.auto_next_page)
            setImageResource(R.drawable.ic_auto_page)
            compatElevation = 2f.dpToPx()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = 16.dpToPx()
                setMargins(m, m, m, m)
            }
        }
        val fabReplaceRule = FloatingActionButton(context).apply {
            id = R.id.fabReplaceRule
            size = FloatingActionButton.SIZE_MINI
            contentDescription = context.getString(R.string.replace_rule_title)
            setImageResource(R.drawable.ic_find_replace)
            compatElevation = 2f.dpToPx()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = 16.dpToPx()
                setMargins(m, m, m, m)
            }
        }
        val fabNightTheme = FloatingActionButton(context).apply {
            id = R.id.fabNightTheme
            size = FloatingActionButton.SIZE_MINI
            contentDescription = context.getString(R.string.dark_theme)
            setImageResource(R.drawable.ic_brightness)
            compatElevation = 2f.dpToPx()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = 16.dpToPx()
                setMargins(m, m, m, m)
            }
        }
        val llFloatingButton = LinearLayout(context).apply {
            id = R.id.ll_floating_button
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(16.dpToPx(), 0, 16.dpToPx(), 0)
            addView(fabSearch)
            addView(Space(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(fabAutoPage)
            addView(Space(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(fabReplaceRule)
            addView(Space(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(fabNightTheme)
        }
        val tvPre = TextView(context).apply {
            id = R.id.tv_pre
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 10.dpToPx()
                marginEnd = 10.dpToPx()
            }
            setBackgroundResource(borderlessBgResId)
            isClickable = true
            isEnabled = false
            isFocusable = true
            setPadding(0, 10.dpToPx(), 0, 10.dpToPx())
            setText(R.string.previous_chapter)
            textSize = 14f
        }
        val seekReadPage = ThemeSeekBar(context).apply {
            id = R.id.seek_read_page
            layoutParams = LinearLayout.LayoutParams(0, 25.dpToPx(), 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val tvNext = TextView(context).apply {
            id = R.id.tv_next
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 10.dpToPx()
                marginEnd = 10.dpToPx()
            }
            setBackgroundResource(borderlessBgResId)
            isClickable = true
            isEnabled = false
            isFocusable = true
            setPadding(0, 10.dpToPx(), 0, 10.dpToPx())
            setText(R.string.next_chapter)
            textSize = 14f
        }
        val ivCatalog = AppCompatImageView(context).apply {
            id = R.id.iv_catalog
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            adjustViewBounds = true
            contentDescription = context.getString(R.string.chapter_list)
            maxHeight = 20.dpToPx()
            setImageResource(R.drawable.ic_toc)
        }
        val tvCatalog = TextView(context).apply {
            id = R.id.tv_catalog
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 3.dpToPx()
            }
            ellipsize = TextUtils.TruncateAt.MIDDLE
            isSingleLine = true
            setText(R.string.chapter_list)
            textSize = 12f
        }
        val llCatalog = LinearLayout(context).apply {
            id = R.id.ll_catalog
            layoutParams = LinearLayout.LayoutParams(60.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundResource(borderlessBgResId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.chapter_list)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 7.dpToPx())
            addView(ivCatalog)
            addView(tvCatalog)
        }
        val ivReadAloud = AppCompatImageView(context).apply {
            id = R.id.iv_read_aloud
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            adjustViewBounds = true
            contentDescription = context.getString(R.string.read_aloud)
            maxHeight = 20.dpToPx()
            setImageResource(R.drawable.ic_read_aloud)
        }
        val tvReadAloud = TextView(context).apply {
            id = R.id.tv_read_aloud
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 3.dpToPx()
            }
            ellipsize = TextUtils.TruncateAt.MIDDLE
            isSingleLine = true
            setText(R.string.read_aloud)
            textSize = 12f
        }
        val llReadAloud = LinearLayout(context).apply {
            id = R.id.ll_read_aloud
            layoutParams = LinearLayout.LayoutParams(60.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundResource(borderlessBgResId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.read_aloud)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 7.dpToPx())
            addView(ivReadAloud)
            addView(tvReadAloud)
        }
        val ivFont = AppCompatImageView(context).apply {
            id = R.id.iv_font
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            adjustViewBounds = true
            contentDescription = context.getString(R.string.interface_setting)
            maxHeight = 20.dpToPx()
            setImageResource(R.drawable.ic_interface_setting)
        }
        val tvFont = TextView(context).apply {
            id = R.id.tv_font
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 3.dpToPx()
            }
            ellipsize = TextUtils.TruncateAt.MIDDLE
            isSingleLine = true
            setText(R.string.interface_setting)
            textSize = 12f
        }
        val llFont = LinearLayout(context).apply {
            id = R.id.ll_font
            layoutParams = LinearLayout.LayoutParams(60.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundResource(compatBorderlessBgResId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.interface_setting)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 7.dpToPx())
            addView(ivFont)
            addView(tvFont)
        }
        val ivSetting = AppCompatImageView(context).apply {
            id = R.id.iv_setting
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            adjustViewBounds = true
            contentDescription = context.getString(R.string.setting)
            maxHeight = 20.dpToPx()
            setImageResource(R.drawable.ic_settings)
        }
        val tvSetting = TextView(context).apply {
            id = R.id.tv_setting
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 3.dpToPx()
            }
            ellipsize = TextUtils.TruncateAt.MIDDLE
            isSingleLine = true
            setText(R.string.setting)
            textSize = 12f
        }
        val llSetting = LinearLayout(context).apply {
            id = R.id.ll_setting
            layoutParams = LinearLayout.LayoutParams(60.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundResource(borderlessBgResId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.setting)
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 7.dpToPx())
            addView(ivSetting)
            addView(tvSetting)
        }
        val memoSpacer = View(context).apply {
            id = R.id.memo_spacer
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        val ivMemo = AppCompatImageView(context).apply {
            id = R.id.iv_memo
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            adjustViewBounds = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            maxHeight = 20.dpToPx()
            setImageResource(R.drawable.ic_edit)
        }
        val tvMemo = TextView(context).apply {
            id = R.id.tv_memo
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 3.dpToPx()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isSingleLine = true
            setText(R.string.book_memo)
            textSize = 12f
        }
        val llMemo = LinearLayout(context).apply {
            id = R.id.ll_memo
            layoutParams = LinearLayout.LayoutParams(60.dpToPx(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundResource(compatBorderlessBgResId)
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.book_memo)
            minimumHeight = 48.dpToPx()
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 7.dpToPx())
            visibility = View.GONE
            addView(ivMemo)
            addView(tvMemo)
        }
        val llBottomBg = LinearLayout(context).apply {
            id = R.id.ll_bottom_bg
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.color.background_menu)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(20.dpToPx(), 5.dpToPx(), 20.dpToPx(), 5.dpToPx())
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    orientation = LinearLayout.HORIZONTAL
                    addView(tvPre)
                    addView(seekReadPage)
                    addView(tvNext)
                }
            )
            addView(
                LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    isBaselineAligned = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    orientation = LinearLayout.HORIZONTAL
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(llCatalog)
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(llReadAloud)
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(llFont)
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                    addView(llSetting)
                    addView(memoSpacer)
                    addView(llMemo)
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                }
            )
        }
        val bottomMenu = LinearLayout(context).apply {
            id = R.id.bottom_menu
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            orientation = LinearLayout.VERTICAL
            addView(llFloatingButton)
            addView(llBottomBg)
        }
        val vwMenuRoot = ConstraintLayout(context).apply {
            id = R.id.vw_menu_root
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(vwMenuBg)
            addView(titleBar)
            addView(llBrightness)
            addView(bottomMenu)
        }
        val root = vwMenuRoot

        init {
            parent.addView(root)
        }
    }

    var canShowMenu: Boolean = false
    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewReadMenuViewHolder(context, this)
    private val chapterNameTextSize = binding.tvChapterName.textSize
    private var confirmSkipToChapter: Boolean = false
    private var isMenuOutAnimating = false
    private val menuTopIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_in)
    }
    private val menuTopOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_top_out)
    }
    private val menuBottomIn: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_in)
    }
    private val menuBottomOut: Animation by lazy {
        loadAnimation(context, R.anim.anim_readbook_bottom_out)
    }
    private val immersiveMenu: Boolean
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0
    private var bgColor: Int = if (immersiveMenu) {
        kotlin.runCatching {
            ReadBookConfig.durConfig.curBgStr().toColorInt()
        }.getOrDefault(context.bottomBackground)
    } else {
        context.bottomBackground
    }
    private var textColor: Int = if (immersiveMenu) {
        ReadBookConfig.durConfig.curTextColor()
    } else {
        context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    }

    private var bottomBackgroundList: ColorStateList = Selector.colorBuild()
        .setDefaultColor(bgColor)
        .setPressedColor(ColorUtils.darkenColor(bgColor))
        .create()
    private var onMenuOutEnd: (() -> Unit)? = null
    private val showBrightnessView
        get() = context.getPrefBoolean(
            PreferKey.showBrightnessView,
            true
        )
    private val menuInListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            binding.tvSourceAction.text =
                ReadBook.bookSource?.bookSourceName ?: context.getString(R.string.book_source)
            binding.tvSourceAction.isGone = ReadBook.isLocalBook
            ReadBook.bookSource?.let {
                if (it.customButton) {
                    binding.tvCustomBtn.visibility = VISIBLE
                }
            }
            callBack.upSystemUiVisibility()
            binding.llBrightness.visible(showBrightnessView)
        }

        @SuppressLint("RtlHardcoded")
        override fun onAnimationEnd(animation: Animation) {
            binding.vwMenuBg.setOnClickListener { runMenuOut() }
            callBack.upSystemUiVisibility()
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }
    private val menuOutListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation) {
            isMenuOutAnimating = true
            binding.vwMenuBg.setOnClickListener(null)
        }

        override fun onAnimationEnd(animation: Animation) {
            this@ReadMenu.invisible()
            binding.titleBar.invisible()
            binding.bottomMenu.invisible()
            canShowMenu = false
            isMenuOutAnimating = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }

        override fun onAnimationRepeat(animation: Animation) = Unit
    }

    init {
        initView()
        upBrightnessState()
        bindEvent()
    }

    private fun initView(reset: Boolean = false) = binding.run {
        if (AppConfig.isNightTheme) {
            fabNightTheme.setImageResource(R.drawable.ic_daytime)
        } else {
            fabNightTheme.setImageResource(R.drawable.ic_brightness)
        }
        initAnimation()
        tvCustomBtn.setColorFilter(context.accentColor)
        if (immersiveMenu) {
            val lightTextColor = ColorUtils.withAlpha(ColorUtils.lightenColor(textColor), 0.75f)
            titleBar.setTextColor(textColor)
            titleBar.setBackgroundColor(bgColor)
            titleBar.setColorFilter(textColor)
            tvChapterName.setTextColor(lightTextColor)
            tvChapterUrl.setTextColor(lightTextColor)
        } else if (reset) {
            val bgColor = context.primaryColor
            val textColor = context.primaryTextColor
            titleBar.setTextColor(textColor)
            titleBar.setBackgroundColor(bgColor)
            titleBar.setColorFilter(textColor)
            tvChapterName.setTextColor(textColor)
            tvChapterUrl.setTextColor(textColor)
        }
        val brightnessBackground = GradientDrawable()
        brightnessBackground.cornerRadius = 5F.dpToPx()
        brightnessBackground.setColor(ColorUtils.adjustAlpha(bgColor, 0.5f))
        llBrightness.background = brightnessBackground
        if (AppConfig.isEInkMode) {
            titleBar.setBackgroundResource(R.drawable.bg_eink_border_bottom)
            llBottomBg.setBackgroundResource(R.drawable.bg_eink_border_top)
        } else {
            llBottomBg.setBackgroundColor(bgColor)
        }
        fabSearch.backgroundTintList = bottomBackgroundList
        fabSearch.setColorFilter(textColor)
        fabAutoPage.backgroundTintList = bottomBackgroundList
        fabAutoPage.setColorFilter(textColor)
        fabReplaceRule.backgroundTintList = bottomBackgroundList
        fabReplaceRule.setColorFilter(textColor)
        fabNightTheme.backgroundTintList = bottomBackgroundList
        fabNightTheme.setColorFilter(textColor)
        val chapterTextColor = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setDisabledColor(ColorUtils.withAlpha(textColor, 0.4f))
            .create()
        tvPre.setTextColor(chapterTextColor)
        tvNext.setTextColor(chapterTextColor)
        ivCatalog.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvCatalog.setTextColor(textColor)
        ivReadAloud.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvReadAloud.setTextColor(textColor)
        ivFont.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvFont.setTextColor(textColor)
        ivSetting.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvSetting.setTextColor(textColor)
        ivMemo.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        tvMemo.setTextColor(textColor)
        vwBrightnessPosAdjust.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        seekBrightness.applyTint(context.accentColor)
        llBrightness.setOnClickListener(null)
        seekBrightness.post {
            seekBrightness.progress = AppConfig.readBrightness
        }
        if (AppConfig.showReadTitleBarAddition) {
            titleBarAddition.visible()
        } else {
            titleBarAddition.gone()
        }
        updateTitleAdditionLayout()
        upBrightnessVwPos()
        /**
         * 确保视图不被导航栏遮挡
         */
        applyNavigationBarPadding()
    }

    fun reset() {
        upColorConfig()
        initView(true)
    }

    fun refreshMenuColorFilter() {
        if (immersiveMenu) {
            binding.titleBar.setColorFilter(textColor)
        }
    }

    private fun upColorConfig() {
        bgColor = if (immersiveMenu) {
            kotlin.runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        textColor = if (immersiveMenu) {
            ReadBookConfig.durConfig.curTextColor()
        } else {
            context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        }
        bottomBackgroundList = Selector.colorBuild()
            .setDefaultColor(bgColor)
            .setPressedColor(ColorUtils.darkenColor(bgColor))
            .create()
    }

    fun upBrightnessState() {
        if (brightnessAuto()) {
            binding.ivBrightnessAuto.setColorFilter(context.accentColor)
            binding.seekBrightness.isEnabled = false
        } else {
            binding.ivBrightnessAuto.setColorFilter(context.buttonDisabledColor)
            binding.seekBrightness.isEnabled = true
        }
        setScreenBrightness(AppConfig.readBrightness.toFloat())
    }

    /**
     * 系统亮度监听，在高阳光亮度时启用
     */
    private var contentObserver: ContentObserver? = null
    /**
     * 设置屏幕亮度
     */
    fun setScreenBrightness(value: Float) {
        activity?.run {
            fun setBrightness(value: Float) {
                val params = window.attributes
                params.screenBrightness = value
                window.attributes = params
            }
            val autoBrightness = BRIGHTNESS_OVERRIDE_NONE
            if (brightnessAuto() || value == autoBrightness) {
                setBrightness(autoBrightness)
                return
            }
            val brightness = if (value < 1f) 0.004f else value / 255f
            var isSunMax = false
            if (brightness == 1f) {
                val sysBrightness = getCurrentBrightness(context)
                if (sysBrightness == 255) {
                    isSunMax = true
                }
            }
            if (isSunMax) {
                contentObserver = object : ContentObserver(buildMainHandler()) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        if (contentObserver == null) return
                        if (uri == Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)) {
                            val sysBrightness = getCurrentBrightness(context)
                            if (sysBrightness < 200) {
                                setBrightness(brightness)
                                contentObserver?.let {
                                    context.contentResolver.unregisterContentObserver(it)
                                }
                                contentObserver = null
                            } else if (sysBrightness < 255) {
                                setBrightness(brightness)
                            } else {
                                setBrightness(autoBrightness)
                            }
                        }
                    }
                }
                val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                context.contentResolver.registerContentObserver(
                    brightnessUri,
                    false,
                    contentObserver!!
                )
                setBrightness(autoBrightness)
            } else {
                setBrightness(brightness)
            }
        }
    }

    /**
     * 获取系统亮度值
     */
    private fun getCurrentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        val showMemo = context.getPrefBoolean(PreferKey.showBookMemo, false)
        binding.llMemo.isVisible = showMemo
        binding.memoSpacer.isVisible = showMemo
        callBack.onMenuShow()
        this.visible()
        binding.titleBar.visible()
        binding.bottomMenu.visible()
        if (anim) {
            binding.titleBar.startAnimation(menuTopIn)
            binding.bottomMenu.startAnimation(menuBottomIn)
        } else {
            menuInListener.onAnimationStart(menuBottomIn)
            menuInListener.onAnimationEnd(menuBottomIn)
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) {
            return
        }
        callBack.onMenuHide()
        this.onMenuOutEnd = onMenuOutEnd
        if (this.isVisible) {
            if (anim) {
                binding.titleBar.startAnimation(menuTopOut)
                binding.bottomMenu.startAnimation(menuBottomOut)
            } else {
                menuOutListener.onAnimationStart(menuBottomOut)
                menuOutListener.onAnimationEnd(menuBottomOut)
            }
        }
    }

    private fun brightnessAuto(): Boolean {
        return context.getPrefBoolean("brightnessAuto", true) || !showBrightnessView
    }

    private fun bindEvent() = binding.run {
        vwMenuBg.setOnClickListener { runMenuOut() }
        titleBar.toolbar.setOnClickListener {
            callBack.openBookInfoActivity()
        }
        val chapterViewClickListener = OnClickListener {
            if (ReadBook.isLocalBook) {
                return@OnClickListener
            }
            if (AppConfig.readUrlInBrowser) {
                context.openUrl(tvChapterUrl.text.toString().substringBefore(",{"))
            } else {
                Coroutine.async {
                    context.startActivity<WebViewActivity> {
                        val url = tvChapterUrl.text.toString()
                        val bookSource = ReadBook.bookSource
                        putExtra("title", tvChapterName.text)
                        putExtra("url", url)
                        putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                        putExtra("sourceName", bookSource?.bookSourceName)
                        putExtra("sourceType", bookSource?.getSourceType())
                    }
                }
            }
        }
        val chapterViewLongClickListener = OnLongClickListener {
            if (ReadBook.isLocalBook) {
                return@OnLongClickListener true
            }
            context.alert(R.string.open_fun) {
                setMessage(R.string.use_browser_open)
                okButton {
                    AppConfig.readUrlInBrowser = true
                }
                noButton {
                    AppConfig.readUrlInBrowser = false
                }
            }
            true
        }
        tvChapterName.setOnClickListener(chapterViewClickListener)
        tvChapterName.setOnLongClickListener(chapterViewLongClickListener)
        tvChapterUrl.setOnClickListener(chapterViewClickListener)
        tvChapterUrl.setOnLongClickListener(chapterViewLongClickListener)
        tvCustomBtn.setOnClickListener {
            val book = ReadBook.book ?: return@setOnClickListener
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
        }
        tvCustomBtn.setOnLongClickListener {
            val book = ReadBook.book ?: return@setOnLongClickListener true
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            activity?.let { activity ->
                SourceCallBack.callBackBtn(
                    activity,
                    SourceCallBack.LONG_CLICK_CUSTOM_BUTTON,
                    ReadBook.bookSource,
                    book,
                    chapter,
                    BookType.text
                )
            }
            true
        }
        //书源操作
        tvSourceAction.onClick {
            val hasLogin = ReadBook.bookSource?.hasLogin() == true
            val canPay = hasLogin
                    && ReadBook.curTextChapter?.isVip == true
                    && ReadBook.curTextChapter?.isPay != true
            popupActionMenu(context) {
                item(context.getString(R.string.login), "login", hasLogin)
                item(context.getString(R.string.chapter_pay), "chapterPay", canPay)
                item(context.getString(R.string.edit_book_source), "editSource")
                item(context.getString(R.string.disable_book_source), "disableSource")
            }.show(tvSourceAction) { action ->
                when (action) {
                    "login" -> callBack.showLogin()
                    "chapterPay" -> callBack.payAction()
                    "editSource" -> callBack.openSourceEditActivity()
                    "disableSource" -> callBack.disableSource()
                }
            }
        }
        //亮度跟随
        ivBrightnessAuto.setOnClickListener {
            context.putPrefBoolean("brightnessAuto", !brightnessAuto())
            upBrightnessState()
        }
        //亮度调节
        seekBrightness.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setScreenBrightness(progress.toFloat())
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppConfig.readBrightness = seekBar.progress
            }

        })
        vwBrightnessPosAdjust.setOnClickListener {
            AppConfig.brightnessVwPos = !AppConfig.brightnessVwPos
            upBrightnessVwPos()
        }
        //阅读进度
        seekReadPage.setOnSeekBarChangeListener(object : SeekBarChangeListener {

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                binding.vwMenuBg.setOnClickListener { runMenuOut() }
                when (AppConfig.progressBarBehavior) {
                    "page" -> ReadBook.skipToPage(seekBar.progress)
                    "chapter" -> {
                        if (confirmSkipToChapter) {
                            callBack.skipToChapter(seekBar.progress)
                        } else {
                            context.alert("章节跳转确认", "确定要跳转章节吗？") {
                                yesButton {
                                    confirmSkipToChapter = true
                                    callBack.skipToChapter(seekBar.progress)
                                }
                                noButton {
                                    upSeekBar()
                                }
                                onCancelled {
                                    upSeekBar()
                                }
                            }
                        }
                    }
                }
            }

        })

        //搜索
        fabSearch.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(null)
            }
        }

        //自动翻页
        fabAutoPage.setOnClickListener {
            runMenuOut {
                callBack.autoPage()
            }
        }

        //替换
        fabReplaceRule.setOnClickListener { callBack.openReplaceRule() }

        //夜间模式
        fabNightTheme.setOnClickListener {
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            ThemeConfig.applyDayNight(context)
        }

        //上一章
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }

        //下一章
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }

        //目录
        llCatalog.setOnClickListener {
            runMenuOut {
                callBack.openChapterList()
            }
        }

        //朗读
        llReadAloud.setOnClickListener {
            runMenuOut {
                if (BaseReadAloudService.isRun) {
                    callBack.showReadAloudDialog()
                } else {
                    callBack.onClickReadAloud()
                }
            }
        }
        llReadAloud.onLongClick {
            runMenuOut {
                callBack.showReadAloudDialog()
            }
        }
        //界面
        llFont.setOnClickListener {
            runMenuOut {
                callBack.showReadStyle()
            }
        }

        //设置
        llSetting.setOnClickListener {
            runMenuOut {
                callBack.showMoreSetting()
            }
        }
        llMemo.setOnClickListener {
            runMenuOut {
                callBack.showBookMemo()
            }
        }
    }

    private fun initAnimation() {
        menuTopIn.setAnimationListener(menuInListener)
        menuTopOut.setAnimationListener(menuOutListener)
    }

    fun upBookView() {
        binding.titleBar.title = ReadBook.book?.name
        ReadBook.curTextChapter?.let {
            binding.tvChapterName.text = it.title
            binding.tvChapterName.visible()
            if (!ReadBook.isLocalBook) {
                binding.tvChapterUrl.text = it.chapter.getAbsoluteURL()
            } else {
                binding.tvChapterUrl.text = null
                binding.tvChapterUrl.gone()
            }
            updateTitleAdditionLayout()
            upSeekBar()
            binding.tvPre.isEnabled = ReadBook.durChapterIndex != 0
            binding.tvNext.isEnabled = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
        } ?: let {
            binding.tvChapterName.gone()
            binding.tvChapterUrl.gone()
        }
    }

    private fun updateTitleAdditionLayout() = binding.run {
        val chapterNameOnly = AppConfig.showReadTitleChapterNameOnly
        val scaledDensity = resources.displayMetrics.scaledDensity
        val hasChapterUrl = !tvChapterUrl.text.isNullOrBlank()
        tvChapterName.gravity = Gravity.CENTER_VERTICAL
        tvChapterUrl.gravity = Gravity.CENTER_VERTICAL
        tvChapterName.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            chapterNameTextSize + if (chapterNameOnly) 2f * scaledDensity else 0f
        )
        tvChapterUrl.alpha = if (chapterNameOnly && hasChapterUrl) 0f else 1f
        if (hasChapterUrl) {
            tvChapterUrl.visible()
        } else {
            tvChapterUrl.gone()
        }
        ConstraintSet().apply {
            clone(titleBarAddition)
            val bottomTarget = if (tvChapterUrl.isGone) {
                R.id.tv_chapter_name
            } else {
                R.id.tv_chapter_url
            }
            connect(R.id.tv_custom_btn, ConstraintSet.BOTTOM, bottomTarget, ConstraintSet.BOTTOM)
            connect(R.id.tv_source_action, ConstraintSet.BOTTOM, bottomTarget, ConstraintSet.BOTTOM)
            applyTo(titleBarAddition)
        }
        tvChapterName.translationY = 0f
        if (chapterNameOnly && tvChapterName.isVisible) {
            titleBarAddition.doOnLayout {
                tvChapterName.translationY =
                    (titleBarAddition.height - tvChapterName.height) / 2f - tvChapterName.top
            }
        }
    }

    fun upSeekBar() {
        binding.seekReadPage.apply {
            when (AppConfig.progressBarBehavior) {
                "page" -> {
                    ReadBook.curTextChapter?.let {
                        max = it.pageSize.minus(1)
                        progress = ReadBook.durPageIndex
                    }
                }

                "chapter" -> {
                    max = ReadBook.simulatedChapterSize - 1
                    progress = ReadBook.durChapterIndex
                }
            }
        }
    }

    fun setSeekPage(seek: Int) {
        binding.seekReadPage.progress = seek
    }

    fun setAutoPage(autoPage: Boolean) = binding.run {
        if (autoPage) {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page_stop)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page_stop)
        } else {
            fabAutoPage.setImageResource(R.drawable.ic_auto_page)
            fabAutoPage.contentDescription = context.getString(R.string.auto_next_page)
        }
        fabAutoPage.setColorFilter(textColor)
    }

    private fun upBrightnessVwPos() {
        if (AppConfig.brightnessVwPos) {
            binding.root.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.LEFT)
                .rightToRightOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        } else {
            binding.root.modifyBegin()
                .clear(R.id.ll_brightness, ConstraintModify.Anchor.RIGHT)
                .leftToLeftOf(R.id.ll_brightness, R.id.vw_menu_root)
                .commit()
        }
    }

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openSourceEditActivity()
        fun openBookInfoActivity()
        fun showReadStyle()
        fun showMoreSetting()
        fun showBookMemo()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()
    }

}
