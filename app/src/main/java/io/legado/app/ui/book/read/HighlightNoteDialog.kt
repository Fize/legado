package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.BookHighlight
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout

class HighlightNoteDialog() : BaseDialogFragment(adaptationSoftKeyboard = true) {

    constructor(highlight: BookHighlight) : this() {
        arguments = Bundle().apply {
            putParcelable(ARG_HIGHLIGHT, highlight)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                    @Suppress("DEPRECATION")
                    val highlight = arguments?.getParcelable<BookHighlight>(ARG_HIGHLIGHT)
                    if (highlight != null) {
                        HighlightNoteScreen(highlight)
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        if (arguments?.getParcelable<BookHighlight>(ARG_HIGHLIGHT) == null) {
            dismiss()
        }
    }

    @Composable
    private fun HighlightNoteScreen(highlight: BookHighlight) {
        val context = requireContext()
        val bgColor = Color(context.backgroundColor)
        val textColor = Color(context.primaryTextColor)
        val topBarColor = Color(context.primaryColor)
        val accent = Color(context.accentColor)
        val errorColor = colorResource(R.color.error)
        var bookText by remember(highlight) { mutableStateOf(highlight.bookText) }
        var noteText by remember(highlight) { mutableStateOf(highlight.note) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = bgColor
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Toolbar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(topBarColor)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.highlight_note),
                            color = textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Scrollable Body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = highlight.chapterName,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = bookText,
                            onValueChange = { bookText = it },
                            label = { Text(stringResource(R.string.content)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp)
                        )
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text(stringResource(R.string.note_content)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .heightIn(min = 120.dp)
                        )
                    }

                    // Bottom Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .widthIn(min = 48.dp)
                                .clickable {
                                    ReadBook.removeHighlight(highlight)
                                    dismiss()
                                }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.delete),
                                color = errorColor
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .widthIn(min = 48.dp)
                                    .clickable { dismiss() }
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.cancel),
                                    color = accent
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .widthIn(min = 48.dp)
                                    .clickable {
                                        highlight.bookText = bookText
                                        highlight.note = noteText
                                        ReadBook.updateHighlight(highlight)
                                        dismiss()
                                    }
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.ok),
                                    color = accent
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_HIGHLIGHT = "highlight"
    }
}
