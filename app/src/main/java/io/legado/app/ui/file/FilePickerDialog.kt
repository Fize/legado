package io.legado.app.ui.file

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.getPrimaryDisabledTextColor
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.file.HandleFileContract.Companion.FILE
import io.legado.app.ui.file.utils.FilePickerIcon
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import java.io.File

/**
 * 文件/文件夹选择对话框 (Compose)
 */
class FilePickerDialog : BaseDialogFragment() {

    companion object {
        const val tag = "FileChooserDialog"

        fun show(
            manager: FragmentManager,
            mode: Int = FILE,
            title: String? = null,
            initPath: String? = null,
            isShowHideDir: Boolean = false,
            allowExtensions: Array<String>? = null,
        ) {
            FilePickerDialog().apply {
                val bundle = Bundle()
                bundle.putInt("mode", mode)
                bundle.putString("title", title)
                bundle.putBoolean("isShowHideDir", isShowHideDir)
                bundle.putString("initPath", initPath)
                bundle.putStringArray("allowExtensions", allowExtensions)
                arguments = bundle
            }.show(manager, tag)
        }
    }

    private val viewModel by viewModels<FilePickerViewModel>()
    private val dirParent = ".."
    private val filesList = mutableStateListOf<File>()
    private val pathList = mutableStateListOf<File>()
    private var selectedFile by mutableStateOf<File?>(null)
    private var showCreateFolderDialog by mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val context = LocalContext.current
                    val barBg = Color(context.primaryColor)
                    val barContent = Color(context.primaryTextColor)
                    val primaryTextColor = remember(context) {
                        Color(context.getPrimaryTextColor(!AppConfig.isNightTheme))
                    }
                    val disabledTextColor = remember(context) {
                        Color(context.getPrimaryDisabledTextColor(!AppConfig.isNightTheme))
                    }

                    val arrowBitmap = remember {
                        ConvertUtils.toBitmap(FilePickerIcon.getArrow())!!.asImageBitmap()
                    }
                    val upBitmap = remember {
                        ConvertUtils.toBitmap(FilePickerIcon.getUpDir())!!.asImageBitmap()
                    }
                    val folderBitmap = remember {
                        ConvertUtils.toBitmap(FilePickerIcon.getFolder())!!.asImageBitmap()
                    }
                    val fileBitmap = remember {
                        ConvertUtils.toBitmap(FilePickerIcon.getFile())!!.asImageBitmap()
                    }

                    val dialogTitle = arguments?.getString("title") ?: if (viewModel.isSelectDir) {
                        stringResource(R.string.folder_chooser)
                    } else {
                        stringResource(R.string.file_chooser)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorResource(R.color.background_card))
                    ) {
                        TopAppBar(
                            title = { Text(dialogTitle) },
                            actions = {
                                IconButton(onClick = { showCreateFolderDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.CreateNewFolder,
                                        contentDescription = stringResource(R.string.create_folder),
                                        tint = barContent
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = barBg,
                                titleContentColor = barContent,
                                actionIconContentColor = barContent
                            )
                        )

                        // Breadcrumb path bar
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable {
                                            viewModel.subDocs.clear()
                                            pathList.clear()
                                            viewModel.upFiles(viewModel.rootDoc)
                                        }
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "root",
                                        color = colorResource(R.color.primaryText),
                                        fontSize = 13.sp
                                    )
                                    Image(
                                        bitmap = arrowBitmap,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            itemsIndexed(pathList) { index, dir ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable {
                                            viewModel.subDocs = ArrayList(viewModel.subDocs.subList(0, index + 1))
                                            pathList.clear()
                                            pathList.addAll(viewModel.subDocs)
                                            viewModel.upFiles(viewModel.subDocs.lastOrNull())
                                        }
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = dir.name,
                                        color = colorResource(R.color.primaryText),
                                        fontSize = 13.sp
                                    )
                                    Image(
                                        bitmap = arrowBitmap,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = colorResource(R.color.divider))

                        // File list
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(
                                items = filesList,
                                key = { it.absolutePath }
                            ) { item ->
                                val isUpDir = item == viewModel.lastDir
                                val isDir = item.isDirectory
                                val isSelected = item == selectedFile
                                val iconBitmap = when {
                                    isUpDir -> upBitmap
                                    isDir -> folderBitmap
                                    else -> fileBitmap
                                }
                                val displayName = if (isUpDir) dirParent else item.name
                                val itemTextColor = if (isDir) {
                                    primaryTextColor
                                } else if (viewModel.isSelectDir) {
                                    disabledTextColor
                                } else {
                                    val allowed = viewModel.allowExtensions
                                    if (allowed.isNullOrEmpty() || allowed.contains(FileUtils.getExtension(item.path))) {
                                        primaryTextColor
                                    } else {
                                        disabledTextColor
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (isSelected) {
                                                Modifier.border(
                                                    1.dp,
                                                    primaryTextColor,
                                                    RoundedCornerShape(2.dp)
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clickable {
                                            when {
                                                isUpDir -> {
                                                    viewModel.subDocs.removeLastOrNull()
                                                    pathList.clear()
                                                    pathList.addAll(viewModel.subDocs)
                                                    viewModel.upFiles(
                                                        viewModel.subDocs.lastOrNull() ?: viewModel.rootDoc
                                                    )
                                                }

                                                isDir -> {
                                                    viewModel.subDocs.add(item)
                                                    pathList.clear()
                                                    pathList.addAll(viewModel.subDocs)
                                                    viewModel.upFiles(item)
                                                }

                                                viewModel.isSelectFile -> {
                                                    val allowed = viewModel.allowExtensions
                                                    if (allowed.isNullOrEmpty() ||
                                                        allowed.contains(FileUtils.getExtension(item.path))
                                                    ) {
                                                        selectedFile = item
                                                    }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(
                                        bitmap = iconBitmap,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = displayName,
                                        color = itemTextColor,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = colorResource(R.color.divider)
                                )
                            }
                        }

                        // Footer OK button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    if (viewModel.isSelectDir) {
                                        viewModel.lastDir?.let {
                                            setResultData(it.path)
                                            dismissAllowingStateLoss()
                                        }
                                    } else {
                                        val file = selectedFile
                                        if (file == null) {
                                            toastOnUi("请选择文件")
                                        } else {
                                            setResultData(file.path)
                                            dismissAllowingStateLoss()
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(android.R.string.ok))
                            }
                        }
                    }

                    if (showCreateFolderDialog) {
                        var folderName by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showCreateFolderDialog = false },
                            title = { Text(stringResource(R.string.create_folder)) },
                            text = {
                                OutlinedTextField(
                                    value = folderName,
                                    onValueChange = { folderName = it },
                                    label = { Text("文件夹名") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (folderName.isBlank()) {
                                            toastOnUi("文件夹名不能为空")
                                        } else {
                                            showCreateFolderDialog = false
                                            viewModel.createFolder(folderName.trim())
                                        }
                                    }
                                ) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCreateFolderDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.filesLiveData.observe(viewLifecycleOwner) {
            selectedFile = null
            filesList.clear()
            filesList.addAll(it)
            pathList.clear()
            pathList.addAll(viewModel.subDocs)
        }
        viewModel.initData(arguments)
    }

    private fun setResultData(path: String) {
        val data = Intent().setData(Uri.fromFile(File(path)))
        (parentFragment as? CallBack)?.onResult(data)
        (activity as? CallBack)?.onResult(data)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        activity?.finish()
    }

    interface CallBack {
        fun onResult(data: Intent)
    }
}