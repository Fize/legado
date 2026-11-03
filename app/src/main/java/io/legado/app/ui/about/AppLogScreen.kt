package io.legado.app.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.http.HttpLogStore
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.share
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

class AppLogViewModel : ViewModel() {
    private val _logs = MutableStateFlow(AppLog.logs)
    val logs = _logs.asStateFlow()
    private val listener: (List<Triple<Long, String, Throwable?>>) -> Unit = { _logs.value = it }

    init { AppLog.addListener(listener) }

    fun clear() {
        AppLog.clear()
        HttpLogStore.clear()
    }

    fun detail(item: Triple<Long, String, Throwable?>): Pair<String, String>? {
        val id = HttpLogRecord.parseId(item.second)
        return when {
            id != null -> "HTTP" to (HttpLogStore.get(id)?.detail ?: item.second)
            item.third != null -> "Log" to item.third!!.stackTraceToString()
            else -> null
        }
    }

    override fun onCleared() {
        AppLog.removeListener(listener)
        super.onCleared()
    }
}

@Composable
fun AppLogScreen(onBack: () -> Unit, viewModel: AppLogViewModel = viewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showClearConfirmation by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.log),
                onBack = onBack,
                menuActions = listOf(
                    LegadoMenuAction(stringResource(R.string.clear), R.drawable.ic_clear_all) { showClearConfirmation = true },
                    LegadoMenuAction(stringResource(R.string.export), R.drawable.ic_export) {
                        val text = AppLog.exportText(logs)
                        if (text.isBlank()) {
                            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.no_log)) }
                        } else if (text.length <= 64_000) {
                            context.share(text, resources.getString(R.string.log))
                        } else {
                            runCatching {
                                File(context.cacheDir, "applog.txt").apply { writeText(text) }
                            }.onSuccess { context.share(it, "text/plain") }
                                .onFailure {
                                    scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.can_not_share)) }
                                }
                        }
                    },
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 1000.dp)) {
                itemsIndexed(logs, key = { index, entry -> "${entry.first}_$index" }) { _, entry ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable { detail = viewModel.detail(entry) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = io.legado.app.utils.LogUtils.logTimeFormat.format(Date(entry.first)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(entry.second, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.clear)) },
            text = { Text(stringResource(R.string.clear_log_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clear(); showClearConfirmation = false }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    detail?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(title) },
            text = { LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) { item { Text(body) } } },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
