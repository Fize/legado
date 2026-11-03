package io.legado.app.ui.widget.dialog

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.text.method.KeyListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.IntentData
import io.legado.app.help.findTextRanges
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.view.ThemeSeekBar
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.code.CodeTextTransfer
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.ui.widget.seekbar.VerticalSeekBarWrapper
import io.legado.app.utils.disableEdit
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

internal fun resolveCodeDialogOriginal(
    showingAlternate: Boolean,
    originalCode: String,
    displayedCode: String,
): String = if (showingAlternate) originalCode else displayedCode

internal fun resolveCodeDialogPositionProgress(
    scrollY: Int,
    maxScrollY: Int,
    progressMax: Int = 10000,
): Int {
    if (maxScrollY <= 0 || progressMax <= 0) return 0
    return (scrollY.coerceIn(0, maxScrollY).toLong() * progressMax / maxScrollY).toInt()
}

class CodeDialog() : BaseDialogFragment() {

    constructor(
        code: String,
        disableEdit: Boolean = true,
        requestId: String? = null,
        alternateCode: String? = null,
        showAlternate: Boolean = false,
        showReplaceRules: Boolean = false,
    ) : this() {
        arguments = Bundle().apply {
            putBoolean("disableEdit", disableEdit)
            putString("code", IntentData.put(code))
            putString("requestId", requestId)
            alternateCode?.let { putString("alternateCode", IntentData.put(it)) }
            putBoolean("showAlternate", showAlternate)
            putBoolean("showReplaceRules", showReplaceRules)
        }
    }

    private var codeViewRef: CodeView? = null
    private var positionBarRef: ThemeSeekBar? = null
    private var editKeyListener: KeyListener? = null
    private var originalCode = ""
    private var alternateCode by mutableStateOf<String?>(null)
    private var showingAlternate by mutableStateOf(false)
    private var restoreAlternateAfterRefresh = false
    private var saveEnabled by mutableStateOf(false)
    @set:JvmName("updateSearchOpenState")
    private var searchOpen by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    @set:JvmName("updateSearchActionsEnabledState")
    private var searchActionsEnabled by mutableStateOf(false)
    private var searchRanges: List<IntRange> = emptyList()
    private var searchIndex by mutableIntStateOf(-1)
    @set:JvmName("updateReplaceRuleRefreshPendingState")
    private var replaceRuleRefreshPending by mutableStateOf(false)
    private var originalCodeStateKey: String? = null
    private var alternateCodeStateKey: String? = null
    private var editorTextPath: String? = null
    private var editorPending by mutableStateOf(false)
    private var editorReadOnly = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { updatePositionBar() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { updatePositionBar() }
    val requestId: String?
        get() = arguments?.getString("requestId")
    private val sourcePreview: Boolean
        get() = arguments?.getBoolean("showReplaceRules") == true

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val context = requireContext().applicationContext
        val outputPath = result.data?.getStringExtra("textFile")
        try {
            if (result.resultCode != RESULT_OK || editorReadOnly) return@registerForActivityResult
            val code = result.data?.getStringExtra("text") ?: outputPath?.let {
                CodeTextTransfer.read(context, it)
                    ?: error(getString(R.string.code_editor_result_error))
            }
            if (code != null) {
                val wasShowingAlternate = sourcePreview && showingAlternate
                originalCode = code
                if (!showingAlternate) codeViewRef?.setText(code)
                if (sourcePreview) {
                    restoreAlternateAfterRefresh = restoreAlternateAfterRefresh || wasShowingAlternate
                    clearAlternateCode()
                    callback()?.onCodeSave(code, requestId)
                }
            }
            if (!showingAlternate) {
                result.data?.takeIf { it.hasExtra("cursorPosition") }
                    ?.getIntExtra("cursorPosition", 0)?.let {
                        val cv = codeViewRef
                        if (cv != null) {
                            cv.setSelection(it.coerceIn(0, cv.length()))
                        }
                    }
            }
        } catch (error: Exception) {
            toastOnUi(error.localizedMessage ?: "")
        } finally {
            CodeTextTransfer.delete(context, editorTextPath)
            CodeTextTransfer.delete(context, outputPath)
            editorTextPath = null
            editorPending = false
            updateEditorAction()
        }
    }

    override fun onStart() {
        super.onStart()
        originalCodeStateKey?.let { IntentData.get<Any>(it) }
        alternateCodeStateKey?.let { IntentData.get<Any>(it) }
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val disableEdit = arguments?.getBoolean("disableEdit") == true
        val showReplaceRulesArg = arguments?.getBoolean("showReplaceRules") == true

        val codeView = CodeView(context).apply {
            contentDescription = "codeDialogEditor"
            val pad = 12.dpToPx()
            setPadding(pad, pad, pad, pad)
            if (disableEdit) {
                disableEdit()
            }
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
            viewTreeObserver.addOnScrollChangedListener(scrollListener)
            viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            doAfterTextChanged {
                if (searchOpen) {
                    updateSearch(keepIndex = true, selectMatch = false)
                }
            }
        }
        codeViewRef = codeView

        val seekBar = ThemeSeekBar(context, null).apply {
            contentDescription = getString(R.string.content_edit_position)
            max = 10000
            rotation = 90f
            isSaveEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val max = maxScrollY()
                        codeView.scrollTo(
                            0,
                            (max * progress.toLong() / bar.max).toInt(),
                        )
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        positionBarRef = seekBar

        val seekBarWrapper = VerticalSeekBarWrapper(context).apply {
            addView(
                seekBar,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val localContext = LocalContext.current
                    val barBg = Color(localContext.primaryColor)
                    val barContent = Color(localContext.primaryTextColor)
                    val pending = replaceRuleRefreshPending || editorPending
                    val canPreviewReplacement = !disableEdit && alternateCode != null
                    var overflowExpanded by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorResource(R.color.background))
                    ) {
                        TopAppBar(
                            title = {
                                if (searchOpen) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = {
                                            searchQuery = it
                                            updateSearch()
                                        },
                                        placeholder = {
                                            Text(
                                                text = stringResource(R.string.search),
                                                color = barContent.copy(alpha = 0.7f)
                                            )
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = { moveToMatch(searchIndex + 1) }
                                        ),
                                        trailingIcon = {
                                            IconButton(
                                                onClick = {
                                                    searchQuery = ""
                                                    setSearchOpen(false)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = stringResource(R.string.close),
                                                    tint = barContent
                                                )
                                            }
                                        },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = barContent,
                                            unfocusedTextColor = barContent,
                                            cursorColor = barContent,
                                            focusedBorderColor = barContent.copy(alpha = 0.6f),
                                            unfocusedBorderColor = barContent.copy(alpha = 0.3f)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    Text(if (disableEdit) "code view" else "code edit")
                                }
                            },
                            actions = {
                                if (!searchOpen) {
                                    IconButton(
                                        onClick = {
                                            setSearchOpen(true)
                                            updateSearch(keepIndex = true)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = stringResource(R.string.search),
                                            tint = barContent
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            if (!editorPending) moveToMatch(searchIndex - 1)
                                        },
                                        enabled = searchActionsEnabled && !editorPending
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_skip_previous),
                                            contentDescription = stringResource(R.string.help_search_prev),
                                            tint = barContent
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (!editorPending) moveToMatch(searchIndex + 1)
                                        },
                                        enabled = searchActionsEnabled && !editorPending
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_skip_next),
                                            contentDescription = stringResource(R.string.help_search_next),
                                            tint = barContent
                                        )
                                    }
                                }

                                if (saveEnabled && !searchOpen) {
                                    IconButton(
                                        onClick = {
                                            if (!editorPending) openEditor()
                                        },
                                        enabled = !pending,
                                        modifier = Modifier.testTag("codeDialogOpenEditor"),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_edit),
                                            contentDescription = stringResource(R.string.view_in_code_editor),
                                            tint = barContent
                                        )
                                    }
                                }

                                if (saveEnabled && (!showingAlternate || sourcePreview) && !searchOpen) {
                                    IconButton(
                                        onClick = {
                                            if (!editorPending && !replaceRuleRefreshPending) {
                                                callback()?.onCodeSave(currentOriginalCode(), requestId)
                                                dismiss()
                                            }
                                        },
                                        enabled = !pending,
                                        modifier = Modifier.testTag("codeDialogSave"),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_save),
                                            contentDescription = stringResource(R.string.action_save),
                                            tint = barContent
                                        )
                                    }
                                }

                                if (showReplaceRulesArg || sourcePreview) {
                                    Box {
                                        IconButton(
                                            onClick = { overflowExpanded = true },
                                            modifier = Modifier.testTag("codeDialogOverflow"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = stringResource(R.string.more_menu),
                                                tint = barContent
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = overflowExpanded,
                                            onDismissRequest = { overflowExpanded = false }
                                        ) {
                                            if (showReplaceRulesArg) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.menu_replace_rule)) },
                                                    modifier = Modifier.testTag("codeDialogManageReplaceRules"),
                                                    enabled = !pending,
                                                    onClick = {
                                                        overflowExpanded = false
                                                        if (!editorPending && !replaceRuleRefreshPending) {
                                                            callback()?.onOpenReplaceRules()
                                                        }
                                                    }
                                                )
                                            }
                                            if (sourcePreview) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.effective_replaces)) },
                                                    enabled = !pending,
                                                    onClick = {
                                                        overflowExpanded = false
                                                        if (!editorPending && !replaceRuleRefreshPending) {
                                                            callback()?.onShowSourceReplacements(
                                                                currentOriginalCode(),
                                                                requestId,
                                                                false
                                                            )
                                                        }
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.manual_replace_rule)) },
                                                    enabled = !pending && callback()?.isManualSourceReplacementEnabled() == true,
                                                    onClick = {
                                                        overflowExpanded = false
                                                        if (!editorPending && !replaceRuleRefreshPending &&
                                                            callback()?.isManualSourceReplacementEnabled() == true
                                                        ) {
                                                            callback()?.onShowSourceReplacements(
                                                                currentOriginalCode(),
                                                                requestId,
                                                                true
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = barBg,
                                titleContentColor = barContent,
                                actionIconContentColor = barContent
                            )
                        )

                        if (canPreviewReplacement) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clickable(enabled = !pending) {
                                            showAlternate(!showingAlternate)
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = showingAlternate,
                                        onCheckedChange = { checked -> showAlternate(checked) },
                                        enabled = !pending,
                                        modifier = Modifier.testTag("codeDialogAlternate"),
                                    )
                                    Text(
                                        text = stringResource(R.string.show_source_replacement),
                                        color = colorResource(R.color.primaryText)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            AndroidView(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                factory = { codeView }
                            )
                            AndroidView(
                                modifier = Modifier
                                    .width(40.dp)
                                    .fillMaxHeight(),
                                factory = { seekBarWrapper }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        editorTextPath = savedInstanceState?.getString("editorTextPath")
        editorPending = savedInstanceState?.getBoolean("editorPending") == true
        editorReadOnly = savedInstanceState?.getBoolean("editorReadOnly") == true
        val disableEdit = arguments?.getBoolean("disableEdit") == true
        initMenu(!disableEdit)
        originalCodeStateKey = savedInstanceState?.getString("originalCode")
            ?: arguments?.getString("code")
        originalCode = originalCodeStateKey
            ?.let { IntentData.get<String>(it) }
            .orEmpty()
        alternateCodeStateKey = savedInstanceState?.getString("alternateCode")
            ?: arguments?.getString("alternateCode")
        alternateCode = alternateCodeStateKey
            ?.let { IntentData.get<String>(it) }
        if (arguments?.getBoolean("showReplaceRules") == true) {
            callback()?.let { alternateCode = it.getCodeAlternate(requestId) }
        }
        val codeView = checkNotNull(codeViewRef)
        editKeyListener = codeView.keyListener
        codeView.setText(originalCode)
        codeView.post { updatePositionBar() }
        val canPreviewReplacement = !disableEdit && alternateCode != null
        val initialShowingAlternate = savedInstanceState?.getBoolean("showingAlternate")
            ?: (arguments?.getBoolean("showAlternate") == true)
        restoreAlternateAfterRefresh = savedInstanceState?.getBoolean("restoreAlternateAfterRefresh") == true
        showingAlternate = initialShowingAlternate
        if (canPreviewReplacement) {
            showAlternate(showingAlternate)
        }
        setReplaceRuleRefreshPending(callback()?.isReplaceRuleRefreshPending() == true)
    }

    private fun showAlternate(show: Boolean) {
        val codeView = codeViewRef ?: return
        if (show) {
            val alternate = alternateCode ?: return
            if (!showingAlternate) {
                originalCode = codeView.text?.toString().orEmpty()
            }
            codeView.setText(alternate)
            codeView.keyListener = null
        } else {
            codeView.setText(originalCode)
            codeView.keyListener = editKeyListener
        }
        showingAlternate = show
        updateEditorAction()
        if (searchOpen) showCurrentMatch()
        codeView.post { updatePositionBar() }
    }

    private fun initMenu(canSave: Boolean) {
        saveEnabled = canSave
        setSearchOpen(false)
    }

    private fun openEditor() {
        if (!saveEnabled || replaceRuleRefreshPending || editorPending) return
        val codeView = codeViewRef ?: return
        val context = requireContext().applicationContext
        editorReadOnly = showingAlternate && !sourcePreview
        val code = if (editorReadOnly) codeView.text.toString() else currentOriginalCode()
        val cursor = if (showingAlternate && sourcePreview) 0 else codeView.selectionStart.coerceAtLeast(0)
        editorPending = true
        updateEditorAction()
        try {
            val path = if (EditSafety.isTooLongForInline(code)) CodeTextTransfer.write(context, code) else null
            editorTextPath = path
            editorLauncher.launch(Intent(context, CodeEditActivity::class.java).apply {
                putExtra("useTextFile", true)
                if (path != null) putExtra("textFile", path) else putExtra("text", code)
                putExtra("readOnly", editorReadOnly)
                putExtra("cursorPosition", cursor)
            })
        } catch (error: Exception) {
            CodeTextTransfer.delete(context, editorTextPath)
            editorTextPath = null
            editorPending = false
            updateEditorAction()
            toastOnUi(error.localizedMessage ?: "")
        }
    }

    private fun updateEditorAction() {
        val pending = replaceRuleRefreshPending || editorPending
        codeViewRef?.keyListener = if (pending || showingAlternate) null else editKeyListener
        isCancelable = !pending
    }

    private fun setSearchOpen(open: Boolean) {
        searchOpen = open
        if (!open) setSearchActionsEnabled(false)
    }

    private fun updateSearch(
        keepIndex: Boolean = false,
        selectMatch: Boolean = true,
    ) {
        searchRanges = findTextRanges(
            codeViewRef?.text?.toString().orEmpty(),
            searchQuery,
        )
        if (searchRanges.isEmpty()) {
            searchIndex = -1
            setSearchActionsEnabled(false)
            return
        }
        searchIndex = if (keepIndex) {
            searchIndex.coerceIn(0, searchRanges.lastIndex)
        } else {
            0
        }
        setSearchActionsEnabled(true)
        if (selectMatch) showCurrentMatch()
    }

    private fun moveToMatch(index: Int) {
        if (searchRanges.isEmpty()) return
        searchIndex = ((index % searchRanges.size) + searchRanges.size) % searchRanges.size
        showCurrentMatch()
    }

    private fun showCurrentMatch() {
        val range = searchRanges.getOrNull(searchIndex) ?: return
        val codeView = codeViewRef ?: return
        codeView.setSelection(range.first, range.last + 1)
        codeView.post {
            if (!codeView.isAttachedToWindow ||
                searchRanges.getOrNull(searchIndex) != range
            ) return@post
            codeView.bringPointIntoView(range.first)
        }
    }

    private fun setSearchActionsEnabled(enabled: Boolean) {
        searchActionsEnabled = enabled
    }

    private fun maxScrollY(): Int {
        val codeView = codeViewRef ?: return 0
        return ((codeView.layout?.height ?: 0) + codeView.totalPaddingTop +
            codeView.totalPaddingBottom - codeView.height).coerceAtLeast(0)
    }

    private fun updatePositionBar() {
        val seekBar = positionBarRef ?: return
        val codeView = codeViewRef ?: return
        val max = maxScrollY()
        seekBar.isEnabled = max > 0
        seekBar.progress = resolveCodeDialogPositionProgress(
            codeView.scrollY,
            max,
            seekBar.max,
        )
    }

    fun refreshAlternateCode() {
        alternateCode = callback()?.getCodeAlternate(requestId)
        updateAlternatePreview()
    }

    fun clearAlternateCode() {
        alternateCode = null
        updateAlternatePreview()
    }

    private fun updateAlternatePreview() {
        if (view == null) return
        if (alternateCode == null) {
            if (showingAlternate) showAlternate(false)
        } else {
            if (showingAlternate || restoreAlternateAfterRefresh) {
                restoreAlternateAfterRefresh = false
                showAlternate(true)
            }
        }
        updateEditorAction()
    }

    fun setReplaceRuleRefreshPending(pending: Boolean) {
        replaceRuleRefreshPending = pending
        if (view == null) return
        updateEditorAction()
    }

    fun currentOriginalCode(): String = if (view == null) {
        originalCode
    } else {
        resolveCodeDialogOriginal(
            showingAlternate,
            originalCode,
            codeViewRef?.text?.toString().orEmpty(),
        )
    }

    internal fun codeViewForTest(): CodeView? = codeViewRef

    internal fun isShowingAlternateForTest(): Boolean = showingAlternate

    private fun callback(): Callback? =
        (parentFragment as? Callback) ?: (activity as? Callback)

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        searchOpen = savedInstanceState?.getBoolean("searchOpen") == true
        searchQuery = savedInstanceState?.getString("searchQuery").orEmpty()
        setSearchOpen(searchOpen)
        searchIndex = savedInstanceState?.getInt("searchIndex", -1) ?: -1
        if (searchOpen) updateSearch(keepIndex = true)
    }

    override fun onDestroyView() {
        codeViewRef?.viewTreeObserver?.removeOnScrollChangedListener(scrollListener)
        codeViewRef?.viewTreeObserver?.removeOnGlobalLayoutListener(layoutListener)
        codeViewRef = null
        positionBarRef = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("editorTextPath", editorTextPath)
        outState.putBoolean("editorPending", editorPending)
        outState.putBoolean("editorReadOnly", editorReadOnly)
        outState.putBoolean("searchOpen", searchOpen)
        outState.putString("searchQuery", searchQuery)
        outState.putInt("searchIndex", searchIndex)
        outState.putBoolean("restoreAlternateAfterRefresh", restoreAlternateAfterRefresh)
        originalCodeStateKey = saveStateData(originalCodeStateKey, currentOriginalCode())
            .also { outState.putString("originalCode", it) }
        alternateCode?.let { code ->
            alternateCodeStateKey = saveStateData(alternateCodeStateKey, code)
                .also { outState.putString("alternateCode", it) }
        }
        outState.putBoolean("showingAlternate", showingAlternate)
        super.onSaveInstanceState(outState)
    }

    private fun saveStateData(key: String?, data: String): String =
        key?.also { IntentData.put(it, data) } ?: IntentData.put(data)

    interface Callback {

        fun onCodeSave(code: String, requestId: String?)

        fun onOpenReplaceRules() = Unit

        fun onShowSourceReplacements(code: String, requestId: String?, manual: Boolean) = Unit

        fun isManualSourceReplacementEnabled(): Boolean = false

        fun getCodeAlternate(requestId: String?): String? = null

        fun isReplaceRuleRefreshPending(): Boolean = false

    }

}
