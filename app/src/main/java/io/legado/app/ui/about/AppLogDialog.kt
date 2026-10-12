package io.legado.app.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.help.http.HttpLogRecord
import io.legado.app.help.http.HttpLogStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import java.io.File
import java.util.Date

class AppLogDialog : BaseDialogFragment() {

    companion object {
        private const val MAX_SHARE_TEXT = 64_000
    }

    private var logsState by mutableStateOf<List<Triple<Long, String, Throwable?>>>(emptyList())

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        logsState = AppLog.logs.toList()
        observeEvent<Boolean>(EventBus.APP_LOG_UPDATED) {
            logsState = AppLog.logs.toList()
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LegadoTopAppBar(
                                title = stringResource(R.string.log),
                                onBack = { dismiss() },
                                menuActions = listOf(
                                    LegadoMenuAction(
                                        title = stringResource(R.string.clear),
                                        iconRes = R.drawable.ic_clear_all,
                                        onClick = ::clearLogs,
                                    ),
                                    LegadoMenuAction(
                                        title = stringResource(R.string.export),
                                        iconRes = R.drawable.ic_export,
                                        onClick = ::exportLogs,
                                    ),
                                ),
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 520.dp),
                            ) {
                                itemsIndexed(
                                    items = logsState,
                                    key = { index, item -> "${item.first}_$index" },
                                ) { _, item ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onLogItemClick(item) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                    ) {
                                        Text(
                                            text = LogUtils.logTimeFormat.format(Date(item.first)),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.second,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun clearLogs() {
        alert(R.string.clear, R.string.clear_log_confirm) {
            yesButton {
                AppLog.clear()
                HttpLogStore.clear()
                logsState = emptyList()
            }
            noButton()
        }
    }

    private fun onLogItemClick(item: Triple<Long, String, Throwable?>) {
        val httpId = HttpLogRecord.parseId(item.second)
        val httpRecord = httpId?.let(HttpLogStore::get)
        when {
            httpId != null -> {
                showDialogFragment(TextDialog("HTTP", httpRecord?.detail ?: item.second))
            }

            else -> item.third?.let { throwable ->
                showDialogFragment(TextDialog("Log", throwable.stackTraceToString()))
            }
        }
    }

    private fun exportLogs() {
        val text = AppLog.exportText(AppLog.logs)
        if (text.isBlank()) {
            toastOnUi(R.string.no_log)
            return
        }
        if (text.length <= MAX_SHARE_TEXT) {
            requireContext().share(text, getString(R.string.log))
            return
        }
        runCatching {
            val file = File(requireContext().cacheDir, "applog.txt")
            file.writeText(text)
            requireContext().share(file, "text/plain")
        }.onFailure {
            toastOnUi(R.string.can_not_share)
        }
    }

}
