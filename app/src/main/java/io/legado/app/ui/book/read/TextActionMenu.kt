package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible

@SuppressLint("RestrictedApi")
class TextActionMenu(private val context: Context, private val callBack: CallBack) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    class PopupActionMenuViewHolder(context: Context) : ViewBinding {
        val recyclerView = RecyclerView(context).apply {
            id = R.id.recycler_view
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            layoutManager = FlexboxLayoutManager(context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
            }
        }
        val ivMenuMore = AppCompatImageView(context).apply {
            id = R.id.iv_menu_more
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx())
            val value = TypedValue()
            context.theme.resolveAttribute(
                androidx.appcompat.R.attr.selectableItemBackgroundBorderless,
                value,
                true
            )
            setBackgroundResource(value.resourceId)
            contentDescription = context.getString(R.string.more_menu)
            setImageResource(R.drawable.ic_more_vert)
            setColorFilter(context.getCompatColor(R.color.primaryText))
            visibility = View.GONE
        }
        val recyclerViewMore = RecyclerView(context).apply {
            id = R.id.recycler_view_more
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            layoutManager = LinearLayoutManager(context)
            visibility = View.GONE
        }
        val root = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.shape_card_view)
            orientation = LinearLayout.VERTICAL
            val pad = 5.dpToPx()
            setPadding(pad, pad, pad, pad)
            addView(
                LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    addView(recyclerView)
                    addView(ivMenuMore)
                }
            )
            addView(recyclerViewMore)
        }

        override fun getRoot(): View = root
    }

    class ItemTextViewHolder(context: Context) : ViewBinding {
        val textView = TextView(context).apply {
            id = R.id.text_view
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            setBackgroundResource(value.resourceId)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val pad = 5.dpToPx()
            setPadding(pad, pad, pad, pad)
            maxLines = 1
            setTextColor(context.getCompatColor(R.color.primaryText))
            textSize = 14f
        }
        val root = textView

        override fun getRoot(): View = root
    }

    private val binding = PopupActionMenuViewHolder(context)
    private val adapter = Adapter(context).apply {
        setHasStableIds(true)
    }
    private val menuItems: List<MenuItemImpl>
    private val visibleMenuItems = arrayListOf<MenuItemImpl>()
    private val moreMenuItems = arrayListOf<MenuItemImpl>()

    init {
        @SuppressLint("InflateParams")
        contentView = binding.root

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = false

        val myMenu = MenuBuilder(context)
        val otherMenu = MenuBuilder(context)
        myMenu.add(Menu.NONE, R.id.menu_replace, Menu.NONE, R.string.replace)
        myMenu.add(Menu.NONE, R.id.menu_copy, Menu.NONE, android.R.string.copy)
        myMenu.add(Menu.NONE, R.id.menu_bookmark, Menu.NONE, R.string.bookmark)
        myMenu.add(Menu.NONE, R.id.menu_highlight, Menu.NONE, R.string.highlight)
        myMenu.add(Menu.NONE, R.id.menu_aloud, Menu.NONE, R.string.read_aloud)
        myMenu.add(Menu.NONE, R.id.menu_dict, Menu.NONE, R.string.dict)
        myMenu.add(Menu.NONE, R.id.menu_search_content, Menu.NONE, R.string.search_content)
        myMenu.add(Menu.NONE, R.id.menu_browser, Menu.NONE, R.string.browser)
        myMenu.add(Menu.NONE, R.id.menu_share_str, Menu.NONE, R.string.share)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            onInitializeMenu(otherMenu)
        }
        menuItems = myMenu.visibleItems + otherMenu.visibleItems
        binding.recyclerView.adapter = adapter
        binding.recyclerViewMore.adapter = adapter
        setOnDismissListener {
            resetToPrimaryView()
        }
        binding.ivMenuMore.setOnClickListener {
            if (binding.recyclerView.isVisible) {
                binding.ivMenuMore.setImageResource(R.drawable.ic_arrow_back)
                adapter.setItems(moreMenuItems)
                binding.recyclerView.gone()
                binding.recyclerViewMore.visible()
            } else {
                resetToPrimaryView()
            }
        }
        binding.ivMenuMore.setOnLongClickListener {
            dismiss()
            callBack.onEditTextActionMenu()
            true
        }
        upMenu()
    }

    private fun resetToPrimaryView() {
        binding.ivMenuMore.setImageResource(R.drawable.ic_more_vert)
        binding.recyclerViewMore.gone()
        adapter.setItems(visibleMenuItems)
        binding.recyclerView.visible()
    }

    fun upMenu() {
        buildPartition()
        resetToPrimaryView()
        if (moreMenuItems.isEmpty()) {
            binding.ivMenuMore.gone()
        } else {
            binding.ivMenuMore.visible()
        }
    }

    private fun buildPartition() {
        val itemsById = menuItems
            .filter { it.itemId != Menu.NONE }
            .associateBy { it.itemId }
        val builtInItems = TextSelectMenuItem.entries.mapNotNull { item ->
            item.menuId?.let { id -> itemsById[id]?.let { item.key to it } }
        }.toMap()
        val processTextItems = menuItems.filter {
            it.itemId == Menu.NONE && it.intent?.action == Intent.ACTION_PROCESS_TEXT
        }
        val partition = loadTextSelectMenuConfig(context).partitionItems(
            builtInItems = builtInItems,
            processTextItems = processTextItems,
            allItems = menuItems
        )
        visibleMenuItems.clear()
        visibleMenuItems.addAll(partition.bar)
        moreMenuItems.clear()
        moreMenuItems.addAll(partition.more)
    }

    fun show(
        view: View,
        windowHeight: Int,
        startX: Int,
        startTopY: Int,
        startBottomY: Int,
        endX: Int,
        endBottomY: Int
    ) {
        upMenu()
        when {
            startTopY > 500 -> {
                showAtLocation(
                    view,
                    Gravity.BOTTOM or Gravity.START,
                    startX,
                    windowHeight - startTopY
                )
            }

            endBottomY - startBottomY > 500 -> {
                showAtLocation(view, Gravity.TOP or Gravity.START, startX, startBottomY)
            }

            else -> {
                showAtLocation(view, Gravity.TOP or Gravity.START, endX, endBottomY)
            }
        }
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<MenuItemImpl, ItemTextViewHolder>(context) {

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewBinding(parent: ViewGroup): ItemTextViewHolder {
            return ItemTextViewHolder(context)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTextViewHolder,
            item: MenuItemImpl,
            payloads: MutableList<Any>
        ) {
            with(binding) {
                textView.text = item.title
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTextViewHolder) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    if (!callBack.onMenuItemSelected(it.itemId)) {
                        onMenuItemSelected(it)
                    }
                }
                callBack.onMenuActionFinally()
            }
            holder.itemView.setOnLongClickListener {
                if (AppConfig.contentSelectSpeakMod == 0) {
                    AppConfig.contentSelectSpeakMod = 1
                    context.toastOnUi("切换为从选择的地方开始一直朗读")
                } else {
                    AppConfig.contentSelectSpeakMod = 0
                    context.toastOnUi("切换为朗读选择内容")
                }
                true
            }
        }
    }

    private fun onMenuItemSelected(item: MenuItemImpl) {
        when (item.itemId) {
            R.id.menu_copy -> context.sendToClip(callBack.selectedText)
            R.id.menu_share_str -> context.share(callBack.selectedText)
            R.id.menu_browser -> {
                kotlin.runCatching {
                    val intent = if (callBack.selectedText.isAbsUrl()) {
                        Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(callBack.selectedText)
                        }
                    } else {
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, callBack.selectedText)
                        }
                    }
                    context.startActivity(intent)
                }.onFailure {
                    it.printOnDebug()
                    context.toastOnUi(it.localizedMessage ?: "ERROR")
                }
            }

            else -> item.intent?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    kotlin.runCatching {
                        it.putExtra(Intent.EXTRA_PROCESS_TEXT, callBack.selectedText)
                        context.startActivity(it)
                    }.onFailure { e ->
                        AppLog.put("执行文本菜单操作出错\n$e", e, true)
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntent(): Intent {
        return Intent()
            .setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getSupportedActivities(): List<ResolveInfo> {
        return context.packageManager
            .queryIntentActivities(createProcessTextIntent(), 0)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntentForResolveInfo(info: ResolveInfo): Intent {
        return createProcessTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
    }

    /**
     * Start with a menu Item order value that is high enough
     * so that your "PROCESS_TEXT" menu items appear after the
     * standard selection menu items like Cut, Copy, Paste.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun onInitializeMenu(menu: Menu) {
        kotlin.runCatching {
            var menuItemOrder = 100
            for (resolveInfo in getSupportedActivities()) {
                menu.add(
                    Menu.NONE, Menu.NONE,
                    menuItemOrder++, resolveInfo.loadLabel(context.packageManager)
                ).intent = createProcessTextIntentForResolveInfo(resolveInfo)
            }
        }.onFailure {
            context.toastOnUi("获取文字操作菜单出错:${it.localizedMessage}")
        }
    }

    interface CallBack {
        val selectedText: String

        fun onMenuItemSelected(itemId: Int): Boolean

        fun onMenuActionFinally()

        fun onEditTextActionMenu()
    }
}
