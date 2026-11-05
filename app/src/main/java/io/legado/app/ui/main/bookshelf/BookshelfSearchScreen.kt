package io.legado.app.ui.main.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.ui.compose.component.LegadoBookCover
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfSearchScreen(onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    var attempt by remember { mutableIntStateOf(0) }
    val source = remember(attempt) { appDb.bookDao.flowBookshelfBooks().map<List<BookshelfBook>, Result<List<BookshelfBook>>> { Result.success(it) }.catch { emit(Result.failure(it)) } }
    val result by source.collectAsStateWithLifecycle(initialValue = null)
    val matches = remember(query, result) {
        if (query.isBlank()) emptyList() else result?.getOrNull().orEmpty().filter {
            it.name.contains(query.trim(), ignoreCase = true) || it.author.contains(query.trim(), ignoreCase = true)
        }.sortedByDescending { it.durChapterTime }
    }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = {
            TextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.bookshelf_search_hint), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.testTag("shelf-search-clear")) { Icon(Icons.Default.Close, stringResource(R.string.clear)) } },
                colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().testTag("shelf-search-query"))
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                result == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                result?.isFailure == true -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.load_error_retry))
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                }
                query.isBlank() -> Text(stringResource(R.string.bookshelf_search_hint), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                matches.isEmpty() -> Text(stringResource(R.string.search_session_no_results), Modifier.align(Alignment.Center).testTag("shelf-search-empty"))
                else -> LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("shelf-search-results")) {
                    items(matches, key = { it.bookUrl }) { book ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenBook(book.bookUrl) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LegadoBookCover(book.displayCover, book.name, book.author, Modifier.width(56.dp), sourceOrigin = book.coverSourceOrigin)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(book.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
