package io.legado.app.ui.book.import.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Server
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.setLayout

class ServerConfigDialog() : BaseDialogFragment(0, true) {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<ServerConfigViewModel>()

    private var nameState by mutableStateOf("")
    private var selectedTypeIndexState by mutableIntStateOf(0)
    private var urlState by mutableStateOf("")
    private var usernameState by mutableStateOf("")
    private var passwordState by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewModel.init(arguments?.getLong("id")) {
            upConfigView(viewModel.mServer)
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DialogContent()
                }
            }
        }
    }

    private fun upConfigView(server: Server?) {
        nameState = server?.name.orEmpty()
        selectedTypeIndexState = 0
        val config = server?.getConfigJsonObject()
        urlState = config?.optString("url").orEmpty()
        usernameState = config?.optString("username").orEmpty()
        passwordState = config?.optString("password").orEmpty()
    }

    private fun getServer(): Server {
        val server = viewModel.mServer?.copy() ?: Server()
        server.name = nameState
        server.type = Server.TYPE.WEBDAV
        val data = hashMapOf(
            "url" to urlState,
            "username" to usernameState,
            "password" to passwordState,
        )
        server.config = GSON.toJson(data)
        return server
    }

    @Composable
    private fun DialogContent() {
        val serverTypes = stringArrayResource(R.array.server_type)
        var typeMenuExpanded by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.server_config),
                    onBack = { dismissAllowingStateLoss() },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.action_save),
                            iconRes = R.drawable.ic_save,
                            showAsAction = true,
                            onClick = {
                                getServer().let {
                                    viewModel.save(it) {
                                        dismissAllowingStateLoss()
                                    }
                                }
                            },
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = nameState,
                    onValueChange = { nameState = it },
                    label = { Text(text = stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TYPE",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box {
                        Row(
                            modifier = Modifier
                                .clickable { typeMenuExpanded = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = serverTypes.getOrElse(selectedTypeIndexState) { serverTypes.firstOrNull().orEmpty() },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = typeMenuExpanded,
                            onDismissRequest = { typeMenuExpanded = false },
                        ) {
                            serverTypes.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(text = label) },
                                    onClick = {
                                        selectedTypeIndexState = index
                                        typeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = urlState,
                    onValueChange = { urlState = it },
                    label = { Text(text = "url") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = usernameState,
                    onValueChange = { usernameState = it },
                    label = { Text(text = "username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passwordState,
                    onValueChange = { passwordState = it },
                    label = { Text(text = "password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

}