package io.legado.app.ui.book.import.remote

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 服务器配置
 */
class ServersDialog : BaseDialogFragment() {

    val viewModel by viewModels<ServersViewModel>()

    private val callback get() = (activity as? Callback)
    private var serversState by mutableStateOf<List<Server>>(emptyList())
    private var selectServerIdState by mutableLongStateOf(AppConfig.remoteServerId)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        selectServerIdState = AppConfig.remoteServerId
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

    private fun initData() {
        lifecycleScope.launch {
            appDb.serverDao.observeAll().catch {
                AppLog.put("服务器配置界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                serversState = it
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        callback?.onDialogDismiss("serversDialog")
    }

    @Composable
    private fun DialogContent() {
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.server_config),
                    onBack = { dismissAllowingStateLoss() },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.add),
                            iconRes = R.drawable.ic_add,
                            showAsAction = true,
                            onClick = { showDialogFragment(ServerConfigDialog()) },
                        ),
                    ),
                )
            },
            bottomBar = {
                Column {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                AppConfig.remoteServerId = DEFAULT_WEBDAV_ID
                                dismissAllowingStateLoss()
                            },
                        ) {
                            Text(text = stringResource(R.string.text_default))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { dismissAllowingStateLoss() }) {
                                Text(text = stringResource(R.string.cancel))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    AppConfig.remoteServerId = selectServerIdState
                                    dismissAllowingStateLoss()
                                },
                            ) {
                                Text(text = stringResource(R.string.ok))
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(
                    items = serversState,
                    key = { it.id },
                ) { server ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectServerIdState = server.id }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = server.id == selectServerIdState,
                                onClick = { selectServerIdState = server.id },
                            )
                            Text(
                                text = server.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                            )
                            IconButton(
                                onClick = { showDialogFragment(ServerConfigDialog(server.id)) },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.edit),
                                )
                            }
                            IconButton(
                                onClick = {
                                    alert {
                                        setTitle(R.string.draw)
                                        setMessage(R.string.sure_del)
                                        yesButton {
                                            viewModel.delete(server)
                                        }
                                        noButton()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    interface Callback {

        fun onDialogDismiss(tag: String)

    }

}