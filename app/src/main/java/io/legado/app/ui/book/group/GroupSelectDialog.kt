package io.legado.app.ui.book.group

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class GroupSelectDialog() : BaseComposeDialogFragment() {

    constructor(groupId: Long, requestCode: Int = -1) : this() {
        arguments = Bundle().apply {
            putLong("groupId", groupId)
            putInt("requestCode", requestCode)
        }
    }

    private var requestCode: Int = -1
    private val viewModel: GroupViewModel by viewModels()
    private val callBack get() = (activity as? CallBack)
    private var groupIdState by mutableLongStateOf(0L)
    private var groupsState by mutableStateOf<List<BookGroup>>(emptyList())

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            groupIdState = it.getLong("groupId")
            requestCode = it.getInt("requestCode", -1)
        }
        initData()
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowSelect().conflate().collect {
                groupsState = it
            }
        }
    }

    private fun moveGroup(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in groupsState.indices || toIndex !in groupsState.indices) return
        val mutable = groupsState.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        mutable.forEachIndexed { idx, item ->
            item.order = idx + 1
        }
        groupsState = mutable
        viewModel.upGroup(*mutable.toTypedArray())
    }

    @Composable
    override fun DialogContent() {
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.group_select),
                    onNavigationClick = { dismissAllowingStateLoss() },
                    actions = {
                        IconButton(
                            onClick = { showDialogFragment(GroupEditDialog()) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { dismissAllowingStateLoss() }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                callBack?.upGroup(requestCode, groupIdState)
                                dismissAllowingStateLoss()
                            }
                        ) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                itemsIndexed(
                    items = groupsState,
                    key = { _, item -> item.groupId },
                ) { index, item ->
                    val isChecked = (groupIdState and item.groupId) > 0L
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                groupIdState = if (!isChecked) {
                                    groupIdState + item.groupId
                                } else {
                                    groupIdState - item.groupId
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                groupIdState = if (checked) {
                                    groupIdState + item.groupId
                                } else {
                                    groupIdState - item.groupId
                                }
                            },
                        )
                        Text(
                            text = item.groupName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        IconButton(
                            onClick = { moveGroup(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = { moveGroup(index, index + 1) },
                            enabled = index < groupsState.lastIndex,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = { showDialogFragment(GroupEditDialog(item)) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    interface CallBack {
        fun upGroup(requestCode: Int, groupId: Long)
    }
}