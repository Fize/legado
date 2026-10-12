package io.legado.app.ui.book.group

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.compose.component.LegadoBookCover
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

class GroupEditDialog() : BaseComposeDialogFragment() {

    constructor(bookGroup: BookGroup? = null) : this() {
        arguments = Bundle().apply {
            putParcelable("group", bookGroup?.copy())
        }
    }

    private val viewModel by viewModels<GroupViewModel>()
    private var bookGroup: BookGroup? = null

    private var groupNameState by mutableStateOf("")
    private var coverPathState by mutableStateOf<String?>(null)
    private var sortSelectionState by mutableIntStateOf(0)
    private var enableRefreshState by mutableStateOf(true)
    private var onlyUpdateReadState by mutableStateOf(false)

    private val selectImage = registerForActivityResult(HandleFileContract()) {
        val uri = it.uri ?: return@registerForActivityResult
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            coverPathState = uri.toString()
            return@registerForActivityResult
        }
        readUri(uri) { fileDoc, inputStream ->
            try {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = it.uri.inputStream(requireContext()).getOrThrow().use { tmp ->
                    MD5Utils.md5Encode(tmp) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                coverPathState = file.absolutePath
            } catch (e: Exception) {
                appCtx.toastOnUi(e.localizedMessage)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        bookGroup = arguments?.getParcelable("group")
        val sortCount = resources.getStringArray(R.array.book_sort).size
        bookGroup?.let {
            groupNameState = it.groupName
            coverPathState = it.cover
            if (it.bookSort + 1 !in 0..<sortCount) {
                it.bookSort = -1
            }
            sortSelectionState = it.bookSort + 1
            enableRefreshState = it.enableRefresh
            onlyUpdateReadState = it.onlyUpdateRead
        }
    }

    private fun deleteGroup(ok: () -> Unit) {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                ok.invoke()
            }
            noButton()
        }
    }

    @Composable
    override fun DialogContent() {
        val sortOptions = stringArrayResource(R.array.book_sort)
        var showSortDropdown by remember { mutableStateOf(false) }
        val group = bookGroup
        val showDelete = group != null && (group.groupId > 0 || group.groupId == Long.MIN_VALUE)

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                LegadoTopAppBar(
                    title = if (group == null) {
                        stringResource(R.string.add_group)
                    } else {
                        stringResource(R.string.group_edit)
                    },
                    onNavigationClick = { dismiss() },
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LegadoBookCover(
                            path = coverPathState,
                            name = groupNameState,
                            author = null,
                            loadOnlyWifi = false,
                            modifier = Modifier
                                .width(72.dp)
                                .height(100.dp)
                                .clickable {
                                    if (!coverPathState.isNullOrEmpty()) {
                                        val actions = arrayListOf(
                                            getString(R.string.select_image),
                                            getString(R.string.delete)
                                        )
                                        context?.selector(items = actions) { _, i ->
                                            when (i) {
                                                0 -> selectImage.launch {
                                                    mode = HandleFileContract.IMAGE
                                                }
                                                1 -> coverPathState = null
                                            }
                                        }
                                    } else {
                                        selectImage.launch {
                                            mode = HandleFileContract.IMAGE
                                        }
                                    }
                                },
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = groupNameState,
                                onValueChange = { groupNameState = it },
                                label = { Text(stringResource(R.string.group_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { showSortDropdown = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "${stringResource(R.string.sort)}: ${
                                            sortOptions.getOrElse(sortSelectionState) { "" }
                                        }"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSortDropdown,
                                    onDismissRequest = { showSortDropdown = false },
                                ) {
                                    sortOptions.forEachIndexed { idx, label ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                sortSelectionState = idx
                                                showSortDropdown = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { enableRefreshState = !enableRefreshState },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = enableRefreshState,
                            onCheckedChange = { enableRefreshState = it },
                        )
                        Text(
                            text = stringResource(R.string.allow_drop_down_refresh),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onlyUpdateReadState = !onlyUpdateReadState },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = onlyUpdateReadState,
                            onCheckedChange = { onlyUpdateReadState = it },
                        )
                        Text(
                            text = stringResource(R.string.only_update_read),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showDelete) {
                            TextButton(
                                onClick = {
                                    deleteGroup {
                                        bookGroup?.let {
                                            viewModel.delGroup(it) {
                                                dismiss()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(stringResource(R.string.delete))
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { dismiss() }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val groupName = groupNameState.trim()
                                if (groupName.isEmpty()) {
                                    toastOnUi("分组名称不能为空")
                                } else if (bookGroup == null && !appDb.bookGroupDao.canAddGroup) {
                                    toastOnUi("分组已达上限(63个)")
                                } else {
                                    val bookSort = sortSelectionState - 1
                                    val coverPath = coverPathState
                                    val enableRefresh = enableRefreshState
                                    val onlyUpdateRead = onlyUpdateReadState
                                    bookGroup?.let {
                                        it.groupName = groupName
                                        it.cover = coverPath
                                        it.bookSort = bookSort
                                        it.enableRefresh = enableRefresh
                                        it.onlyUpdateRead = onlyUpdateRead
                                        viewModel.upGroup(it) {
                                            dismiss()
                                        }
                                    } ?: let {
                                        viewModel.addGroup(
                                            groupName,
                                            bookSort,
                                            enableRefresh,
                                            onlyUpdateRead,
                                            coverPath
                                        ) {
                                            dismiss()
                                        }
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                }
            }
        }
    }
}
