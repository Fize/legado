package io.legado.app.ui

import android.os.Bundle
import android.content.Context
import android.content.Intent
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.legado.app.R
import io.legado.app.help.storage.RestoreRecoveryGate
import io.legado.app.help.storage.RestoreRecoveryStatus
import io.legado.app.help.storage.backupRestoreMutex
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.association.BookSourceImportCallbacks
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.navigation.AppRoute
import io.legado.app.ui.navigation.LegadoApp
import io.legado.app.ui.navigation.MyRoute
import io.legado.app.ui.navigation.OnlineImportRoute
import io.legado.app.ui.navigation.FileAssociationRoute
import io.legado.app.ui.association.FileAssociationContract
import io.legado.app.data.repository.FileAssociationRequest
import java.util.UUID
import io.legado.app.ui.navigation.TopLevelRoutes
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.getPrefBoolean
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The single Compose host for the Navigation 3 application shell. */
@AndroidEntryPoint
class AppActivity : AppCompatActivity(), BookSourceImportCallbacks {

    private var recoveryRedirected = false

    private val mainViewModel by viewModels<MainViewModel>()
    private var navigationRequest by mutableStateOf<AppRoute?>(null)
    private var externalFileRoute: FileAssociationRoute? = null
    private var fileAssociationInput: Pair<String, FileAssociationRequest>? = null
    internal var currentDestination: AppRoute? = null
        private set
    private var bookSourceImportCallbacks: BookSourceImportCallbacks? = null

    fun bindBookSourceImportCallbacks(callbacks: BookSourceImportCallbacks) {
        bookSourceImportCallbacks = callbacks
    }

    fun unbindBookSourceImportCallbacks(callbacks: BookSourceImportCallbacks) {
        if (bookSourceImportCallbacks === callbacks) bookSourceImportCallbacks = null
    }

    override fun onCodeSave(code: String, requestId: String?) =
        bookSourceImportCallbacks?.onCodeSave(code, requestId) ?: Unit
    override fun onOpenReplaceRules() = bookSourceImportCallbacks?.onOpenReplaceRules() ?: Unit
    override fun onShowSourceReplacements(code: String, requestId: String?, manual: Boolean) =
        bookSourceImportCallbacks?.onShowSourceReplacements(code, requestId, manual) ?: Unit
    override fun isManualSourceReplacementEnabled() =
        bookSourceImportCallbacks?.isManualSourceReplacementEnabled() ?: false
    override fun getCodeAlternate(requestId: String?) =
        bookSourceImportCallbacks?.getCodeAlternate(requestId)
    override fun isReplaceRuleRefreshPending() =
        bookSourceImportCallbacks?.isReplaceRuleRefreshPending() ?: false
    override fun onManualSourceRulesSelected(ids: List<Long>, requestId: String?) =
        bookSourceImportCallbacks?.onManualSourceRulesSelected(ids, requestId) ?: Unit
    override fun onEffectiveSourceRulesChanged() =
        bookSourceImportCallbacks?.onEffectiveSourceRulesChanged() ?: Unit

    internal fun navigateTo(destination: AppRoute) {
        navigationRequest = destination
    }
    private val startupCoordinator by lazy {
        AppStartupCoordinator(this, mainViewModel) { navigationRequest = MyRoute }
    }

    companion object {
        private const val EXTRA_INITIAL_DESTINATION = "initialDestination"

        fun startIntent(context: Context, destination: AppRoute): Intent =
            Intent(context, AppActivity::class.java).putExtra(
                EXTRA_INITIAL_DESTINATION,
                Json.encodeToString<AppRoute>(destination),
            )
    }

    internal val initialDestination: AppRoute? by lazy {
        (intent.getStringExtra(EXTRA_INITIAL_DESTINATION)
            ?.let { runCatching { Json.decodeFromString<AppRoute>(it) }.getOrNull() }
            ?: intent.data?.takeIf {
                intent.action == Intent.ACTION_VIEW &&
                    (it.scheme == "legado" || it.scheme == "yuedu")
            }?.let { OnlineImportRoute(it.toString()) }
            ?: externalFileRoute
            ?: externalSearchQuery()?.let { SearchRoute(it) })
            ?.takeUnless { it in TopLevelRoutes }
    }

    private val isLauncherLaunch: Boolean
        get() = intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)

    private val runsStartupCoordinator: Boolean
        get() = initialDestination == null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(if (RestoreRecoveryGate.isQuarantined) newBase else AppContextWrapper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (RestoreRecoveryGate.isQuarantined) {
            setTheme(R.style.AppTheme_Light)
            super.onCreate(savedInstanceState)
            redirectToRecovery()
            return
        }
        val savedFileRequestId = savedInstanceState?.getString("externalFileRequestId")
        if (!intent.hasExtra(EXTRA_INITIAL_DESTINATION) &&
            FileAssociationContract.accepts(intent, restoringRequest = savedFileRequestId != null)
        ) {
            val id = savedFileRequestId ?: UUID.randomUUID().toString()
            externalFileRoute = FileAssociationRoute(id)
            fileAssociationInput = id to FileAssociationContract.read(intent)
        }
        if (isLauncherLaunch) {
            installSplashScreen()
        } else {
            setTheme(R.style.AppTheme_Light)
        }
        super.onCreate(savedInstanceState)
        if (dispatchExternalIntent()) return
        if (!recoveryRedirected && !RestoreRecoveryGate.isQuarantined && runsStartupCoordinator) startupCoordinator.onCreate()
        enableEdgeToEdge()
        setContent {
            LegadoTheme {
                LegadoApp(
                    initialDestination = initialDestination,
                    navigationRequest = navigationRequest,
                    onNavigationRequestHandled = { navigationRequest = null },
                    onExit = ::finish,
                    onResult = { resultCode, data -> setResult(resultCode, data) },
                    fileAssociationInput = fileAssociationInput,
                    onDestinationChanged = { currentDestination = it },
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RestoreRecoveryGate.status.collect { state ->
                    if (state != RestoreRecoveryStatus.Ready) redirectToRecovery()
                }
            }
        }
        openLastReadBookOnLauncherStart(savedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!recoveryRedirected && !RestoreRecoveryGate.isQuarantined && runsStartupCoordinator) startupCoordinator.onPostCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (!recoveryRedirected) lifecycleScope.launch(Dispatchers.IO) {
            backupRestoreMutex.recover { RestoreRecoveryGate.recordRestoreFailure(applicationContext) }
        }
        if (!recoveryRedirected && !RestoreRecoveryGate.isQuarantined && runsStartupCoordinator) startupCoordinator.onResume()
    }

    override fun onPause() {
        if (!recoveryRedirected && !RestoreRecoveryGate.isQuarantined && runsStartupCoordinator) startupCoordinator.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!recoveryRedirected && !RestoreRecoveryGate.isQuarantined && runsStartupCoordinator) startupCoordinator.onWindowFocusChanged(hasFocus)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        externalFileRoute?.let { outState.putString("externalFileRequestId", it.requestId) }
        if (!recoveryRedirected && !RestoreRecoveryGate.isQuarantined && runsStartupCoordinator) startupCoordinator.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!recoveryRedirected && !RestoreRecoveryGate.isQuarantined && runsStartupCoordinator) startupCoordinator.onDestroy()
        super.onDestroy()
    }

    private fun redirectToRecovery() {
        if (recoveryRedirected) return
        recoveryRedirected = true
        startActivity(RestoreRecoveryActivity.intent(this))
        finish()
    }

    private fun openLastReadBookOnLauncherStart(savedInstanceState: Bundle?) {
        if (savedInstanceState != null || !isLauncherLaunch ||
            !getPrefBoolean(PreferKey.defaultToRead)
        ) return
        lifecycleScope.launch {
            val lastBook = withContext(Dispatchers.IO) { appDb.bookDao.lastReadBook }
            if (lastBook != null && !isFinishing) startActivityForBook(lastBook)
        }
    }

    private fun dispatchExternalIntent(): Boolean {
        if (initialDestination is FileAssociationRoute) return false
        if (intent.getStringExtra("action") == "readAloud") {
            MediaButtonReceiver.readAloud(this, false)
            finish()
            return true
        }
        return false
    }

    private fun externalSearchQuery(): String? {
        val sharedText = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.action == Intent.ACTION_PROCESS_TEXT && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null

        val containsUrl = sharedText.split(Regex("\\s+"))
            .any { it.startsWith("http://") || it.startsWith("https://") }
        return sharedText.takeUnless { containsUrl }
    }
}
