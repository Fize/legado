package io.legado.app.ui.widget.keyboard

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.PopupWindow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.activity
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.windowSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import splitties.systemservices.windowManager

/** Compose keyboard assistance bar shown immediately above the IME. */
class KeyboardToolPop(
    private val context: Context,
    private val scope: CoroutineScope,
    private val rootView: View,
    private val callBack: CallBack,
) : PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    ViewTreeObserver.OnGlobalLayoutListener {

    private var keyboardItems by mutableStateOf<List<KeyboardAssist>>(emptyList())
    private var rowCount by mutableIntStateOf(AppConfig.showBoardLine.coerceIn(1, 5))
    private var softKeyboardShowing = false
    var initialPadding = 0

    init {
        contentView = ComposeView(context).apply {
            setContent {
                LegadoTheme {
                    val actions = buildList {
                        add(ToolAction("❓") { helpAlert() })
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            add(ToolAction("↩\uFE0F", callBack::onUndoClicked))
                            add(ToolAction("↪\uFE0F", callBack::onRedoClicked))
                        }
                        keyboardItems.forEach { item ->
                            add(ToolAction(item.key) { callBack.sendText(item.value) })
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 3.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((rowCount * 42 + 10).dp)
                                .horizontalScroll(rememberScrollState())
                                .padding(5.dp),
                        ) {
                            actions.chunked(rowCount).forEach { columnItems ->
                                Column {
                                    columnItems.forEach { action -> ToolButton(action) }
                                }
                            }
                        }
                    }
                }
            }
        }
        applyMd3PopupStyle()
        isTouchable = true
        isOutsideTouchable = false
        isFocusable = false
        inputMethodMode = INPUT_METHOD_NEEDED
        upAdapterData()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @androidx.compose.runtime.Composable
    private fun ToolButton(action: ToolAction) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .padding(3.dp)
                .height(36.dp)
                .combinedClickable(onClick = action.onClick),
        ) {
            Text(
                text = action.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }

    fun attachToWindow(window: Window) {
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(this)
        contentView.activity?.observeEvent<Int>(PreferKey.showBoardLine) { rows ->
            rowCount = rows.coerceIn(1, 5)
            contentView.requestLayout()
        }
        contentView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    }

    override fun onGlobalLayout() {
        val keyboardHeight = resolveKeyboardHeight()
        val wasShowing = softKeyboardShowing
        if (keyboardHeight > 0) {
            softKeyboardShowing = true
            contentView.measure(
                View.MeasureSpec.makeMeasureSpec(rootView.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED,
            )
            rootView.setPadding(0, 0, 0, initialPadding + contentView.measuredHeight)
            if (!isShowing) showAtLocation(rootView, Gravity.BOTTOM, 0, 0)
        } else {
            softKeyboardShowing = false
            rootView.setPadding(0, 0, 0, initialPadding)
            if (wasShowing) dismiss()
        }
    }

    private fun resolveKeyboardHeight(): Int {
        ViewCompat.getRootWindowInsets(rootView)?.let { insets ->
            return resolveKeyboardToolHeight(
                imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()),
                imeInsetBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                screenHeight = 0,
                visibleFrameBottom = 0,
            )
        }
        val rect = Rect()
        rootView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = windowManager.windowSize.heightPixels
        return resolveKeyboardToolHeight(null, 0, screenHeight, rect.bottom)
    }

    fun upAdapterData() {
        scope.launch {
            appDb.keyboardAssistsDao.flowByType(0)
                .catch { AppLog.put("键盘帮助浮窗获取数据失败\n${it.localizedMessage}", it) }
                .flowOn(IO)
                .collect { keyboardItems = it }
        }
    }

    private fun helpAlert() {
        val items = arrayListOf(SelectItem(context.getString(R.string.assists_key_config), "keyConfig"))
        items.addAll(callBack.helpActions())
        context.selector(context.getString(R.string.help), items) { _, item, _ ->
            if (item.value == "keyConfig") config() else callBack.onHelpActionSelect(item.value)
        }
    }

    private fun config() {
        contentView.activity?.showDialogFragment<KeyboardAssistsConfig>()
    }

    private data class ToolAction(val label: String, val onClick: () -> Unit)

    interface CallBack {
        fun helpActions(): List<SelectItem<String>> = arrayListOf()
        fun onHelpActionSelect(action: String)
        fun sendText(text: String)
        fun onUndoClicked()
        fun onRedoClicked()
    }
}

internal fun resolveKeyboardToolHeight(
    imeVisible: Boolean?,
    imeInsetBottom: Int,
    screenHeight: Int,
    visibleFrameBottom: Int,
): Int {
    if (imeVisible != null) return if (imeVisible) imeInsetBottom.coerceAtLeast(0) else 0
    if (screenHeight <= 0 || visibleFrameBottom !in 1..screenHeight) return 0
    val hiddenHeight = screenHeight - visibleFrameBottom
    return hiddenHeight.takeIf { it > screenHeight / 5 } ?: 0
}
