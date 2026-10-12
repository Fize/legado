package io.legado.app.ui.widget.dialog

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.BaseViewModel
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout

class VariableDialog() : BaseDialogFragment(adaptationSoftKeyboard = true) {

    private val viewModel by viewModels<ViewModel>()
    private var variableState by mutableStateOf("")
    private var commentState by mutableStateOf("")

    constructor(title: String, key: String, variable: String?, comment: String) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("key", key)
            putString("variable", variable)
            putString("comment", comment)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val dialogTitle = arguments?.getString("title") ?: "Set variable"
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val context = LocalContext.current
                    val barBg = Color(context.primaryColor)
                    val barContent = Color(context.primaryTextColor)
                    val accent = Color(context.accentColor)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colorResource(R.color.background_card),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                TopAppBar(
                                    title = { Text(dialogTitle) },
                                    actions = {
                                        IconButton(
                                            onClick = {
                                                callback?.setVariable(
                                                    viewModel.key ?: "",
                                                    variableState.takeIf { it.isNotEmpty() }
                                                )
                                                dismissAllowingStateLoss()
                                            }
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_save),
                                                contentDescription = stringResource(R.string.action_save),
                                                tint = barContent
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = barBg,
                                        titleContentColor = barContent,
                                        actionIconContentColor = barContent
                                    ),
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )

                                OutlinedTextField(
                                    value = variableState,
                                    onValueChange = { variableState = it },
                                    label = { Text("variable") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .padding(top = 6.dp, bottom = 12.dp)
                                        .padding(horizontal = 16.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.variable_comment),
                                        color = accent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(3.dp)
                                    )
                                    SelectionContainer {
                                        Text(
                                            text = commentState,
                                            color = colorResource(R.color.secondaryText),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(3.dp)
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

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments
        if (args == null) {
            dismiss()
            return
        }
        viewModel.init(args) {
            commentState = viewModel.comment.orEmpty()
            variableState = viewModel.variable.orEmpty()
        }
    }

    val callback get() = (parentFragment as? Callback) ?: (activity as? Callback)

    class ViewModel(application: Application) : BaseViewModel(application) {

        var key: String? = null
        var comment: String? = null
        var variable: String? = null

        fun init(arguments: Bundle, onFinally: () -> Unit) {
            if (key != null) {
                onFinally.invoke()
                return
            }
            execute {
                key = arguments.getString("key")
                comment = arguments.getString("comment")
                variable = arguments.getString("variable")
            }.onFinally {
                onFinally.invoke()
            }
        }

    }

    interface Callback {

        fun setVariable(key: String, variable: String?)

    }

}