package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

class OpenUrlConfirmDialog() : BaseComposeDialogFragment() {

    constructor(
        uri: String,
        mimeType: String?,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int
    ) : this() {
        arguments = Bundle().apply {
            putString("uri", uri)
            putString("mimeType", mimeType)
            putString("sourceOrigin", sourceOrigin)
            putString("sourceName", sourceName)
            putInt("sourceType", sourceType)
        }
    }

    val viewModel by viewModels<OpenUrlConfirmViewModel>()
    private var subtitleState by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val arguments = arguments ?: return
        viewModel.initData(arguments)
        if (viewModel.uri.isBlank()) {
            dismiss()
            return
        }
        subtitleState = viewModel.sourceName
    }

    @Composable
    override fun DialogContent() {
        var showMenu by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LegadoTopAppBar(
                    title = "跳转确认",
                    subtitle = subtitleState.takeIf { it.isNotBlank() },
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.disable_source)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.disableSource {
                                            dismiss()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_source)) },
                                    onClick = {
                                        showMenu = false
                                        alert(R.string.draw) {
                                            setMessage(getString(R.string.sure_del) + "\n" + viewModel.sourceName)
                                            noButton()
                                            yesButton {
                                                viewModel.deleteSource {
                                                    dismiss()
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                )

                Text(
                    text = "正在请求跳转链接/应用，是否跳转？",
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { dismiss() }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            openUrl()
                            dismiss()
                        }
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }

    private fun openUrl() {
        try {
            val uri = viewModel.uri.toUri()
            val mimeType = viewModel.mimeType
            val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                if (!mimeType.isNullOrBlank()) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (targetIntent.resolveActivity(appCtx.packageManager) != null) {
                startActivity(targetIntent)
            } else {
                toastOnUi(R.string.can_not_open)
            }
        } catch (e: Exception) {
            AppLog.put("打开链接失败", e, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activity?.finish()
    }

}
