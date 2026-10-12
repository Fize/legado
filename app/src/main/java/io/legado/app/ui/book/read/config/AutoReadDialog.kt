package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.BaseReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ColorUtils
import java.util.Locale
import kotlin.math.roundToInt

class AutoReadDialog : BaseDialogFragment() {

    private val callBack: CallBack? get() = activity as? CallBack

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
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    AutoReadScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
    }

    @Composable
    private fun AutoReadScreen() {
        val context = requireContext()
        val bgInt = context.bottomBackground
        val isLight = ColorUtils.isColorLight(bgInt)
        val textColor = Color(context.getPrimaryTextColor(isLight))
        val accent = Color(context.accentColor)
        var speed by remember {
            mutableIntStateOf(if (ReadBookConfig.autoReadSpeed < 1) 1 else ReadBookConfig.autoReadSpeed)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(bgInt))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.auto_page_speed),
                    color = textColor,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(Locale.ROOT, "%ds", speed),
                    color = textColor,
                    fontSize = 14.sp
                )
            }

            Slider(
                value = speed.toFloat(),
                onValueChange = { speed = it.roundToInt().coerceAtLeast(1) },
                onValueChangeFinished = {
                    ReadBookConfig.autoReadSpeed = speed.coerceAtLeast(1)
                    upTtsSpeechRate()
                },
                valueRange = 1f..120f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(50.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AutoReadActionItem(
                    iconRes = R.drawable.ic_toc,
                    labelRes = R.string.chapter_list,
                    textColor = textColor,
                    onClick = { callBack?.openChapterList() }
                )
                AutoReadActionItem(
                    iconRes = R.drawable.ic_menu,
                    labelRes = R.string.main_menu,
                    textColor = textColor,
                    onClick = {
                        callBack?.showMenuBar()
                        dismissAllowingStateLoss()
                    }
                )
                AutoReadActionItem(
                    iconRes = R.drawable.ic_auto_page_stop,
                    labelRes = R.string.stop,
                    textColor = textColor,
                    onClick = {
                        callBack?.autoPageStop()
                        dismissAllowingStateLoss()
                    }
                )
                AutoReadActionItem(
                    iconRes = R.drawable.ic_settings,
                    labelRes = R.string.setting,
                    textColor = textColor,
                    onClick = {
                        (activity as BaseReadBookActivity).showPageAnimConfig {
                            (activity as ReadBookActivity).upPageAnim()
                            ReadBook.loadContent(false)
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun AutoReadActionItem(
        iconRes: Int,
        labelRes: Int,
        textColor: Color,
        onClick: () -> Unit
    ) {
        val label = stringResource(labelRes)
        Column(
            modifier = Modifier
                .size(50.dp)
                .semantics { contentDescription = label }
                .clickable(onClick = onClick)
                .padding(bottom = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = textColor,
                modifier = Modifier
                    .weight(1f)
                    .size(20.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun autoPageStop()
    }
}