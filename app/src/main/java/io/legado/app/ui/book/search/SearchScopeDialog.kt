package io.legado.app.ui.book.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setLayout
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchScopeDialog : BaseDialogFragment() {

    private var sourceFlowJob: Job? = null
    val callback: Callback get() = parentFragment as? Callback ?: activity as Callback

    private var groupsState by mutableStateOf<List<String>>(emptyList())
    private var screenSourcesState by mutableStateOf<List<BookSourcePart>>(emptyList())
    private var screenTextState by mutableStateOf("")
    private var isSourceModeState by mutableStateOf(false)
    private var selectedGroupsState by mutableStateOf<Set<String>>(emptySet())
    private var selectedSourceState by mutableStateOf<BookSourcePart?>(null)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
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
                    SearchScopeDialogScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
    }

    @Composable
    private fun SearchScopeDialogScreen() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                LegadoTopAppBar(
                    title = stringResource(R.string.search_scope),
                    searchQuery = if (isSourceModeState) screenTextState else null,
                    onSearchQueryChange = if (isSourceModeState) {
                        { query ->
                            screenTextState = query
                            upBookSource(query)
                        }
                    } else {
                        null
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clickable {
                                isSourceModeState = false
                            }
                            .padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = !isSourceModeState,
                            onClick = { isSourceModeState = false },
                        )
                        Text(text = stringResource(R.string.group))
                    }
                    Row(
                        modifier = Modifier.clickable {
                            isSourceModeState = true
                            upBookSource(screenTextState)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSourceModeState,
                            onClick = {
                                isSourceModeState = true
                                upBookSource(screenTextState)
                            },
                        )
                        Text(text = stringResource(R.string.book_source))
                    }
                }
                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (!isSourceModeState) {
                        items(
                            items = groupsState,
                            key = { it },
                        ) { group ->
                            val isChecked = group in selectedGroupsState
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedGroupsState = if (isChecked) {
                                            selectedGroupsState - group
                                        } else {
                                            selectedGroupsState + group
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedGroupsState = if (checked) {
                                            selectedGroupsState + group
                                        } else {
                                            selectedGroupsState - group
                                        }
                                    },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = group,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        items(
                            items = screenSourcesState,
                            key = { it.bookSourceUrl },
                        ) { source ->
                            val isSelected = selectedSourceState == source
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedSourceState = source }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedSourceState = source },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = source.bookSourceName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            callback.onSearchScopeOk(SearchScope(""))
                            dismiss()
                        },
                    ) {
                        Text(text = stringResource(R.string.all_source))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { dismiss() }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            if (!isSourceModeState) {
                                callback.onSearchScopeOk(SearchScope(selectedGroupsState.toList()))
                            } else {
                                val selectSource = selectedSourceState
                                if (selectSource != null) {
                                    callback.onSearchScopeOk(SearchScope(selectSource))
                                } else {
                                    callback.onSearchScopeOk(SearchScope(""))
                                }
                            }
                            dismiss()
                        },
                    ) {
                        Text(text = stringResource(R.string.ok))
                    }
                }
            }
        }
    }

    private fun initData() {
        viewLifecycleOwner.lifecycleScope.launch {
            groupsState = withContext(IO) {
                appDb.bookSourceDao.allEnabledGroups()
            }
        }
    }

    private fun upBookSource(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.bookSourceDao.flowAll()
                }

                else -> {
                    appDb.bookSourceDao.flowSearch(searchKey)
                }
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("多分组/书源界面更新书源出错", it)
            }.flowOn(IO).conflate().collect { data ->
                screenSourcesState = data
                delay(500)
            }
        }
    }

    interface Callback {

        /**
         * 搜索范围确认
         */
        fun onSearchScopeOk(searchScope: SearchScope)

    }

}
