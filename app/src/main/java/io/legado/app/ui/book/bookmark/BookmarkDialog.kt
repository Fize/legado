package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookmarkDialog() : BaseDialogFragment(adaptationSoftKeyboard = true) {

    private var bookText by mutableStateOf(TextFieldValue())
    private var noteContent by mutableStateOf(TextFieldValue())
    private var focusedEditor = -1
    private var restoreFocusEditor = -1
    private var restoredBookSelection = TextRange.Zero
    private var restoredNoteSelection = TextRange.Zero
    private var pausedBookText: TextFieldValue? = null
    private var pausedNoteContent: TextFieldValue? = null
    private var pausedFocusedEditor: Int? = null

    private fun restoreDraft(state: Bundle?, key: String, original: String): TextFieldValue {
        val text = state?.getString(key) ?: original
        val start = state?.getInt("${key}SelectionStart", text.length) ?: text.length
        val end = state?.getInt("${key}SelectionEnd", text.length) ?: text.length
        return TextFieldValue(text, TextRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length)))
    }

    private fun saveDraft(state: Bundle, key: String, value: TextFieldValue) {
        state.putString(key, value.text)
        state.putInt("${key}SelectionStart", value.selection.start)
        state.putInt("${key}SelectionEnd", value.selection.end)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val bookmark = arguments?.getParcelable<Bookmark>("bookmark")
        bookText = restoreDraft(savedInstanceState, "draftBookText", bookmark?.bookText.orEmpty())
        noteContent = restoreDraft(savedInstanceState, "draftNoteContent", bookmark?.content.orEmpty())
        focusedEditor = savedInstanceState?.getInt("focusedEditor", -1) ?: -1
        restoreFocusEditor = focusedEditor
        restoredBookSelection = bookText.selection
        restoredNoteSelection = noteContent.selection
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Hiding the dialog can collapse selection before Fragment state is saved.
        // Keep the last active selection, while accepting any final IME text commit.
        saveDraft(outState, "draftBookText", pausedBookText?.takeIf { it.text == bookText.text } ?: bookText)
        saveDraft(outState, "draftNoteContent", pausedNoteContent?.takeIf { it.text == noteContent.text } ?: noteContent)
        outState.putInt("focusedEditor", pausedFocusedEditor ?: focusedEditor)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        pausedBookText = bookText
        pausedNoteContent = noteContent
        pausedFocusedEditor = focusedEditor
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        pausedBookText = null
        pausedNoteContent = null
        pausedFocusedEditor = null
    }

    constructor(bookmark: Bookmark, editPos: Int = -1) : this() {
        arguments = Bundle().apply {
            putInt("editPos", editPos)
            putParcelable("bookmark", bookmark)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    @Suppress("DEPRECATION")
                    val bookmark = arguments?.getParcelable<Bookmark>("bookmark")
                    val editPos = arguments?.getInt("editPos", -1) ?: -1
                    if (bookmark == null) {
                        dismissAllowingStateLoss()
                        return@LegadoTheme
                    }

                    val bookFocus = remember { FocusRequester() }
                    val noteFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        // Restore after the new dialog owns its focus targets;
                        // the platform otherwise focuses the first editor.
                        val editor = restoreFocusEditor
                        restoreFocusEditor = -1
                        if (editor == 0) {
                            bookFocus.requestFocus()
                            bookText = bookText.copy(selection = restoredBookSelection)
                        } else if (editor == 1) {
                            noteFocus.requestFocus()
                            noteContent = noteContent.copy(selection = restoredNoteSelection)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .padding(16.dp)
                            .widthIn(max = 520.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = stringResource(R.string.bookmark),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = bookmark.chapterName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = bookText,
                                onValueChange = { bookText = it },
                                label = { Text(stringResource(R.string.content)) },
                                modifier = Modifier.fillMaxWidth().focusRequester(bookFocus)
                                    .onFocusChanged { if (it.isFocused) focusedEditor = 0 },
                                maxLines = 6,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = noteContent,
                                onValueChange = { noteContent = it },
                                label = { Text(stringResource(R.string.note_content)) },
                                modifier = Modifier.fillMaxWidth().focusRequester(noteFocus)
                                    .onFocusChanged { if (it.isFocused) focusedEditor = 1 },
                                maxLines = 6,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (editPos >= 0) {
                                    TextButton(
                                        onClick = {
                                            lifecycleScope.launch {
                                                withContext(IO) {
                                                    appDb.bookmarkDao.delete(bookmark)
                                                }
                                                dismiss()
                                            }
                                        },
                                    ) {
                                        Text(
                                            text = stringResource(R.string.delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier)
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { dismiss() }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                    TextButton(
                                        onClick = {
                                            bookmark.bookText = bookText.text
                                            bookmark.content = noteContent.text
                                            lifecycleScope.launch {
                                                withContext(IO) {
                                                    appDb.bookmarkDao.insert(bookmark)
                                                }
                                                dismiss()
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.ok))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
