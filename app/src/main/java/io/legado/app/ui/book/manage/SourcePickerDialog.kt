package io.legado.app.ui.book.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.setLayout
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 书源选择
 */
class SourcePickerDialog : BaseDialogFragment() {

    private var sourceFlowJob: Job? = null
    private var sourcesState by mutableStateOf<List<BookSourcePart>>(emptyList())
    private var searchKeyState by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        initData()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DialogContent()
                }
            }
        }
    }

    private fun initData(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.bookSourceDao.flowEnabled()
                else -> appDb.bookSourceDao.flowSearchEnabled(searchKey)
            }.catch {
                AppLog.put("书源选择界面获取书源数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                sourcesState = it
            }
        }
    }

    @Composable
    private fun DialogContent() {
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = "选择书源",
                    onBack = { dismissAllowingStateLoss() },
                    searchQuery = searchKeyState,
                    onSearchQueryChange = {
                        searchKeyState = it
                        initData(it)
                    },
                    searchPlaceholder = stringResource(R.string.search_book_source),
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.change_source_delay),
                            onClick = {
                                NumberPickerDialog(requireContext())
                                    .setTitle(getString(R.string.change_source_delay))
                                    .setMaxValue(9999)
                                    .setMinValue(0)
                                    .setValue(AppConfig.batchChangeSourceDelay)
                                    .show {
                                        AppConfig.batchChangeSourceDelay = it
                                    }
                            },
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(
                    items = sourcesState,
                    key = { it.bookSourceUrl },
                ) { item ->
                    Column {
                        Text(
                            text = item.getDisPlayNameGroup(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    item.getBookSource()?.let { source ->
                                        callback?.sourceOnClick(source)
                                    }
                                    dismissAllowingStateLoss()
                                }
                                .padding(16.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    private val callback: Callback?
        get() {
            return (parentFragment as? Callback) ?: activity as? Callback
        }

    interface Callback {
        fun sourceOnClick(source: BookSource)
    }

}