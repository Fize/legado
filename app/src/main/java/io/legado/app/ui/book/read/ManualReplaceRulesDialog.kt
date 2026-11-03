package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper.AdvanceCallback.Mode
import io.legado.app.utils.setLayout

/** Selects replacement rules for the current book or a source import preview. */
class ManualReplaceRulesDialog() : BaseDialogFragment() {
    constructor(ids: List<Long>, requestId: String?) : this() {
        arguments = Bundle().apply {
            putBoolean("sourceReplacement", true)
            putLongArray("selectedIds", ids.toLongArray())
            putString("requestId", requestId)
        }
    }

    interface Callback {
        fun onManualSourceRulesSelected(ids: List<Long>, requestId: String?)
    }

    private val sourceReplacement get() = arguments?.getBoolean("sourceReplacement") == true

    private val viewModel by activityViewModels<ReadBookViewModel>()
    private var candidates: List<ReplaceRule> = emptyList()
    private val selectedIds = linkedSetOf<Long>()
    private var selectionVersion by mutableIntStateOf(0)
    @Suppress("unused")
    private val dragSelectMode = Mode.ToggleAndReverse

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
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
                    @Suppress("UNUSED_EXPRESSION")
                    selectionVersion
                    ManualReplaceRulesScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val book = ReadBook.book
        if (!sourceReplacement && book == null) {
            dismissAllowingStateLoss()
            return
        }
        candidates = if (sourceReplacement) appDb.replaceRuleDao.findEnabledBySourceScope()
            else appDb.replaceRuleDao.findManualCandidates()
        val selected = savedInstanceState?.getLongArray("selectedIds")?.toList()
            ?: if (sourceReplacement) arguments?.getLongArray("selectedIds")?.toList().orEmpty()
            else book!!.config.manualReplaceRuleIds
        selectedIds.clear()
        selectedIds.addAll(selected)
        selectedIds.retainAll(candidates.mapTo(hashSetOf(), ReplaceRule::id))
        selectionVersion++
    }

    @Composable
    private fun ManualReplaceRulesScreen() {
        val context = requireContext()
        val bgColor = Color(context.backgroundColor)
        val textColor = Color(context.primaryTextColor)
        val topBarColor = Color(context.primaryColor)
        val accent = Color(context.accentColor)
        val selectedCount = selectedIds.size
        val isAllSelected = selectedCount == candidates.size && candidates.isNotEmpty()
        val footerText = getString(
            if (isAllSelected) R.string.select_cancel_count else R.string.select_all_count,
            selectedCount,
            candidates.size
        )

        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = bgColor
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topBarColor)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.manual_replace_rule),
                        color = textColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(candidates, key = { it.id }) { item ->
                        val isChecked = item.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manualSourceRule:${item.id}")
                                .heightIn(min = 48.dp)
                                .clickable {
                                    if (isChecked) selectedIds.remove(item.id)
                                    else selectedIds.add(item.id)
                                    selectionVersion++
                                }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedIds.add(item.id)
                                    else selectedIds.remove(item.id)
                                    selectionVersion++
                                },
                                colors = CheckboxDefaults.colors(checkedColor = accent)
                            )
                            Text(
                                text = item.getDisplayNameGroup(),
                                color = textColor,
                                fontSize = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .testTag("manualSourceRules:selectAll")
                            .heightIn(min = 48.dp)
                            .clickable {
                                if (selectedIds.size == candidates.size) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.addAll(candidates.map(ReplaceRule::id))
                                }
                                selectionVersion++
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = footerText,
                            color = textColor,
                            fontSize = 14.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .testTag("manualSourceRules:cancel")
                                .heightIn(min = 48.dp)
                                .widthIn(min = 48.dp)
                                .clickable { dismissAllowingStateLoss() }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.cancel),
                                color = accent
                            )
                        }
                        Box(
                            modifier = Modifier
                                .testTag("manualSourceRules:ok")
                                .heightIn(min = 48.dp)
                                .widthIn(min = 48.dp)
                                .clickable {
                                    val ids = candidates.filter { it.id in selectedIds }.map { it.id }
                                    if (sourceReplacement) {
                                        ((parentFragment as? Callback) ?: (activity as? Callback))?.onManualSourceRulesSelected(
                                            ids,
                                            arguments?.getString("requestId")
                                        )
                                    } else {
                                        ReadBook.book?.let { book ->
                                            book.config.manualReplaceRuleIds = ids
                                            ReadBook.saveRead()
                                            viewModel.replaceRuleChanged()
                                        }
                                    }
                                    dismissAllowingStateLoss()
                                }
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.ok),
                                color = accent
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("selectedIds", selectedIds.toLongArray())
        super.onSaveInstanceState(outState)
    }
}
