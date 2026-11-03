package io.legado.app.ui

import android.os.Bundle
import android.text.format.DateUtils
import androidx.core.view.postDelayed
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.SourceSharePassphraseImportPolicy
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.update.AppUpdate
import io.legado.app.help.update.isIgnoredAppUpdate
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.navigation.ImportKind
import io.legado.app.ui.navigation.ImportRoute
import io.legado.app.ui.navigation.BookSourceImportRoute
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.TocUpdatePolicy
import io.legado.app.utils.clearClip
import io.legado.app.utils.getClipText
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.hours

/** Runs application-shell lifecycle work that used to live in the retired main Activity. */
internal class AppStartupCoordinator(
    private val activity: AppActivity,
    private val viewModel: MainViewModel,
    private val navigateToMy: () -> Unit,
) : MainViewModel.CallBack {

    private var lastPassphraseText: String? = null
    private var pendingPassphraseRead = false
    private var passphraseReadGeneration = 0

    fun onCreate() {
        activity.observeEvent<String>(EventBus.RECREATE) { activity.recreate() }
        activity.observeEvent<Boolean>(EventBus.NOTIFY_MAIN) { showMy ->
            if (showMy) navigateToMy()
        }
        activity.observeEvent<String>(PreferKey.threadCount) { viewModel.upPool() }
        activity.observeEvent<List<Book>>(EventBus.UP_BOOKS_TOC) {
            viewModel.upToc(
                it,
                onlyUpdateRead = false,
                policy = TocUpdatePolicy.SKIP_PRE_DOWNLOAD,
                refreshBookInfo = true,
            )
        }
    }

    fun onPostCreate(savedInstanceState: Bundle?) {
        activity.lifecycleScope.launch {
            updateVersion()
            notifyAppCrash()
            backupSync()
            viewModel.setActivityCallback(this@AppStartupCoordinator)
            activity.window.decorView.postDelayed(1000) { viewModel.ruleSubsUp() }
            scheduleSourceSharePassphraseRead(1500)
            val refreshed = savedInstanceState?.getBoolean(AUTO_REFRESHED_BOOKS) == true
            if (AppConfig.autoRefreshBook && !refreshed) {
                activity.window.decorView.postDelayed(2000) { viewModel.upAllBookToc() }
            }
            activity.window.decorView.postDelayed(3000) { viewModel.postLoad() }
        }
    }

    fun onResume() {
        if (SourceSharePassphraseImportPolicy.shouldScheduleOnResume(LocalConfig.privacyPolicyOk)) {
            scheduleSourceSharePassphraseRead(500)
        }
    }

    fun onPause() {
        pendingPassphraseRead = false
        passphraseReadGeneration++
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus && pendingPassphraseRead && canAwaitPassphraseWindowFocus()) {
            readSourceSharePassphrase(200)
        } else if (hasFocus) {
            pendingPassphraseRead = false
        }
    }

    fun onSaveInstanceState(outState: Bundle) {
        if (AppConfig.autoRefreshBook) outState.putBoolean(AUTO_REFRESHED_BOOKS, true)
    }

    fun onDestroy() {
        Coroutine.async { BookHelp.clearInvalidCache() }
        if (!BuildConfig.DEBUG) Backup.autoBack(activity)
    }

    private fun updateVersion() {
        if (LocalConfig.versionCode == appInfo.versionCode) {
            if (AppConfig.autoUpdateVariant &&
                LocalConfig.lastCheckUpdate + 24.hours.inWholeMilliseconds < System.currentTimeMillis()
            ) {
                AppUpdate.gitHubUpdate.check(activity.lifecycleScope)
                    .onSuccess {
                        if (isIgnoredAppUpdate(it.tagName, LocalConfig.ignoreUpdateVersion)) return@onSuccess
                        if (activity.supportFragmentManager.isStateSaved) return@onSuccess
                        activity.showDialogFragment(UpdateDialog(it))
                    }
                LocalConfig.lastCheckUpdate = System.currentTimeMillis()
            }
            return
        }
        LocalConfig.versionCode = appInfo.versionCode
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) return
        LocalConfig.appCrash = false
        activity.alert(activity.getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton { activity.showDialogFragment<CrashLogsDialog>() }
            noButton()
        }
    }

    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) return
        activity.lifecycleScope.launch {
            val lastBackupFile = withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                activity.alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton {
                        LocalConfig.lastBackup = maxOf(LocalConfig.lastBackup, lastBackupFile.lastModify)
                    }
                    okButton {
                        viewModel.restoreWebDav(lastBackupFile.displayName, lastBackupFile.lastModify)
                    }
                }
            }
        }
    }

    private fun scheduleSourceSharePassphraseRead(delayMillis: Long) {
        passphraseReadGeneration++
        pendingPassphraseRead = true
        if (activity.hasWindowFocus()) readSourceSharePassphrase(delayMillis, passphraseReadGeneration)
    }

    private fun canAwaitPassphraseWindowFocus() =
        SourceSharePassphraseImportPolicy.canAwaitWindowFocus(
            privacyPolicyOk = LocalConfig.privacyPolicyOk,
            isFinishing = activity.isFinishing,
            isResumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            isFragmentStateSaved = activity.supportFragmentManager.isStateSaved,
        )

    private fun readSourceSharePassphrase(
        delayMillis: Long,
        generation: Int = passphraseReadGeneration,
    ) {
        pendingPassphraseRead = false
        activity.window.decorView.postDelayed(delayMillis) {
            if (generation != passphraseReadGeneration) return@postDelayed
            val hasWindowFocus = activity.hasWindowFocus()
            if (!hasWindowFocus) pendingPassphraseRead = canAwaitPassphraseWindowFocus()
            if (!SourceSharePassphraseImportPolicy.canReadClipboard(
                    privacyPolicyOk = LocalConfig.privacyPolicyOk,
                    isFinishing = activity.isFinishing,
                    isResumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    isFragmentStateSaved = activity.supportFragmentManager.isStateSaved,
                    hasWindowFocus = hasWindowFocus,
                )
            ) return@postDelayed
            val text = activity.getClipText()?.takeIf { it.isNotBlank() } ?: return@postDelayed
            if (text == lastPassphraseText) return@postDelayed
            when (val result = SourceSharePassphrase.decode(text)) {
                SourceSharePassphrase.DecodeResult.NotFound -> lastPassphraseText = null
                SourceSharePassphrase.DecodeResult.Invalid -> {
                    lastPassphraseText = text
                    activity.toastOnUi(R.string.shibboleth_invalid)
                }
                SourceSharePassphrase.DecodeResult.Expired -> {
                    lastPassphraseText = text
                    activity.toastOnUi(R.string.shibboleth_expired)
                }
                is SourceSharePassphrase.DecodeResult.Success -> {
                    lastPassphraseText = text
                    activity.clearClip()
                    val value = result.value
                    when (value.type) {
                        SourceSharePassphrase.Type.BOOK_SOURCE ->
                            activity.navigateTo(BookSourceImportRoute(value.url))
                        SourceSharePassphrase.Type.DICT_RULE ->
                            activity.navigateTo(ImportRoute(value.url, ImportKind.DictionaryRule))
                        SourceSharePassphrase.Type.REPLACE_RULE ->
                            activity.showDialogFragment(ImportReplaceRuleDialog(value.url))
                        SourceSharePassphrase.Type.TOC_RULE ->
                            activity.navigateTo(ImportRoute(value.url, ImportKind.TextTocRule))
                        SourceSharePassphrase.Type.TTS_RULE ->
                            activity.navigateTo(ImportRoute(value.url, ImportKind.HttpTts))
                    }
                }
            }
        }
    }

    override fun openImportUi(type: Int, source: String) {
        when (type) {
            0 -> activity.navigateTo(BookSourceImportRoute(source))
            2 -> activity.showDialogFragment(ImportReplaceRuleDialog(source))
        }
    }

    private companion object {
        const val AUTO_REFRESHED_BOOKS = "isAutoRefreshedBook"
    }
}
