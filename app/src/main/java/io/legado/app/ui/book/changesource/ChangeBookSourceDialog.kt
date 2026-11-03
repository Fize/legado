package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity

/** Temporary host for callers that have not moved to a Compose destination yet. */
class ChangeBookSourceDialog() : BaseDialogFragment() {

    constructor(name: String, author: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
        }
    }

    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeBookSourceViewModel by viewModels()

    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            val origin = it.data?.getStringExtra("origin") ?: return@registerForActivityResult
            viewModel.startSearch(origin)
        }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LegadoTheme {
                ChangeBookSourceScreen(
                    viewModel = viewModel,
                    oldBookType = callBack?.oldBook?.type ?: 0,
                    oldBookUrl = callBack?.oldBook?.bookUrl,
                    onBack = ::dismissAllowingStateLoss,
                    onSourceManage = { startActivity<BookSourceActivity>() },
                    onEditSource = ::editSource,
                    onResult = ::handleChangeSourceResult,
                )
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.initData(arguments, callBack?.oldBook, activity is ReadBookActivity)
    }

    private fun editSource(searchBook: SearchBook) {
        editSourceResult.launch { putExtra("sourceUrl", searchBook.origin) }
    }

    private fun handleChangeSourceResult(result: SourceChangeResult) {
        when (result) {
            is SourceChangeResult.Success -> {
                val callback = callBack ?: return
                val completion = SourceChangeCompletion(
                    result.deleteAfterChange,
                    viewModel::del,
                )
                callback.changeTo(
                    result.source,
                    result.book,
                    result.toc,
                    completion::success,
                )
                if (result.dismissDialog) dismissAllowingStateLoss()
            }

            is SourceChangeResult.Error -> AppLog.put(
                "换源获取目录出错\n${result.throwable.localizedMessage}",
                result.throwable,
                true,
            )
        }
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.SOURCE_CHANGED) {
            viewModel.currentSourceChanged(callBack?.oldBook?.bookUrl)
        }
    }

    interface CallBack {
        val oldBook: Book?

        fun changeTo(
            source: BookSource,
            book: Book,
            toc: List<BookChapter>,
            onSuccess: () -> Unit,
        )
    }
}
