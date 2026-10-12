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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
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

                    var bookText by remember(bookmark) { mutableStateOf(bookmark.bookText) }
                    var noteContent by remember(bookmark) { mutableStateOf(bookmark.content) }

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
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 6,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = noteContent,
                                onValueChange = { noteContent = it },
                                label = { Text(stringResource(R.string.note_content)) },
                                modifier = Modifier.fillMaxWidth(),
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
                                            bookmark.bookText = bookText
                                            bookmark.content = noteContent
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