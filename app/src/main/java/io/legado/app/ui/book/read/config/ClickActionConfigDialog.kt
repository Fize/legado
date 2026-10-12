package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.putPrefInt

/**
 * 点击区域设置
 */
class ClickActionConfigDialog : BaseDialogFragment() {

    private val actions by lazy {
        linkedMapOf(
            Pair(-1, getString(R.string.non_action)),
            Pair(0, getString(R.string.menu)),
            Pair(1, getString(R.string.next_page)),
            Pair(2, getString(R.string.prev_page)),
            Pair(3, getString(R.string.next_chapter)),
            Pair(4, getString(R.string.previous_chapter)),
            Pair(5, getString(R.string.read_aloud_prev_paragraph)),
            Pair(6, getString(R.string.read_aloud_next_paragraph)),
            Pair(7, getString(R.string.bookmark_add)),
            Pair(8, getString(R.string.edit_content)),
            Pair(9, getString(R.string.replace_state_change)),
            Pair(10, getString(R.string.chapter_list)),
            Pair(11, getString(R.string.search_content)),
            Pair(12, getString(R.string.sync_book_progress_t)),
            Pair(13, getString(R.string.read_aloud_pause_resume))
        )
    }

    private var tlState by mutableIntStateOf(AppConfig.clickActionTL)
    private var tcState by mutableIntStateOf(AppConfig.clickActionTC)
    private var trState by mutableIntStateOf(AppConfig.clickActionTR)
    private var mlState by mutableIntStateOf(AppConfig.clickActionML)
    private var mcState by mutableIntStateOf(AppConfig.clickActionMC)
    private var mrState by mutableIntStateOf(AppConfig.clickActionMR)
    private var blState by mutableIntStateOf(AppConfig.clickActionBL)
    private var bcState by mutableIntStateOf(AppConfig.clickActionBC)
    private var brState by mutableIntStateOf(AppConfig.clickActionBR)

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ClickActionConfigScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        view.setBackgroundColor(getCompatColor(R.color.translucent))
        view.applyNavigationBarPadding(withInitialPadding = true)
        initData()
    }

    private fun initData() {
        tlState = AppConfig.clickActionTL
        tcState = AppConfig.clickActionTC
        trState = AppConfig.clickActionTR
        mlState = AppConfig.clickActionML
        mcState = AppConfig.clickActionMC
        mrState = AppConfig.clickActionMR
        blState = AppConfig.clickActionBL
        bcState = AppConfig.clickActionBC
        brState = AppConfig.clickActionBR
    }

    @Composable
    private fun ClickActionConfigScreen() {
        val cardBg = Color(0x80000000)
        val cardBorder = Color(0x66FFFFFF)
        val shape = RoundedCornerShape(6.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(R.color.translucent))
                .padding(3.dp)
        ) {
            // Header card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp)
                    .clip(shape)
                    .background(cardBg)
                    .border(1.dp, cardBorder, shape)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.click_regional_config),
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { dismissAllowingStateLoss() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_close),
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White
                    )
                }
            }

            // Top row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                ZoneCell(
                    label = actions[tlState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionTL, action)
                            tlState = action
                        }
                    }
                )
                ZoneCell(
                    label = actions[tcState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionTC, action)
                            tcState = action
                        }
                    }
                )
                ZoneCell(
                    label = actions[trState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionTR, action)
                            trState = action
                        }
                    }
                )
            }

            // Middle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                ZoneCell(
                    label = actions[mlState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionML, action)
                            mlState = action
                        }
                    }
                )
                ZoneCell(
                    label = actions[mcState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionMC, action)
                            mcState = action
                        }
                    }
                )
                ZoneCell(
                    label = actions[mrState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionMR, action)
                            mrState = action
                        }
                    }
                )
            }

            // Bottom row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                ZoneCell(
                    label = actions[blState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionBL, action)
                            blState = action
                        }
                    }
                )
                ZoneCell(
                    label = actions[bcState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionBC, action)
                            bcState = action
                        }
                    }
                )
                ZoneCell(
                    label = actions[brState].orEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectAction { action ->
                            putPrefInt(PreferKey.clickActionBR, action)
                            brState = action
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun ZoneCell(
        label: String,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val shape = RoundedCornerShape(6.dp)
        Box(
            modifier = modifier
                .fillMaxHeight()
                .padding(3.dp)
                .clip(shape)
                .background(Color(0x80000000))
                .border(1.dp, Color(0x66FFFFFF), shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }

    private fun selectAction(success: (action: Int) -> Unit) {
        context?.selector(
            getString(R.string.select_action),
            actions.values.toList()
        ) { _, index ->
            success.invoke(actions.keys.toList()[index])
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppConfig.detectClickArea()
    }
}
