package io.legado.app.ui.association

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.navigation.OpenUrlConfirmRoute
import io.legado.app.utils.toastOnUi

@Composable
fun OpenUrlConfirmScreen(
    route: OpenUrlConfirmRoute,
    onDismiss: () -> Unit,
    viewModel: OpenUrlConfirmViewModel = viewModel(),
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val sourceOrigin = route.sourceOrigin.orEmpty()
    val sourceName = route.sourceName.orEmpty()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LegadoTopAppBar(
                title = "跳转确认",
                subtitle = sourceName.takeIf { it.isNotBlank() },
                actions = {
                    if (sourceOrigin.isNotBlank()) {
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.testTag("openUrlConfirmMore"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu),
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.disable_source)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.disableSource(sourceOrigin, route.sourceType, onDismiss)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_source)) },
                                    onClick = {
                                        showMenu = false
                                        showDeleteConfirmation = true
                                    },
                                )
                            }
                        }
                    }
                },
            )

            Text(
                text = "正在请求跳转链接/应用，是否跳转？",
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = {
                        try {
                            val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                                if (!route.mimeType.isNullOrBlank()) {
                                    setDataAndType(route.uri.toUri(), route.mimeType)
                                } else {
                                    data = route.uri.toUri()
                                }
                            }
                            if (targetIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(targetIntent)
                            } else {
                                context.toastOnUi(R.string.can_not_open)
                            }
                        } catch (error: Exception) {
                            AppLog.put("打开链接失败", error, true)
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.sure_del) + "\n" + sourceName) },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteSource(sourceOrigin, route.sourceType, onDismiss)
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}
