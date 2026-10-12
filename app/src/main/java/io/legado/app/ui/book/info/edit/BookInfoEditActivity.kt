package io.legado.app.ui.book.info.edit

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.hasEditedNetworkCover
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.compose.component.LegadoBookCover
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.FileOutputStream

class BookInfoEditActivity :
    VMBaseComposeActivity<BookInfoEditViewModel>(),
    ChangeCoverDialog.CallBack {

    private val selectCover = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            coverChangeTo(uri)
        }
    }

    override val viewModel by viewModels<BookInfoEditViewModel>()

    private var bookNameState by mutableStateOf("")
    private var bookAuthorState by mutableStateOf("")
    private var selectedTypeIndexState by mutableIntStateOf(0)
    private var coverUrlState by mutableStateOf("")
    private var bookIntroState by mutableStateOf("")
    private var coverRefreshKey by mutableIntStateOf(0)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.bookData.observe(this) { upView(it) }
        if (viewModel.bookData.value == null) {
            intent.getStringExtra("bookUrl")?.let {
                viewModel.loadBook(it)
            }
        }
    }

    @Composable
    override fun Content() {
        val bookTypes = stringArrayResource(R.array.book_type)
        var typeMenuExpanded by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.book_info_edit),
                    onBack = { finish() },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.action_save),
                            iconRes = R.drawable.ic_save,
                            showAsAction = true,
                            onClick = ::saveData,
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    val displayCover = remember(coverUrlState, coverRefreshKey) {
                        coverUrlState.takeIf { it.isNotBlank() }
                            ?: viewModel.book?.getDisplayCover()
                    }
                    LegadoBookCover(
                        path = displayCover,
                        name = bookNameState,
                        author = bookAuthorState,
                        modifier = Modifier
                            .width(90.dp)
                            .height(130.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = bookNameState,
                            onValueChange = { bookNameState = it },
                            label = { Text(text = stringResource(R.string.book_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = bookAuthorState,
                            onValueChange = { bookAuthorState = it },
                            label = { Text(text = stringResource(R.string.author)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.book_type),
                        style = MaterialTheme.typography.bodyMedium,
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
                                text = bookTypes.getOrElse(selectedTypeIndexState) { bookTypes.firstOrNull().orEmpty() },
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
                            bookTypes.forEachIndexed { index, label ->
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
                    value = coverUrlState,
                    onValueChange = { coverUrlState = it },
                    label = { Text(text = stringResource(R.string.cover_path)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            selectCover.launch {
                                mode = HandleFileContract.IMAGE
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.select_local_image),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.bookData.value?.let {
                                showDialogFragment(
                                    ChangeCoverDialog(it.name, it.author)
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.change_cover_source),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.book?.customCoverUrl = coverUrlState
                            viewModel.book?.persistedCoverUrl = null
                            upCover()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.refresh_cover),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                OutlinedTextField(
                    value = bookIntroState,
                    onValueChange = { bookIntroState = it },
                    label = { Text(text = stringResource(R.string.book_intro)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private fun upView(book: Book) {
        bookNameState = book.name
        bookAuthorState = book.author
        selectedTypeIndexState = when {
            book.isVideo -> 4
            book.isImage -> 2
            book.isAudio -> 1
            else -> 0
        }
        coverUrlState = (book.customCoverUrl?.takeIf { it.isNotEmpty() } ?: book.coverUrl).orEmpty()
        bookIntroState = book.getDisplayIntro().orEmpty()
        upCover()
    }

    private fun upCover() {
        coverRefreshKey++
    }

    private fun saveData() {
        val book = viewModel.book ?: return
        val oldBook = book.copy()
        book.name = bookNameState
        book.author = bookAuthorState
        val local = if (book.isLocal) BookType.local else 0
        val bookType = when (selectedTypeIndexState) {
            4 -> BookType.video or local
            2 -> BookType.image or local
            1 -> BookType.audio or local
            else -> BookType.text or local
        }
        book.removeType(BookType.video, BookType.local, BookType.image, BookType.audio, BookType.text)
        book.addType(bookType)
        val customCoverUrl = coverUrlState.takeIf { it.isNotEmpty() }
        if (hasEditedNetworkCover(customCoverUrl, book.customCoverUrl, book.coverUrl)) {
            book.persistedCoverUrl = null
        }
        book.customCoverUrl = if (customCoverUrl == book.coverUrl) null else customCoverUrl
        val customIntro = bookIntroState
        book.customIntro = if (customIntro == book.intro) null else customIntro
        BookHelp.updateCacheFolder(oldBook, book)
        viewModel.saveBook(book) {
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.book?.customCoverUrl = coverUrl
        viewModel.book?.persistedCoverUrl = null
        coverUrlState = coverUrl
        upCover()
    }

    private fun coverChangeTo(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            coverChangeTo(uri.toString())
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            runCatching {
                inputStream.use {
                    var file = this.externalFiles
                    val suffix = if (fileDoc.name.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        "." + fileDoc.name.substringAfterLast(".")
                    }
                    val fileName = uri.inputStream(this).getOrThrow().use {
                        MD5Utils.md5Encode(it) + suffix
                    }
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    coverChangeTo(file.absolutePath)
                }
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
