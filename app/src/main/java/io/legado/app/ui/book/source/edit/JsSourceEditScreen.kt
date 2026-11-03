package io.legado.app.ui.book.source.edit

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withStateAtLeast
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.ui.AppActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class JsSourceEditUiState(
    val openedSourceUrl: String?,
    val pendingText: String? = null,
    val stage: JsSourceEditStage = JsSourceEditStage.READY,
)

private val JsSourceEditUiStateSaver = Saver<JsSourceEditUiState, List<Any?>>(
    save = { listOf(it.openedSourceUrl, it.pendingText, it.stage.name) },
    restore = { values ->
        JsSourceEditUiState(
            openedSourceUrl = values[0] as String?,
            pendingText = values[1] as String?,
            stage = runCatching { JsSourceEditStage.valueOf(values[2] as String) }
                .getOrDefault(JsSourceEditStage.READY),
        )
    },
)

@Composable
fun JsSourceEditScreen(
    sourceUrl: String?,
    onBack: () -> Unit,
    onResult: (Int, Intent?) -> Unit,
) {
    val activity = checkNotNull(LocalActivity.current as? AppCompatActivity)
    val scope = rememberCoroutineScope()
    var state by rememberSaveable(sourceUrl, stateSaver = JsSourceEditUiStateSaver) {
        mutableStateOf(JsSourceEditUiState(openedSourceUrl = sourceUrl))
    }

    lateinit var openEditor: (String) -> Unit
    lateinit var saveSource: (String, Boolean, Boolean, ((BookSource) -> Unit)?) -> Unit
    lateinit var saveForLogin: (String) -> Unit
    lateinit var launchLoginWhenResumed: () -> Unit

    val editorResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data?.getStringExtra("text")
        if (result.resultCode != Activity.RESULT_OK || text == null) {
            state = state.copy(stage = JsSourceEditStage.READY)
            onBack()
        } else {
            val loginRequested = result.data?.getStringExtra(CodeEditActivity.EXTRA_RESULT_ACTION) ==
                CodeEditActivity.RESULT_ACTION_LOGIN_SOURCE
            state = state.copy(
                pendingText = text,
                stage = stageForEditorResult(loginRequested),
            )
            if (loginRequested) saveForLogin(text) else saveSource(text, true, true, null)
        }
    }
    val loginResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        state = state.copy(stage = state.stage.afterLoginResult())
        state.pendingText?.let(openEditor) ?: onBack()
    }

    openEditor = { text ->
        if (state.stage != JsSourceEditStage.EDITOR_OPEN && !activity.isFinishing) {
            state = state.copy(stage = JsSourceEditStage.EDITOR_OPEN)
            editorResult.launch(
                Intent(activity, CodeEditActivity::class.java).apply {
                    putExtra("text", text)
                    putExtra("title", activity.getString(R.string.js_source_edit))
                    putExtra("languageName", "source.js")
                    putExtra("returnUnchangedText", true)
                    putExtra(CodeEditActivity.EXTRA_CHECK_JAVASCRIPT_SYNTAX, true)
                    putExtra(CodeEditActivity.EXTRA_SHOW_LOGIN_SOURCE, true)
                },
            )
        }
    }
    launchLoginWhenResumed = launchLogin@{
        val openedSourceUrl = state.openedSourceUrl
        if (openedSourceUrl == null) {
            val text = state.pendingText ?: run {
                onBack()
                return@launchLogin
            }
            state = state.copy(stage = JsSourceEditStage.SAVING_FOR_LOGIN)
            saveForLogin(text)
        } else {
            scope.launch {
                activity.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                    if (state.stage == JsSourceEditStage.LOGIN_READY) {
                        state = state.copy(stage = JsSourceEditStage.LOGIN_OPEN)
                        loginResult.launch(
                        AppActivity.startIntent(
                            activity,
                            SourceLoginRoute(
                                SourceLoginTarget.BookSource,
                                key = openedSourceUrl,
                            ),
                        ),
                        )
                    }
                }
            }
        }
    }
    saveSource = { text, showSuccessToast, finishAfterSave, success ->
        scope.launch {
            try {
                val source = JsSourceUpsert.save(text, state.openedSourceUrl)
                state = state.copy(
                    openedSourceUrl = source.bookSourceUrl,
                    pendingText = source.mainJs ?: text,
                    stage = state.stage.afterSuccessfulSave(),
                )
                if (showSuccessToast) activity.toastOnUi(R.string.success)
                onResult(
                    Activity.RESULT_OK,
                    Intent().putExtra("origin", source.bookSourceUrl),
                )
                success?.invoke(source)
                if (finishAfterSave) onBack()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                state = state.copy(stage = JsSourceEditStage.READY)
                activity.toastOnUi(error.localizedMessage)
                openEditor(text)
            }
        }
    }
    saveForLogin = { text ->
        saveSource(text, false, false) { source ->
            if (source.hasLogin()) {
                launchLoginWhenResumed()
            } else {
                state = state.copy(stage = JsSourceEditStage.READY)
                activity.toastOnUi(R.string.source_no_login)
                state.pendingText?.let(openEditor) ?: onBack()
            }
        }
    }

    LaunchedEffect(sourceUrl) {
        val text = state.pendingText ?: withContext(Dispatchers.IO) {
            state.openedSourceUrl
                ?.let { appDb.bookSourceDao.getBookSource(it)?.mainJs }
                .orEmpty()
        }
        state = state.copy(pendingText = text)
        when (state.stage.restoreAction()) {
            JsSourceEditRestoreAction.OPEN_EDITOR -> openEditor(text)
            JsSourceEditRestoreAction.SAVE_AND_FINISH -> saveSource(text, true, true, null)
            JsSourceEditRestoreAction.SAVE_FOR_LOGIN -> saveForLogin(text)
            JsSourceEditRestoreAction.LAUNCH_LOGIN -> launchLoginWhenResumed()
            JsSourceEditRestoreAction.AWAIT_RESULT -> Unit
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
