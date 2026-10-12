package io.legado.app.ui.book.audio.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.help.book.savePreservingCustomCoverUrl
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class AudioSkipCredits : BaseDialogFragment() {

    companion object {
        private var bookRef: WeakReference<Book>? = null

        fun newInstance(book: Book): AudioSkipCredits {
            return AudioSkipCredits().apply {
                bookRef = WeakReference(book)
            }
        }
    }

    private val book: Book by lazy {
        bookRef?.get() ?: throw IllegalStateException("Book reference lost")
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    val context = LocalContext.current
                    val accent = Color(context.accentColor)
                    var isGlobal by remember { mutableStateOf(book.isAudioSkipUsingGlobal()) }
                    var openCredits by remember { mutableIntStateOf(book.getOpenCredits()) }
                    var closeCredits by remember { mutableIntStateOf(book.getCloseCredits()) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(R.color.background))
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.skip_book_credits),
                            color = accent,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ScopeChip(
                                text = stringResource(R.string.audio_skip_scope_book),
                                selected = !isGlobal,
                                accentColor = accent,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    isGlobal = false
                                    book.setAudioSkipUsingGlobal(false)
                                    openCredits = book.getOpenCredits()
                                    closeCredits = book.getCloseCredits()
                                }
                            )
                            ScopeChip(
                                text = stringResource(R.string.audio_skip_scope_global),
                                selected = isGlobal,
                                accentColor = accent,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    isGlobal = true
                                    book.setAudioSkipUsingGlobal(true)
                                    openCredits = book.getOpenCredits()
                                    closeCredits = book.getCloseCredits()
                                }
                            )
                        }

                        CreditsSeekBarRow(
                            title = stringResource(R.string.audio_skip_opening_seconds),
                            value = openCredits,
                            max = 180,
                            accentColor = accent,
                            onValueChange = { value ->
                                openCredits = value
                                if (isGlobal) {
                                    AppConfig.audioSkipOpenCredits = value
                                } else {
                                    book.setOpenCredits(value)
                                }
                            }
                        )

                        CreditsSeekBarRow(
                            title = stringResource(R.string.audio_skip_closing_seconds),
                            value = closeCredits,
                            max = 180,
                            accentColor = accent,
                            onValueChange = { value ->
                                closeCredits = value
                                if (isGlobal) {
                                    AppConfig.audioSkipCloseCredits = value
                                } else {
                                    book.setCloseCredits(value)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        book.savePreservingCustomCoverUrl()
    }
}

@Composable
private fun ScopeChip(
    text: String,
    selected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(
                width = 1.dp,
                color = if (selected) accentColor else colorResource(R.color.divider),
                shape = shape
            )
            .background(if (selected) accentColor.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) accentColor else colorResource(R.color.primaryText),
            maxLines = 1
        )
    }
}

@Composable
private fun CreditsSeekBarRow(
    title: String,
    value: Int,
    max: Int,
    accentColor: Color,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = colorResource(R.color.primaryText),
                fontSize = 14.sp
            )
            Text(
                text = value.toString(),
                color = colorResource(R.color.secondaryText),
                fontSize = 14.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (value > 0) onValueChange(value - 1) }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_reduce),
                    contentDescription = null,
                    tint = colorResource(R.color.primaryText)
                )
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(0, max)) },
                valueRange = 0f..max.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { if (value < max) onValueChange(value + 1) }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    tint = colorResource(R.color.primaryText)
                )
            }
        }
    }
}
