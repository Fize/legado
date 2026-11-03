package io.legado.app.ui.book.searchContent

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.ui.navigation.SearchContentRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContentScreen(route: SearchContentRoute,onBack: () -> Unit,onResult: (Intent) -> Unit,
    viewModel: SearchContentViewModel=viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentResult by rememberUpdatedState(onResult)
    val currentBack by rememberUpdatedState(onBack)
    val focus=LocalFocusManager.current
    val list=rememberLazyListState()
    LaunchedEffect(route,viewModel) { viewModel.initialize(route) }
    LaunchedEffect(state.selection) { state.selection?.let { value ->
        if(value.delivered) currentBack() else currentResult(Intent().putExtra("contentSearchSessionId",value.sessionId).putExtra("contentSearchOrdinal",value.ordinal))
        viewModel.selectionHandled(value)
    } }
    var shownPage by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(topBar={ TopAppBar(title={ Text(stringResource(R.string.search_content)) },navigationIcon={
        IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.back)) }
    }) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // In short windows the controls can consume the whole viewport,
            // leaving a zero-height results list. Keep them reachable by
            // scrolling the header together with the results in that case.
            val compact = maxHeight < 320.dp
            LaunchedEffect(state.page,state.session?.generation,state.hits.isEmpty()) {
                val session=state.session
                if(session!=null && state.hits.isNotEmpty()) {
                    val key="${session.generation}:${state.page}"
                    if(shownPage!=key) {
                        val offset=if(shownPage==null && route.initialIndex/100==state.page) route.initialIndex%100 else 0
                        list.scrollToItem(offset.coerceIn(0,state.hits.lastIndex) + if(compact) 1 else 0)
                        shownPage=key
                    }
                }
            }
            val controls: @Composable () -> Unit = { Column {
            OutlinedTextField(state.query,viewModel::editQuery,Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("content_search_query"),
                singleLine=true,enabled=!state.loading,label={ Text(stringResource(R.string.search)) },
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={ focus.clearFocus(); viewModel.submit() }),
                trailingIcon={ IconButton(onClick={ focus.clearFocus(); viewModel.submit() },enabled=!state.loading,modifier=Modifier.testTag("content_search_submit")) {
                    Icon(Icons.Default.Search,stringResource(R.string.search))
                } })
            state.session?.let { session ->
                FlowRow(Modifier.padding(horizontal=8.dp)) {
                    FilterChip(session.regex,{ viewModel.submit(regex=!session.regex) },label={ Text(stringResource(R.string.regex)) })
                    FilterChip(session.matchCase,{ viewModel.submit(matchCase=!session.matchCase) },label={ Text(stringResource(R.string.content_search_match_case)) })
                    FilterChip(session.replace,{ viewModel.submit(replace=!session.replace) },label={ Text(stringResource(R.string.replace)) })
                    if(session.running) TextButton(onClick=viewModel::stop,modifier=Modifier.testTag("content_search_stop")) { Text(stringResource(R.string.stop)) }
                    else if(!session.complete && session.query.isNotEmpty()) TextButton(onClick=viewModel::resume) { Text(stringResource(R.string.resume)) }
                }
                if(session.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.search_content_size)+": ${session.resultCount}",Modifier.padding(horizontal=16.dp).testTag("content_search_count"))
                Text(stringResource(R.string.content_search_progress,session.searchedChapters,session.totalChapters,session.skippedChapters),
                    Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
            }
            state.error?.let { error ->
                val text=when {
                    error=="Book does not exist" -> stringResource(R.string.no_book)
                    error=="Book has no chapters" -> stringResource(R.string.no_chapter)
                    error=="Query is too long" -> stringResource(R.string.content_search_query_too_long)
                    error.contains("changed",true) -> stringResource(R.string.content_search_changed)
                    error=="Search session expired" -> stringResource(R.string.search_session_interrupted)
                    error.contains("expired",true) -> stringResource(R.string.reader_return_request_expired)
                    else -> stringResource(R.string.content_search_failed)
                }
                Text(text,Modifier.padding(16.dp).testTag("content_search_error"),color=MaterialTheme.colorScheme.error)
                TextButton(onClick=viewModel::retry) { Text(stringResource(R.string.retry)) }
            }
            if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.session?.complete==true && state.hits.isEmpty()) Text(stringResource(R.string.search_content_empty),Modifier.padding(16.dp))
            } }
            val pagination: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={ viewModel.page(-1) },enabled=state.page>0) { Text(stringResource(R.string.prev_page)) }
                    Text("${state.page+1}/${(((state.session?.resultCount ?: 0)-1)/100+1).coerceAtLeast(1)}")
                    TextButton(onClick={ viewModel.page(1) },enabled=(state.page+1)*100<(state.session?.resultCount ?: 0)) { Text(stringResource(R.string.next_page)) }
                }
            }
            val results: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                items(state.hits,key={ "${it.generation}:${it.ordinal}" }) { hit ->
                    val highlight=MaterialTheme.colorScheme.primary
                    val caption=remember(hit,highlight) { buildAnnotatedString {
                        append(hit.chapterTitle); append('\n'); val offset=length; append(hit.snippet)
                        if(hit.snippetStart<hit.snippetEnd) addStyle(SpanStyle(color=highlight,fontWeight=FontWeight.Bold),
                            offset+hit.snippetStart,offset+hit.snippetEnd)
                    } }
                    Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).clickable(enabled=state.error==null) { viewModel.select(hit) }
                        .testTag("content_search_hit_${hit.ordinal}"),shape=MaterialTheme.shapes.medium,tonalElevation=1.dp) {
                        Text(caption,Modifier.padding(12.dp))
                    }
                }
            }
            if(compact) {
                LazyColumn(Modifier.fillMaxSize().testTag("content_search_results"),state=list) {
                    item(key="search-controls") { controls() }
                    results()
                    item(key="search-pagination") { pagination() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    controls()
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("content_search_results"),state=list,content=results)
                    pagination()
                }
            }
        }
    }
}
