package io.legado.app.ui.widget.dialog

import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookMemo
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.dpToPx
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.setLayout
import io.legado.app.utils.showSoftInput
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class BookMemoDialog : BaseDialogFragment() {
    private val bookUrl get() = requireArguments().getString("bookUrl")!!
    private var memo by mutableStateOf<BookMemo?>(null)
    private var editing by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var draftText by mutableStateOf("")
    private var renderJob: Job? = null
    private var restoredScroll = 0
    private var readerActivity: ReadBookActivity? = null
    private lateinit var markwon: Markwon

    private var memoScrollViewRef: ScrollView? = null
    private var memoContentViewRef: TextView? = null
    private var memoEditorRef: EditText? = null

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
        dialog?.window?.apply {
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        readerActivity?.let { it.bottomDialog-- }
        readerActivity = null
        super.onDismiss(dialog)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.post { if (view != null) setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.5f) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val textColor = ThemeStore.textColorPrimary(requireContext())
                    val composeTextColor = Color(textColor)

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.book_memo),
                                color = composeTextColor,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    if (editing) {
                                        requireContext().alert(R.string.book_memo_discard) {
                                            okButton { dismiss() }
                                            cancelButton()
                                        }
                                    } else {
                                        dismiss()
                                    }
                                },
                                enabled = !saving,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.close))
                            }
                            if (landscape) {
                                MemoActionsRow(
                                    editing = editing,
                                    loaded = loaded,
                                    saving = saving,
                                    hasContent = !memo?.content.isNullOrEmpty()
                                )
                            }
                        }

                        val currentMemo = memo
                        if (currentMemo != null) {
                            val updatedLabel = stringResource(
                                R.string.book_memo_updated,
                                DateFormat.getDateTimeInstance().format(Date(currentMemo.updatedAt))
                            )
                            Text(
                                text = updatedLabel,
                                color = composeTextColor,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )
                        }

                        if (!editing) {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                factory = { ctx ->
                                    val textView = TextView(ctx).apply {
                                        val padV = 8.dpToPx()
                                        setPadding(0, padV, 0, padV)
                                        setTextColor(textColor)
                                        setTextIsSelectable(true)
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                    }
                                    memoContentViewRef = textView
                                    ScrollView(ctx).apply {
                                        isFillViewport = true
                                        addView(
                                            textView,
                                            ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT
                                            )
                                        )
                                        memoScrollViewRef = this
                                    }
                                },
                                update = {
                                    memoContentViewRef?.setTextColor(textColor)
                                    if (::markwon.isInitialized) {
                                        renderMemo()
                                    }
                                }
                            )
                        } else {
                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                factory = { ctx ->
                                    EditText(ctx).apply {
                                        gravity = Gravity.TOP or Gravity.START
                                        setHint(R.string.book_memo_hint)
                                        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                                            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                        val pad = 8.dpToPx()
                                        setPadding(pad, pad, pad, pad)
                                        isSaveEnabled = false
                                        isVerticalScrollBarEnabled = true
                                        setTextColor(textColor)
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                        setText(draftText)
                                        memoEditorRef = this
                                    }
                                },
                                update = { editText ->
                                    editText.isEnabled = !saving
                                    editText.setTextColor(textColor)
                                    if (editText.text?.toString() != draftText) {
                                        editText.setText(draftText)
                                    }
                                }
                            )
                        }

                        if (!landscape) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                MemoActionsRow(
                                    editing = editing,
                                    loaded = loaded,
                                    saving = saving,
                                    hasContent = !memo?.content.isNullOrEmpty()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MemoActionsRow(
        editing: Boolean,
        loaded: Boolean,
        saving: Boolean,
        hasContent: Boolean
    ) {
        TextButton(
            onClick = {
                if (editing) {
                    draftText = memoEditorRef?.text?.toString().orEmpty()
                    this.editing = false
                    memoEditorRef?.hideSoftInput()
                    updateControls()
                    renderMemo()
                } else {
                    requireContext().alert(R.string.book_memo_clear_confirm) {
                        okButton { save("") }
                        cancelButton()
                    }
                }
            },
            enabled = loaded && !saving && (editing || hasContent),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(stringResource(if (editing) R.string.cancel else R.string.clear))
        }
        TextButton(
            onClick = {
                if (editing) {
                    val content = memoEditorRef?.text?.toString() ?: draftText
                    draftText = content
                    save(content)
                } else {
                    draftText = memo?.content.orEmpty()
                    this.editing = true
                    updateControls()
                    view?.post {
                        memoEditorRef?.requestFocus()
                        memoEditorRef?.showSoftInput()
                    }
                }
            },
            enabled = loaded && !saving,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(stringResource(if (editing) R.string.book_memo_save else R.string.edit))
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        readerActivity = activity as? ReadBookActivity
        readerActivity?.let { it.bottomDialog++ }
        markwon = Markwon.builder(requireContext())
            .usePlugin(GlideImagesPlugin.create(Glide.with(this)))
            .usePlugin(TablePlugin.create(HelpMarkwonTheme.tableTheme(requireContext())))
            .usePlugin(HelpMarkwonTheme.plugin(requireContext()))
            .build()
        editing = savedInstanceState?.getBoolean("editing") ?: false
        restoredScroll = savedInstanceState?.getInt("scroll") ?: 0
        draftText = savedInstanceState?.getString("draft").orEmpty()
        updateControls()
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookMemoDao.flow(bookUrl).collect {
                memo = it
                loaded = true
                updateControls()
                if (!editing) renderMemo()
            }
        }
    }

    private fun updateControls() {
        isCancelable = !editing && !saving
    }

    private fun renderMemo() {
        if (!::markwon.isInitialized) return
        val contentView = memoContentViewRef ?: return
        renderJob?.cancel()
        val content = memo?.content.orEmpty().ifEmpty { getString(R.string.book_memo_empty) }
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val rendered = withContext(IO) { markwon.toMarkdown(content) }
            markwon.setParsedMarkdown(contentView, rendered)
            if (restoredScroll > 0) {
                val scroll = restoredScroll
                restoredScroll = 0
                val scrollView = memoScrollViewRef
                scrollView?.post { scrollView.scrollTo(0, scroll) }
            }
        }
    }

    private fun save(content: String) {
        saving = true
        updateControls()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                memo = withContext(IO) {
                    appDb.bookMemoDao.save(bookUrl, content)
                    appDb.bookMemoDao.get(bookUrl)
                }
                editing = false
                memoEditorRef?.hideSoftInput()
                renderMemo()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toastOnUi(getString(R.string.book_memo_save_failed, error.localizedMessage.orEmpty()))
            } finally {
                saving = false
                if (view != null) updateControls()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editing", editing)
        outState.putString("draft", memoEditorRef?.text?.toString() ?: draftText)
        outState.putInt("scroll", memoScrollViewRef?.scrollY ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        memoScrollViewRef = null
        memoContentViewRef = null
        memoEditorRef = null
        super.onDestroyView()
    }
}
