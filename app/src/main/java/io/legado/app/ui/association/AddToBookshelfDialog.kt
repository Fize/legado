package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

/**
 * 添加书籍链接到书架，需要对应网站书源
 * ${origin}/${path}, {origin: bookSourceUrl}
 * 按以下顺序尝试匹配书源并添加网址
 * - UrlOption中的指定的书源网址bookSourceUrl
 * - 在所有启用的书源中匹配orgin
 * - 在所有启用的书源中使用详情页正则匹配${origin}/${path}, {origin: bookSourceUrl}
 */
class AddToBookshelfDialog() : BaseComposeDialogFragment() {

    constructor(bookUrl: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("bookUrl", bookUrl)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    val viewModel by viewModels<ViewModel>()
    private var isLoading by mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun DialogContent() {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.add_to_bookshelf),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(18.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { dismiss() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bookUrl = arguments?.getString("bookUrl")
        if (bookUrl.isNullOrBlank()) {
            toastOnUi("url不能为空")
            dismiss()
            return
        }
        appDb.bookDao.getBook(bookUrl)?.let { //已在书架时直接跳转到书籍详情页
            AppLog.put("${it.name} 已在书架", null, true)
            startActivity<BookInfoActivity> {
                putExtra("name", it.name)
                putExtra("author", it.author)
                putExtra("bookUrl", it.bookUrl)
            }
            dismiss()
            return
        }
        viewModel.loadStateLiveData.observe(this) {
            isLoading = it
        }
        viewModel.loadErrorLiveData.observe(this) {
            toastOnUi(it)
            dismiss()
        }
        viewModel.load(bookUrl) {
            viewModel.saveSearchBook(it) {
                startActivity<BookInfoActivity> {
                    putExtra("name", it.name)
                    putExtra("author", it.author)
                    putExtra("bookUrl", it.bookUrl)
                }
                dismiss()
            }
        }
    }

    class ViewModel(application: Application) : BaseViewModel(application) {

        val loadStateLiveData = MutableLiveData<Boolean>()
        val loadErrorLiveData = MutableLiveData<String>()
        var book: Book? = null

        fun load(bookUrl: String, success: (book: Book) -> Unit) {
            execute {
                val baseUrl = NetworkUtils.getBaseUrl(bookUrl)
                    ?: throw NoStackTraceException("书籍地址格式不对")
                val urlMatcher = AnalyzeUrl.paramPattern.matcher(bookUrl)
                if (urlMatcher.find()) {
                    val origin = GSON.fromJsonObject<AnalyzeUrl.UrlOption>(
                        bookUrl.substring(urlMatcher.end())
                    ).getOrNull()?.getOrigin()
                    origin?.let {
                        val source = appDb.bookSourceDao.getBookSource(it)
                        source?.let {
                            getBookInfo(bookUrl, source)?.let { book ->
                                return@execute book
                            }
                        }
                    }
                }
                appDb.bookSourceDao.getBookSourceAddBook(baseUrl)?.let { source ->
                    getBookInfo(bookUrl, source)?.let { book ->
                        return@execute book
                    }
                }
                appDb.bookSourceDao.hasBookUrlPattern.forEach { source ->
                    try {
                        val bs = source.getBookSource()!!
                        if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                            getBookInfo(bookUrl, bs)?.let { book ->
                                return@execute book
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                throw NoStackTraceException("未找到匹配书源")
            }.onError {
                AppLog.put("添加书籍 $bookUrl 出错", it)
                loadErrorLiveData.postValue(it.localizedMessage)
            }.onSuccess {
                book = it
                success.invoke(it)
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onFinally {
                loadStateLiveData.postValue(false)
            }
        }

        private suspend fun getBookInfo(bookUrl: String, source: BookSource): Book? {
            return kotlin.runCatching {
                val book = Book(
                    bookUrl = bookUrl,
                    origin = source.bookSourceUrl,
                    originName = source.bookSourceName
                )
                WebBook.getBookInfoAwait(source, book)
            }.getOrNull()
        }

        fun saveSearchBook(book: Book, success: () -> Unit) {
            execute {
                val searchBook = book.toSearchBook()
                appDb.searchBookDao.insert(searchBook)
                searchBook
            }.onSuccess {
                success.invoke()
            }
        }

    }

}