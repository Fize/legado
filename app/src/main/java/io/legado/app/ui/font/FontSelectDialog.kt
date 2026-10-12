package io.legado.app.ui.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.cnCompare
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.listFileDocs
import io.legado.app.utils.openInputStream
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import java.io.File

data class FontItem(
    val doc: FileDoc,
    val privateFolder: Boolean,
)

/**
 * 字体选择对话框 (Compose)
 */
class FontSelectDialog : BaseDialogFragment() {
    private val fontRegex = Regex("(?i).*\\.[ot]tf")
    private val fontItems = mutableStateListOf<FontItem>()
    private var showSystemTypefaceDialog by mutableStateOf(false)

    private val selectFontDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                putPrefString(PreferKey.fontFolder, uri.toString())
                val doc = DocumentFile.fromTreeUri(requireContext(), uri)
                if (doc != null) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    RealPathUtil.getPath(requireContext(), uri)?.let { path ->
                        loadFontFilesByPermission(path)
                    }
                }
            } else {
                uri.path?.let { path ->
                    putPrefString(PreferKey.fontFolder, path)
                    loadFontFilesByPermission(path)
                }
            }
        }
    }
    private val importFont = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importFont)
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
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
                    val curFilePath = callBack?.curFontPath.orEmpty()
                    var menuExpanded by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorResource(R.color.background))
                    ) {
                        TopAppBar(
                            title = { Text(stringResource(R.string.select_font)) },
                            actions = {
                                TextButton(
                                    onClick = {
                                        if (!shouldSelectSystemTypeface(callBack)) {
                                            onDefaultFontChange()
                                            dismissAllowingStateLoss()
                                        } else {
                                            showSystemTypefaceDialog = true
                                        }
                                    }
                                ) {
                                    Text(
                                        text = stringResource(R.string.default_font),
                                        color = barContent
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        importFont.launch {
                                            mode = HandleFileContract.FILE
                                            title = getString(R.string.import_str)
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_import),
                                        contentDescription = stringResource(R.string.import_str),
                                        tint = barContent
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.more_menu),
                                            tint = barContent
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.other_folder)) },
                                            onClick = {
                                                menuExpanded = false
                                                openFolder()
                                            }
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = barBg,
                                titleContentColor = barContent,
                                actionIconContentColor = barContent
                            )
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(
                                items = fontItems,
                                key = { it.doc.toString() }
                            ) { item ->
                                FontItemRow(
                                    item = item,
                                    curFilePath = curFilePath,
                                    onSelect = { onFontSelect(item.doc) }
                                )
                            }
                        }
                    }

                    if (showSystemTypefaceDialog) {
                        val options = stringArrayResource(R.array.system_typefaces)
                        AlertDialog(
                            onDismissRequest = { showSystemTypefaceDialog = false },
                            title = { Text(stringResource(R.string.system_typeface)) },
                            text = {
                                Column {
                                    options.forEachIndexed { index, label ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    showSystemTypefaceDialog = false
                                                    AppConfig.systemTypefaces = index
                                                    onDefaultFontChange()
                                                    dismissAllowingStateLoss()
                                                }
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = AppConfig.systemTypefaces == index,
                                                onClick = null
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = label)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showSystemTypefaceDialog = false }) {
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
        loadFonts()
    }

    private fun loadFonts() {
        val fontPath = getPrefString(PreferKey.fontFolder)
        if (fontPath.isNullOrEmpty()) {
            loadLocalFonts(openFolderWhenEmpty = true)
        } else {
            if (fontPath.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(requireContext(), Uri.parse(fontPath))
                if (doc?.canRead() == true) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    loadLocalFonts(openFolderWhenEmpty = true)
                }
            } else {
                loadFontFilesByPermission(fontPath)
            }
        }
    }

    private fun importFont(uri: Uri) {
        val directory = getLocalFontDirectory()
        execute {
            val source = FileDoc.fromUri(uri, false)
            source.openInputStream().getOrThrow().use { input ->
                installFontFile(input, source.name, directory, ::isValidFont)
            }
        }.onSuccess {
            toastOnUi(R.string.success)
            loadFonts()
        }.onError {
            AppLog.put("导入字体失败\n${it.localizedMessage}", it)
            toastOnUi(
                if (it is IllegalArgumentException) R.string.wrong_format
                else R.string.error_read_file
            )
        }
    }

    private fun isValidFont(file: File): Boolean = kotlin.runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Typeface.Builder(file).build() != null
        } else {
            Typeface.createFromFile(file) != Typeface.DEFAULT
        }
    }.getOrDefault(false)

    private fun openFolder() {
        lifecycleScope.launch {
            val defaultPath = "SD${File.separator}Fonts"
            selectFontDir.launch {
                otherActions = arrayListOf(SelectItem(defaultPath, -1))
            }
        }
    }

    private fun loadLocalFonts(openFolderWhenEmpty: Boolean = false) {
        execute {
            mergeFontItems(arrayListOf(), getLocalFonts())
        }.onSuccess {
            if (it.isNotEmpty()) {
                fontItems.clear()
                fontItems.addAll(it)
            } else if (openFolderWhenEmpty) {
                openFolder()
            }
        }
    }

    private fun getLocalFonts(): ArrayList<FileDoc> {
        return getLocalFontDirectory().listFileDocs {
            it.name.matches(fontRegex)
        }
    }

    private fun getLocalFontDirectory(): File {
        return File(FileUtils.getPath(requireContext().externalFiles, "font"))
    }

    private fun loadFontFilesByPermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                loadFontFiles(
                    FileDoc.fromFile(File(path))
                )
            }.onDenied {
                loadLocalFonts()
            }
            .request()
    }

    private fun loadFontFiles(fileDoc: FileDoc) {
        execute {
            val fontItems = fileDoc.list {
                it.name.matches(fontRegex)
            } ?: ArrayList()
            mergeFontItems(fontItems, getLocalFonts())
        }.onSuccess {
            fontItems.clear()
            fontItems.addAll(it)
        }.onError {
            AppLog.put("加载字体文件失败\n${it.localizedMessage}", it)
            toastOnUi("getFontFiles:${it.localizedMessage}")
            loadLocalFonts()
        }
    }

    private fun mergeFontItems(
        items1: ArrayList<FileDoc>,
        items2: ArrayList<FileDoc>
    ): List<FontItem> {
        val paths = hashSetOf<String>()
        val items = ArrayList<FontItem>()
        items1.forEach { item ->
            if (paths.add(item.toString())) items.add(FontItem(item, privateFolder = false))
        }
        items2.forEach { item ->
            if (paths.add(item.toString())) items.add(FontItem(item, privateFolder = true))
        }
        return items.sortedWith { o1, o2 ->
            o1.doc.name.cnCompare(o2.doc.name).takeIf { it != 0 }
                ?: o1.privateFolder.compareTo(o2.privateFolder)
        }
    }

    fun onFontSelect(docItem: FileDoc) {
        callBack?.selectFont(docItem.toString())
        dismissAllowingStateLoss()
    }

    private fun onDefaultFontChange() {
        callBack?.selectFont("")
    }

    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    interface CallBack {
        fun selectFont(path: String)
        val curFontPath: String
        val selectSystemTypefaceOnDefault: Boolean get() = true
    }

    companion object {
        internal fun shouldSelectSystemTypeface(callBack: CallBack?): Boolean {
            return callBack?.selectSystemTypefaceOnDefault != false
        }
    }
}

@Composable
internal fun FontItemRow(
    item: FontItem,
    curFilePath: String,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    val doc = item.doc
    val selected = doc.toString() == curFilePath
    val accent = remember(context) { Color(context.accentColor) }
    val typeface = remember(doc) { loadFontTypeface(context, doc) }
    val fontFamily = remember(typeface) { FontFamily(typeface) }
    val label = stringResource(
        if (item.privateFolder) R.string.font_item_private else R.string.font_item_external,
        doc.name,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(2.dp, accent, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onSelect)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = fontFamily,
            color = colorResource(R.color.primaryText),
            modifier = Modifier
                .weight(1f)
                .padding(5.dp)
        )
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = colorResource(R.color.primaryText),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

internal fun loadFontTypeface(context: Context, doc: FileDoc): Typeface {
    return kotlin.runCatching {
        if (doc.isContentScheme) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.contentResolver
                    .openFileDescriptor(doc.uri, "r")?.use {
                        Typeface.Builder(it.fileDescriptor).build()
                    }
            } else {
                Typeface.createFromFile(RealPathUtil.getPath(context, doc.uri))
            }
        } else {
            Typeface.createFromFile(doc.uri.path!!)
        }
    }.onFailure {
        it.printOnDebug()
        AppLog.put("读取字体 ${doc.name} 出错\n${it.localizedMessage}", it, true)
    }.getOrNull() ?: Typeface.DEFAULT
}
