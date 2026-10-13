package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuItemImpl
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.PopupAction
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.toastOnUi

/** Compose backed text selection action menu. */
@SuppressLint("RestrictedApi")
class TextActionMenu(private val context: Context, private val callBack: CallBack) {

    private val popup = PopupAction(context)
    private val menuItems: List<MenuItemImpl>
    private val visibleMenuItems = arrayListOf<MenuItemImpl>()
    private val moreMenuItems = arrayListOf<MenuItemImpl>()
    private var showingMore = false

    init {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) onInitializeMenu(otherMenu)
        menuItems = myMenu.visibleItems + otherMenu.visibleItems
        popup.onActionClick = ::onActionClick
        popup.onActionLongClick = ::onActionLongClick
        popup.setOnDismissListener { showingMore = false }
        upMenu()
    }

    fun dismiss() = popup.dismiss()

    fun upMenu() {
        buildPartition()
        showingMore = false
        showPrimaryItems()
    }

    private fun buildPartition() {
        val itemsById = menuItems.filter { it.itemId != Menu.NONE }.associateBy { it.itemId }
        val builtInItems = TextSelectMenuItem.entries.mapNotNull { configured ->
            configured.menuId?.let { id -> itemsById[id]?.let { configured.key to it } }
        }.toMap()
        val processTextItems = menuItems.filter {
            it.itemId == Menu.NONE && it.intent?.action == Intent.ACTION_PROCESS_TEXT
        }
        val partition = loadTextSelectMenuConfig(context).partitionItems(
            builtInItems = builtInItems,
            processTextItems = processTextItems,
            allItems = menuItems,
        )
        visibleMenuItems.clear()
        visibleMenuItems.addAll(partition.bar)
        moreMenuItems.clear()
        moreMenuItems.addAll(partition.more)
    }

    private fun showPrimaryItems() {
        popup.setVertical(false)
        popup.setActionItems(buildList {
            addAll(visibleMenuItems.map(::asPopupItem))
            if (moreMenuItems.isNotEmpty()) add(PopupAction.PopupActionItem("⋮", ACTION_MORE))
        })
    }

    private fun showMoreItems() {
        showingMore = true
        popup.setVertical(true)
        popup.setActionItems(buildList {
            add(PopupAction.PopupActionItem("‹ 返回", ACTION_BACK))
            addAll(moreMenuItems.map(::asPopupItem))
        })
    }

    private fun asPopupItem(item: MenuItemImpl) = PopupAction.PopupActionItem(
        title = item.title?.toString().orEmpty(),
        value = ACTION_ITEM_PREFIX + menuItems.indexOf(item),
    )

    private fun onActionClick(action: String) {
        when (action) {
            ACTION_MORE -> showMoreItems()
            ACTION_BACK -> {
                showingMore = false
                showPrimaryItems()
            }
            else -> action.removePrefix(ACTION_ITEM_PREFIX).toIntOrNull()
                ?.let(menuItems::getOrNull)
                ?.let { item ->
                    if (!callBack.onMenuItemSelected(item.itemId)) onMenuItemSelected(item)
                    callBack.onMenuActionFinally()
                }
        }
    }

    private fun onActionLongClick(action: String) {
        if (action == ACTION_MORE || action == ACTION_BACK) {
            dismiss()
            callBack.onEditTextActionMenu()
            return
        }
        AppConfig.contentSelectSpeakMod = if (AppConfig.contentSelectSpeakMod == 0) 1 else 0
        context.toastOnUi(
            if (AppConfig.contentSelectSpeakMod == 1) "切换为从选择的地方开始一直朗读"
            else "切换为朗读选择内容"
        )
    }

    fun show(
        view: View,
        windowHeight: Int,
        startX: Int,
        startTopY: Int,
        startBottomY: Int,
        endX: Int,
        endBottomY: Int,
    ) {
        upMenu()
        when {
            startTopY > 500 -> popup.showAtLocation(
                view, Gravity.BOTTOM or Gravity.START, startX, windowHeight - startTopY
            )
            endBottomY - startBottomY > 500 -> popup.showAtLocation(
                view, Gravity.TOP or Gravity.START, startX, startBottomY
            )
            else -> popup.showAtLocation(
                view, Gravity.TOP or Gravity.START, endX, endBottomY
            )
        }
    }

    private fun onMenuItemSelected(item: MenuItemImpl) {
        when (item.itemId) {
            R.id.menu_copy -> context.sendToClip(callBack.selectedText)
            R.id.menu_share_str -> context.share(callBack.selectedText)
            R.id.menu_browser -> runCatching {
                val intent = if (callBack.selectedText.isAbsUrl()) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(callBack.selectedText))
                } else {
                    Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, callBack.selectedText)
                }
                context.startActivity(intent)
            }.onFailure {
                it.printOnDebug()
                context.toastOnUi(it.localizedMessage ?: "ERROR")
            }
            else -> item.intent?.let { intent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) runCatching {
                    intent.putExtra(Intent.EXTRA_PROCESS_TEXT, callBack.selectedText)
                    context.startActivity(intent)
                }.onFailure { error ->
                    AppLog.put("执行文本菜单操作出错\n$error", error, true)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntent() =
        Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getSupportedActivities(): List<ResolveInfo> =
        context.packageManager.queryIntentActivities(createProcessTextIntent(), 0)

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createProcessTextIntentForResolveInfo(info: ResolveInfo) =
        createProcessTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)

    @RequiresApi(Build.VERSION_CODES.M)
    private fun onInitializeMenu(menu: Menu) {
        runCatching {
            var order = 100
            getSupportedActivities().forEach { info ->
                menu.add(Menu.NONE, Menu.NONE, order++, info.loadLabel(context.packageManager)).intent =
                    createProcessTextIntentForResolveInfo(info)
            }
        }.onFailure { context.toastOnUi("获取文字操作菜单出错:${it.localizedMessage}") }
    }

    interface CallBack {
        val selectedText: String
        fun onMenuItemSelected(itemId: Int): Boolean
        fun onMenuActionFinally()
        fun onEditTextActionMenu()
    }

    private companion object {
        const val ACTION_MORE = "__more"
        const val ACTION_BACK = "__back"
        const val ACTION_ITEM_PREFIX = "item:"
    }
}
