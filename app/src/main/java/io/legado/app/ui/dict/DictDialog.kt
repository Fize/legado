package io.legado.app.ui.dict

import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.DictRule
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setHtml
import io.legado.app.utils.setLayout
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 词典 (Compose)
 */
class DictDialog() : BaseDialogFragment() {

    constructor(word: String) : this() {
        arguments = Bundle().apply {
            putString("word", word)
        }
    }

    private val viewModel by viewModels<DictViewModel>()
    private var word: String? = null
    private val dictRules = mutableStateListOf<DictRule>()
    private var selectedTabIndex by mutableIntStateOf(0)
    private var isLoading by mutableStateOf(false)
    private var dictContent by mutableStateOf<Pair<DictRule, String>?>(null)

    private var tvDictView: ScrollTextView? = null
    private var glideImageGetter: GlideImageGetter? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    val bgColor = remember(context) { Color(context.backgroundColor) }
                    val accent = remember(context) { Color(context.accentColor) }

                    LaunchedEffect(selectedTabIndex, dictRules.size) {
                        val rule = dictRules.getOrNull(selectedTabIndex) ?: return@LaunchedEffect
                        val queryWord = word ?: return@LaunchedEffect
                        isLoading = true
                        viewModel.dict(rule, queryWord) { result ->
                            isLoading = false
                            dictContent = rule to result
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                    ) {
                        if (dictRules.isNotEmpty()) {
                            if (dictRules.size <= 4) {
                                PrimaryTabRow(
                                    selectedTabIndex = selectedTabIndex,
                                    containerColor = bgColor,
                                    contentColor = accent,
                                    indicator = {
                                        TabRowDefaults.PrimaryIndicator(
                                            modifier = Modifier.tabIndicatorOffset(selectedTabIndex),
                                            color = accent
                                        )
                                    }
                                ) {
                                    dictRules.forEachIndexed { index, rule ->
                                        Tab(
                                            selected = selectedTabIndex == index,
                                            onClick = { selectedTabIndex = index },
                                            text = {
                                                Text(
                                                    text = rule.name,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = if (selectedTabIndex == index) accent
                                                    else colorResource(R.color.primaryText)
                                                )
                                            }
                                        )
                                    }
                                }
                            } else {
                                PrimaryScrollableTabRow(
                                    selectedTabIndex = selectedTabIndex,
                                    containerColor = bgColor,
                                    contentColor = accent,
                                    edgePadding = 8.dp,
                                    indicator = {
                                        TabRowDefaults.PrimaryIndicator(
                                            modifier = Modifier.tabIndicatorOffset(selectedTabIndex),
                                            color = accent
                                        )
                                    }
                                ) {
                                    dictRules.forEachIndexed { index, rule ->
                                        Tab(
                                            selected = selectedTabIndex == index,
                                            onClick = { selectedTabIndex = index },
                                            text = {
                                                Text(
                                                    text = rule.name,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = if (selectedTabIndex == index) accent
                                                    else colorResource(R.color.primaryText)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .padding(top = 8.dp)
                        ) {
                            val currentPair = dictContent
                            AndroidView(
                                factory = { ctx ->
                                    ScrollTextView(ctx, null).apply {
                                        val pad = 16.dpToPx()
                                        setPadding(pad, pad, pad, pad)
                                        setTextColor(ctx.getCompatColor(R.color.secondaryText))
                                        movementMethod = LinkMovementMethod()
                                        tvDictView = this
                                    }
                                },
                                update = { textView ->
                                    if (currentPair != null) {
                                        renderDictContent(textView, currentPair.first, currentPair.second)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = accent,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .align(Alignment.Center)
                                        .padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        word = arguments?.getString("word")
        if (word.isNullOrEmpty()) {
            toastOnUi(R.string.cannot_empty)
            dismiss()
            return
        }
        viewModel.initData { rules ->
            dictRules.clear()
            dictRules.addAll(rules)
            selectedTabIndex = 0
        }
    }

    private fun renderDictContent(textView: ScrollTextView, dictRule: DictRule, content: String) {
        val imgAvailableWidth = (textView.width - textView.paddingLeft - textView.paddingRight)
            .takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - 32.dpToPx())
        val contentTrimS = content.trimStart()
        if (contentTrimS.startsWith("<md>")) {
            val lastIndex = contentTrimS.lastIndexOf("<")
            if (lastIndex < 4) {
                textView.text = contentTrimS
                return
            }
            val mark = contentTrimS.substring(4, lastIndex)
            viewLifecycleOwner.lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    textView.setTextClassifier(TextClassifier.NO_OP)
                }
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(requireContext())
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(requireContext())
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth)
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(requireContext()))
                        .build()
                    markwon.toMarkdown(mark)
                }
                textView.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source))
                    }
                )
            }
            return
        }
        val getter = glideImageGetter ?: GlideImageGetter(
            requireContext(),
            textView,
            this@DictDialog.lifecycle,
            imgAvailableWidth
        ).also { glideImageGetter = it }

        val textViewTagHandler = TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                viewModel.onButtonClick(dictRule, "button $name", click)
            }
        })
        textView.setHtml(
            content,
            getter,
            textViewTagHandler,
            imgOnLongClickListener = { source ->
                showDialogFragment(PhotoDialog(source))
            },
            imgOnClickListener = { click ->
                viewModel.onButtonClick(dictRule, "image", click)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        glideImageGetter?.clear()
        glideImageGetter = null
        tvDictView = null
    }
}
