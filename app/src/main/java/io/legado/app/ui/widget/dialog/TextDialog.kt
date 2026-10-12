package io.legado.app.ui.widget.dialog

import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.CacheManager
import io.legado.app.help.HelpSection
import io.legado.app.help.IntentData
import io.legado.app.help.findTextRanges
import io.legado.app.help.parseHelpSections
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setHtml
import io.legado.app.utils.setLayout
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextDialog() : BaseDialogFragment() {

    enum class Mode {
        MD, HTML, TEXT
    }

    constructor(
        title: String,
        content: String?,
        mode: Mode = Mode.TEXT,
        time: Long = 0,
        autoClose: Boolean = false,
        showToc: Boolean = false
    ) : this() {
        val tocEnabled = showToc && mode == Mode.MD
        arguments = Bundle().apply {
            putString("title", title)
            putString("content", if (tocEnabled) content else IntentData.put(content))
            putString("mode", mode.name)
            putLong("time", time)
            putBoolean("showToc", tocEnabled)
        }
        isCancelable = false
        this.autoClose = autoClose
    }

    private var textViewRef: ScrollTextView? = null
    private var time = 0L
    private var badgeCount by mutableIntStateOf(0)
    private var autoClose: Boolean = false
    private var markwon: Markwon? = null
    private var renderJob: Job? = null
    private var fullContent = ""
    private var sections: List<HelpSection> = emptyList()
    private val tocLabels = mutableStateListOf<String>()
    private var tocMenuVisible by mutableStateOf(false)
    private var searchMenuVisible by mutableStateOf(false)
    private var tocDrawerOpen by mutableStateOf(false)
    private var selectedSection by mutableIntStateOf(0)
    private var restoredScrollY = 0
    private var searchBarVisible by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    private var searchCountText by mutableStateOf("")
    private var searchNavEnabled by mutableStateOf(false)
    private var searchRanges: List<IntRange> = emptyList()
    private var searchIndex = -1
    private val searchSpans = mutableListOf<BackgroundColorSpan>()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val dialogTitle = arguments?.getString("title").orEmpty()
        val mode = arguments?.getString("mode")

        val scrollTextView = ScrollTextView(context, null).apply {
            val pad = 12.dpToPx()
            setPadding(pad, pad, pad, pad)
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            setTextColor(context.secondaryTextColor)
            setTextIsSelectable(true)
        }
        textViewRef = scrollTextView

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val localContext = LocalContext.current
                    val barBg = Color(localContext.primaryColor)
                    val barContent = Color(localContext.primaryTextColor)
                    val accent = Color(localContext.accentColor)
                    val searchFocusRequester = remember { FocusRequester() }
                    val keyboardController = LocalSoftwareKeyboardController.current

                    LaunchedEffect(searchBarVisible) {
                        if (searchBarVisible) {
                            runCatching { searchFocusRequester.requestFocus() }
                            keyboardController?.show()
                        } else {
                            keyboardController?.hide()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorResource(R.color.background))
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            TopAppBar(
                                title = { Text(dialogTitle) },
                                actions = {
                                    if (searchMenuVisible) {
                                        IconButton(onClick = { toggleSearch() }) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_search),
                                                contentDescription = stringResource(R.string.search),
                                                tint = barContent
                                            )
                                        }
                                    }
                                    if (tocMenuVisible) {
                                        IconButton(onClick = { tocDrawerOpen = true }) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_chapter_list),
                                                contentDescription = stringResource(R.string.chapter_list),
                                                tint = barContent
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val cacheKey = "code_text_${System.currentTimeMillis()}"
                                            CacheManager.putMemory(cacheKey, fullContent)
                                            startActivity<CodeEditActivity> {
                                                putExtra("cacheKey", cacheKey)
                                                putExtra("title", dialogTitle)
                                                putExtra(
                                                    "languageName",
                                                    if (mode == Mode.MD.name) "text.html.markdown" else "text.html.basic"
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_code),
                                            contentDescription = stringResource(R.string.edit_content),
                                            tint = barContent
                                        )
                                    }
                                    TextButton(onClick = { dismissAllowingStateLoss() }) {
                                        Text(
                                            text = stringResource(R.string.close),
                                            color = barContent
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = barBg,
                                    titleContentColor = barContent,
                                    actionIconContentColor = barContent
                                )
                            )

                            if (searchBarVisible) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { onSearchQueryChanged(it) },
                                        placeholder = { Text(stringResource(R.string.search)) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = { moveToMatch(searchIndex + 1) }
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(searchFocusRequester)
                                    )
                                    Text(
                                        text = searchCountText,
                                        color = colorResource(R.color.secondaryText),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .widthIn(min = 40.dp)
                                            .padding(horizontal = 4.dp)
                                    )
                                    IconButton(
                                        onClick = { moveToMatch(searchIndex - 1) },
                                        enabled = searchNavEnabled,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_down),
                                            contentDescription = stringResource(R.string.help_search_prev),
                                            tint = colorResource(R.color.primaryText),
                                            modifier = Modifier.rotate(180f)
                                        )
                                    }
                                    IconButton(
                                        onClick = { moveToMatch(searchIndex + 1) },
                                        enabled = searchNavEnabled,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_arrow_down),
                                            contentDescription = stringResource(R.string.help_search_next),
                                            tint = colorResource(R.color.primaryText)
                                        )
                                    }
                                }
                            }

                            AndroidView(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                factory = { scrollTextView }
                            )
                        }

                        if (badgeCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = accent,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                            ) {
                                Text(
                                    text = badgeCount.toString(),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (tocDrawerOpen) {
                            val scrimInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .clickable(
                                        interactionSource = scrimInteraction,
                                        indication = null,
                                        onClick = { tocDrawerOpen = false }
                                    )
                            )
                        }

                        AnimatedVisibility(
                            visible = tocDrawerOpen,
                            enter = slideInHorizontally(initialOffsetX = { it }),
                            exit = slideOutHorizontally(targetOffsetX = { it }),
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Surface(
                                color = colorResource(R.color.background),
                                shadowElevation = 8.dp,
                                modifier = Modifier
                                    .width(264.dp)
                                    .fillMaxHeight()
                            ) {
                                LazyColumn(
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    itemsIndexed(tocLabels) { position, label ->
                                        val isSelected = selectedSection == position
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isSelected) accent.copy(alpha = 0.15f)
                                                    else Color.Transparent
                                                )
                                                .clickable {
                                                    if (selectedSection != position) {
                                                        if (position != 0 && searchQuery.isNotBlank()) {
                                                            onSearchQueryChanged("")
                                                        }
                                                        selectedSection = position
                                                        restoredScrollY = 0
                                                        textViewRef?.scrollTo(0, 0)
                                                        renderMarkdown(currentMarkdown())
                                                    }
                                                    tocDrawerOpen = false
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isSelected) accent else colorResource(R.color.primaryText),
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        selectedSection = savedInstanceState?.getInt(STATE_SELECTED_SECTION) ?: 0
        restoredScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y) ?: 0
        val textView = checkNotNull(textViewRef)
        arguments?.let {
            val showToc = it.getBoolean("showToc")
            val content = if (showToc) {
                it.getString("content").orEmpty()
            } else {
                IntentData.get<String>(it.getString("content")).orEmpty()
            }
            fullContent = content
            val mode = it.getString("mode")
            when (mode) {
                Mode.MD.name -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        textView.setTextClassifier(TextClassifier.NO_OP)
                    }
                    if (showToc) {
                        textView.setLineSpacing(0f, 1.3f)
                        setupToc()
                        setupSearch(savedInstanceState)
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        markwon = withContext(IO) {
                            val context = requireContext()
                            val builder = Markwon.builder(context)
                                .usePlugin(GlideImagesPlugin.create(Glide.with(context)))
                                .usePlugin(HtmlPlugin.create())
                            if (showToc) {
                                builder
                                    .usePlugin(TablePlugin.create(HelpMarkwonTheme.tableTheme(context)))
                                    .usePlugin(HelpMarkwonTheme.plugin(context))
                            } else {
                                builder.usePlugin(TablePlugin.create(context))
                            }
                            builder.build()
                        }
                        renderMarkdown(currentMarkdown(), restoredScrollY)
                    }
                }

                Mode.HTML.name -> textView.setHtml(content)
                else -> {
                    if (content.length >= 32 * 1024) {
                        val truncatedContent =
                            content.take(32 * 1024) + "\n\n数据太大，无法全部显示…"
                        textView.text = truncatedContent
                    } else {
                        textView.text = content
                    }
                }
            }
            time = it.getLong("time", 0L)
        }
        if (time > 0) {
            val owner = viewLifecycleOwner
            badgeCount = (time / 1000).toInt()
            owner.lifecycleScope.launch {
                while (time > 0) {
                    delay(1000)
                    time -= 1000
                    badgeCount = (time / 1000).toInt()
                    if (time <= 0) {
                        dialog?.setCancelable(true)
                        if (autoClose) dialog?.cancel()
                    }
                }
            }
        } else {
            val owner = viewLifecycleOwner
            view.post {
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    dialog?.setCancelable(true)
                }
            }
        }
    }

    private fun setupToc() {
        sections = parseHelpSections(fullContent)
        if (sections.isEmpty()) {
            selectedSection = 0
            return
        }
        selectedSection = selectedSection.coerceIn(0, sections.size)
        val labels = listOf(getString(R.string.all)) + sections.map {
            if (it.depth == 0) it.title else "    ${it.title}"
        }
        tocLabels.clear()
        tocLabels.addAll(labels)
        tocMenuVisible = true
    }

    private fun currentMarkdown(): String {
        return if (selectedSection == 0) {
            fullContent
        } else {
            sections[selectedSection - 1].markdown
        }
    }

    private fun setupSearch(savedInstanceState: Bundle?) {
        searchQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
        searchIndex = savedInstanceState?.getInt(STATE_SEARCH_INDEX, -1) ?: -1
        if (searchQuery.isNotBlank() && selectedSection != 0) {
            selectedSection = 0
            restoredScrollY = 0
        }
        searchBarVisible = savedInstanceState?.getBoolean(STATE_SEARCH_VISIBLE) == true
        searchMenuVisible = true
    }

    private fun toggleSearch() {
        searchBarVisible = !searchBarVisible
    }

    private fun onSearchQueryChanged(query: String) {
        searchQuery = query
        searchIndex = -1
        searchCountText = ""
        setSearchNavigationEnabled(false)
        if (query.isBlank()) {
            searchRanges = emptyList()
            clearSearchSpans()
            return
        }
        if (selectedSection != 0) {
            selectedSection = 0
            restoredScrollY = 0
            renderMarkdown(currentMarkdown())
        } else if (renderJob?.isActive != true) {
            applySearchHighlight()
        }
    }

    private fun applySearchHighlight(keepIndex: Boolean = false) {
        clearSearchSpans()
        val textView = textViewRef ?: return
        val text = textView.text
        searchRanges = findTextRanges(text.toString(), searchQuery)
        if (searchRanges.isEmpty()) {
            searchIndex = -1
            setSearchNavigationEnabled(false)
            searchCountText = "0/0"
            return
        }
        if (!keepIndex || searchIndex !in searchRanges.indices) searchIndex = 0
        val spannable = text as? Spannable ?: run {
            textView.text = SpannableStringBuilder(text)
            textView.text as? Spannable ?: return
        }
        val normalColor = ColorUtils.adjustAlpha(primaryColor, 0.25f)
        val currentColor = ColorUtils.adjustAlpha(primaryColor, 0.5f)
        searchRanges.forEachIndexed { index, range ->
            val span = BackgroundColorSpan(if (index == searchIndex) currentColor else normalColor)
            spannable.setSpan(span, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            searchSpans.add(span)
        }
        setSearchNavigationEnabled(true)
        searchCountText = "${searchIndex + 1}/${searchRanges.size}"
        scrollToCurrentMatch()
    }

    private fun setSearchNavigationEnabled(enabled: Boolean) {
        searchNavEnabled = enabled
    }

    private fun moveToMatch(index: Int) {
        if (searchRanges.isEmpty()) return
        searchIndex = ((index % searchRanges.size) + searchRanges.size) % searchRanges.size
        applySearchHighlight(keepIndex = true)
    }

    private fun clearSearchSpans() {
        val text = textViewRef?.text as? Spannable
        searchSpans.forEach { text?.removeSpan(it) }
        searchSpans.clear()
    }

    private fun scrollToCurrentMatch() {
        val offset = searchRanges.getOrNull(searchIndex)?.first ?: return
        val textView = textViewRef ?: return
        textView.post {
            if (!textView.isAttachedToWindow ||
                searchRanges.getOrNull(searchIndex)?.first != offset
            ) return@post
            val layout = textView.layout ?: return@post
            val y = (layout.getLineTop(layout.getLineForOffset(offset)) - textView.totalPaddingTop)
                .coerceAtLeast(0)
            textView.scrollTo(0, y)
        }
    }

    private fun renderMarkdown(markdown: String, scrollY: Int = 0) {
        val currentMarkwon = markwon ?: return
        if (searchQuery.isNotBlank()) setSearchNavigationEnabled(false)
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val parsed = withContext(IO) { currentMarkwon.toMarkdown(markdown) }
            val textView = textViewRef ?: return@launch
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.setMarkdown(
                currentMarkwon,
                parsed,
                imgOnLongClickListener = { source ->
                    showDialogFragment(PhotoDialog(source))
                },
            )
            if (searchQuery.isNotBlank()) {
                applySearchHighlight(keepIndex = true)
            } else {
                textView.post {
                    if (textView.isAttachedToWindow) textView.scrollTo(0, scrollY)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (arguments?.getBoolean("showToc") == true) {
            outState.putInt(STATE_SELECTED_SECTION, selectedSection)
            outState.putInt(STATE_SCROLL_Y, textViewRef?.scrollY ?: 0)
            outState.putBoolean(STATE_SEARCH_VISIBLE, searchBarVisible)
            outState.putString(STATE_SEARCH_QUERY, searchQuery)
            outState.putInt(STATE_SEARCH_INDEX, searchIndex)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        renderJob?.cancel()
        renderJob = null
        searchSpans.clear()
        markwon = null
        textViewRef = null
        super.onDestroyView()
    }

    companion object {
        private const val STATE_SELECTED_SECTION = "selectedSection"
        private const val STATE_SCROLL_Y = "textScrollY"
        private const val STATE_SEARCH_VISIBLE = "searchVisible"
        private const val STATE_SEARCH_QUERY = "searchQuery"
        private const val STATE_SEARCH_INDEX = "searchIndex"
    }

}
