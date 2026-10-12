@file:Suppress("DEPRECATION")

package io.legado.app.ui.main

import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.postDelayed
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
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
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.about.UpdateDialog
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.style1.BookshelfFragment1
import io.legado.app.ui.main.bookshelf.style2.BookshelfFragment2
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.my.MyFragment
import io.legado.app.utils.clearClip
import io.legado.app.utils.getClipText
import io.legado.app.utils.isCreated
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.shouldUseTwoPane
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.hours

/**
 * 主界面
 */
@Suppress("PrivatePropertyName")
class MainActivity : VMBaseComposeActivity<MainViewModel>(),
    MainViewModel.CallBack {

    override val viewModel by viewModels<MainViewModel>()
    private val idBookshelf = 0
    private val idBookshelf1 = 11
    private val idBookshelf2 = 12
    private val idExplore = 1
    private val idMy = 2
    private var exitTime: Long = 0
    private var bookshelfReselected: Long = 0
    private var exploreReselected: Long = 0
    private var pagePosition by mutableIntStateOf(0)
    private val fragmentMap = hashMapOf<Int, Fragment>()
    private var bottomMenuCount = 3
    private val EXIT_INTERVAL = 2000L
    private val realPositions = arrayOf(idBookshelf, idExplore, idMy)
    private val adapter by lazy {
        TabFragmentPageAdapter(supportFragmentManager)
    }
    private var viewPagerRef: ViewPager? = null
    private var selectedTabIdState by mutableIntStateOf(idBookshelf)
    private var showDiscoveryState by mutableStateOf(true)
    private var upBooksCountState by mutableIntStateOf(0)
    private var lastPassphraseText: String? = null
    private var pendingPassphraseRead = false
    private var passphraseReadGeneration = 0

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        upBottomMenu()
        upHomePage()
        onBackPressedDispatcher.addCallback(this) {
            if (pagePosition != 0) {
                pagePosition = 0
                selectedTabIdState = realPositions[0]
                viewPagerRef?.currentItem = 0
                return@addCallback
            }
            (fragmentMap[getFragmentId(0)] as? BookshelfFragment2)?.let {
                if (it.back()) {
                    return@addCallback
                }
            }
            if (System.currentTimeMillis() - exitTime > EXIT_INTERVAL) {
                toastOnUi(R.string.double_click_exit)
                exitTime = System.currentTimeMillis()
            } else {
                if (BaseReadAloudService.pause) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    @Composable
    override fun Content() {
        val config = LocalConfiguration.current
        val useRail = shouldUseTwoPane(
            config.screenWidthDp,
            config.screenHeightDp,
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            if (useRail) {
                MainNavigationRail(
                    selectedTabId = selectedTabIdState,
                    showDiscovery = showDiscoveryState,
                    upBooksCount = upBooksCountState,
                    onTabSelected = ::onTabSelected,
                    onTabReselected = ::onTabReselected,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        ViewPager(ctx).apply {
                            id = R.id.view_pager_main
                            setEdgeEffectColor(primaryColor)
                            offscreenPageLimit = 3
                            adapter = this@MainActivity.adapter
                            addOnPageChangeListener(PageChangeCallback())
                            viewPagerRef = this
                            val targetPos = pagePosition.coerceIn(0, (bottomMenuCount - 1).coerceAtLeast(0))
                            if (currentItem != targetPos) {
                                setCurrentItem(targetPos, false)
                            }
                        }
                    },
                    update = { pager ->
                        val targetPos = pagePosition.coerceIn(0, (bottomMenuCount - 1).coerceAtLeast(0))
                        if (pager.currentItem != targetPos) {
                            pager.setCurrentItem(targetPos, false)
                        }
                    },
                )

                if (!useRail) {
                    MainNavigationBar(
                        selectedTabId = selectedTabIdState,
                        showDiscovery = showDiscoveryState,
                        upBooksCount = upBooksCountState,
                        onTabSelected = ::onTabSelected,
                        onTabReselected = ::onTabReselected,
                    )
                }
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        lifecycleScope.launch {
            upVersion()
            notifyAppCrash()
            backupSync()
            viewModel.setActivityCallback(this@MainActivity)
            window.decorView.postDelayed(1000) {
                viewModel.ruleSubsUp()
            }
            scheduleSourceSharePassphraseRead(1500)
            val isAutoRefreshedBook = savedInstanceState?.getBoolean("isAutoRefreshedBook") ?: false
            if (AppConfig.autoRefreshBook && !isAutoRefreshedBook) {
                window.decorView.postDelayed(2000) {
                    viewModel.upAllBookToc()
                }
            }
            window.decorView.postDelayed(3000) {
                viewModel.postLoad()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (SourceSharePassphraseImportPolicy.shouldScheduleOnResume(
                privacyPolicyOk = LocalConfig.privacyPolicyOk
            )
        ) {
            scheduleSourceSharePassphraseRead(500)
        }
    }

    override fun onPause() {
        super.onPause()
        pendingPassphraseRead = false
        passphraseReadGeneration++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingPassphraseRead && canAwaitPassphraseWindowFocus()) {
            readSourceSharePassphrase(200)
        } else if (hasFocus) {
            pendingPassphraseRead = false
        }
    }

    private fun onTabSelected(tabId: Int) {
        val targetPos = when (tabId) {
            idBookshelf -> 0
            idExplore -> realPositions.indexOf(idExplore).coerceAtLeast(0)
            idMy -> realPositions.indexOf(idMy).coerceAtLeast(0)
            else -> 0
        }
        pagePosition = targetPos
        selectedTabIdState = realPositions[targetPos]
        viewPagerRef?.setCurrentItem(targetPos, false)
    }

    private fun onTabReselected(tabId: Int) {
        when (tabId) {
            idBookshelf -> {
                if (System.currentTimeMillis() - bookshelfReselected > 300) {
                    bookshelfReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.gotoTop()
                }
            }

            idExplore -> {
                if (System.currentTimeMillis() - exploreReselected > 300) {
                    exploreReselected = System.currentTimeMillis()
                } else {
                    (fragmentMap[idExplore] as? ExploreFragment)?.compressExplore()
                }
            }
        }
    }

    private fun upVersion() {
        if (LocalConfig.versionCode == appInfo.versionCode) {
            if (AppConfig.autoUpdateVariant) {
                if (LocalConfig.lastCheckUpdate + 24.hours.inWholeMilliseconds < System.currentTimeMillis()) {
                    AppUpdate.gitHubUpdate.check(lifecycleScope)
                        .onSuccess {
                            if (isIgnoredAppUpdate(it.tagName, LocalConfig.ignoreUpdateVersion)) {
                                return@onSuccess
                            }
                            if (supportFragmentManager.isStateSaved) return@onSuccess
                            showDialogFragment(
                                UpdateDialog(it)
                            )
                        }
                    LocalConfig.lastCheckUpdate = System.currentTimeMillis()
                }
            }
            return
        }
        LocalConfig.versionCode = appInfo.versionCode
    }

    private fun notifyAppCrash() {
        if (!LocalConfig.appCrash || BuildConfig.DEBUG) {
            return
        }
        LocalConfig.appCrash = false
        alert(getString(R.string.draw), "检测到阅读发生了崩溃，是否打开崩溃日志以便报告问题？") {
            yesButton {
                showDialogFragment<CrashLogsDialog>()
            }
            noButton()
        }
    }

    private fun backupSync() {
        if (!AppConfig.autoCheckNewBackup) {
            return
        }
        lifecycleScope.launch {
            val lastBackupFile =
                withContext(IO) { AppWebDav.lastBackUp().getOrNull() } ?: return@launch
            if (lastBackupFile.lastModify - LocalConfig.lastBackup > DateUtils.MINUTE_IN_MILLIS) {
                alert(R.string.restore, R.string.webdav_after_local_restore_confirm) {
                    cancelButton {
                        LocalConfig.lastBackup = maxOf(
                            LocalConfig.lastBackup,
                            lastBackupFile.lastModify,
                        )
                    }
                    okButton {
                        viewModel.restoreWebDav(
                            lastBackupFile.displayName,
                            lastBackupFile.lastModify,
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (AppConfig.autoRefreshBook) {
            outState.putBoolean("isAutoRefreshedBook", true)
        }
    }

    override fun onDestroy() {
        viewPagerRef = null
        super.onDestroy()
        Coroutine.async {
            BookHelp.clearInvalidCache()
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun recreate() {
        (fragmentMap[getFragmentId(0)] as? BaseBookshelfFragment)?.run {
            upSort()
        }
        super.recreate()
    }

    override fun observeLiveBus() {
        viewModel.onUpBooksLiveData.observe(this) {
            upBooksCountState = it
        }
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            upBottomMenu()
            if (it) {
                val targetPos = (bottomMenuCount - 1).coerceAtLeast(0)
                pagePosition = targetPos
                selectedTabIdState = realPositions[targetPos]
                viewPagerRef?.setCurrentItem(targetPos, false)
            }
        }
        observeEvent<String>(PreferKey.threadCount) {
            viewModel.upPool()
        }
        observeEvent<List<Book>>(EventBus.UP_BOOKS_TOC) {
            viewModel.upToc(
                it,
                onlyUpdateRead = false,
                policy = TocUpdatePolicy.SKIP_PRE_DOWNLOAD,
                refreshBookInfo = true,
            )
        }
    }

    private fun upBottomMenu() {
        val showDiscovery = AppConfig.showDiscovery
        showDiscoveryState = showDiscovery
        var index = 0
        if (showDiscovery) {
            index++
            realPositions[index] = idExplore
        }
        index++
        realPositions[index] = idMy
        bottomMenuCount = index + 1
        adapter.notifyDataSetChanged()
        pagePosition = pagePosition.coerceIn(0, bottomMenuCount - 1)
        selectedTabIdState = realPositions[pagePosition]
    }

    private fun scheduleSourceSharePassphraseRead(delayMillis: Long) {
        passphraseReadGeneration++
        pendingPassphraseRead = true
        if (hasWindowFocus()) {
            readSourceSharePassphrase(delayMillis, passphraseReadGeneration)
        }
    }

    private fun canAwaitPassphraseWindowFocus(): Boolean {
        return SourceSharePassphraseImportPolicy.canAwaitWindowFocus(
            privacyPolicyOk = LocalConfig.privacyPolicyOk,
            isFinishing = isFinishing,
            isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            isFragmentStateSaved = supportFragmentManager.isStateSaved
        )
    }

    private fun readSourceSharePassphrase(
        delayMillis: Long,
        generation: Int = passphraseReadGeneration
    ) {
        pendingPassphraseRead = false
        window.decorView.postDelayed(delayMillis) {
            if (generation != passphraseReadGeneration) return@postDelayed
            val hasWindowFocus = hasWindowFocus()
            if (!hasWindowFocus) {
                pendingPassphraseRead = canAwaitPassphraseWindowFocus()
            }
            if (!SourceSharePassphraseImportPolicy.canReadClipboard(
                    privacyPolicyOk = LocalConfig.privacyPolicyOk,
                    isFinishing = isFinishing,
                    isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                    isFragmentStateSaved = supportFragmentManager.isStateSaved,
                    hasWindowFocus = hasWindowFocus
                )
            ) {
                return@postDelayed
            }
            val text = getClipText()?.takeIf { it.isNotBlank() } ?: return@postDelayed
            if (text == lastPassphraseText) return@postDelayed
            when (val result = SourceSharePassphrase.decode(text)) {
                SourceSharePassphrase.DecodeResult.NotFound -> {
                    lastPassphraseText = null
                }

                SourceSharePassphrase.DecodeResult.Invalid -> {
                    lastPassphraseText = text
                    toastOnUi(R.string.shibboleth_invalid)
                }

                SourceSharePassphrase.DecodeResult.Expired -> {
                    lastPassphraseText = text
                    toastOnUi(R.string.shibboleth_expired)
                }

                is SourceSharePassphrase.DecodeResult.Success -> {
                    lastPassphraseText = text
                    clearClip()
                    val value = result.value
                    when (value.type) {
                        SourceSharePassphrase.Type.BOOK_SOURCE ->
                            showDialogFragment(ImportBookSourceDialog(value.url))
                        SourceSharePassphrase.Type.DICT_RULE ->
                            showDialogFragment(ImportDictRuleDialog(value.url))

                        SourceSharePassphrase.Type.REPLACE_RULE ->
                            showDialogFragment(ImportReplaceRuleDialog(value.url))

                        SourceSharePassphrase.Type.TOC_RULE ->
                            showDialogFragment(ImportTxtTocRuleDialog(value.url))

                        SourceSharePassphrase.Type.TTS_RULE ->
                            showDialogFragment(ImportHttpTtsDialog(value.url))
                    }
                }
            }
        }
    }

    private fun upHomePage() {
        val targetPos = when (AppConfig.defaultHomePage) {
            "explore" -> if (AppConfig.showDiscovery) realPositions.indexOf(idExplore).coerceAtLeast(0) else 0
            "my" -> realPositions.indexOf(idMy).coerceAtLeast(0)
            else -> 0
        }
        pagePosition = targetPos
        selectedTabIdState = realPositions[targetPos]
        viewPagerRef?.setCurrentItem(targetPos, false)
    }

    private fun getFragmentId(position: Int): Int {
        val id = realPositions[position]
        if (id == idBookshelf) {
            return if (AppConfig.bookGroupStyle == 1) idBookshelf2 else idBookshelf1
        }
        return id
    }

    private inner class PageChangeCallback : ViewPager.SimpleOnPageChangeListener() {

        override fun onPageSelected(position: Int) {
            pagePosition = position
            selectedTabIdState = realPositions[position]
        }

    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter(fm: FragmentManager) :
        FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        private fun getId(position: Int): Int {
            return getFragmentId(position)
        }

        override fun getItemPosition(any: Any): Int {
            val position = (any as MainFragmentInterface).position
                ?: return POSITION_NONE
            val fragmentId = getId(position)
            if ((fragmentId == idBookshelf1 && any is BookshelfFragment1)
                || (fragmentId == idBookshelf2 && any is BookshelfFragment2)
                || (fragmentId == idExplore && any is ExploreFragment)
                || (fragmentId == idMy && any is MyFragment)
            ) {
                return POSITION_UNCHANGED
            }
            return POSITION_NONE
        }

        override fun getItem(position: Int): Fragment {
            return when (getId(position)) {
                idBookshelf1 -> BookshelfFragment1(position)
                idBookshelf2 -> BookshelfFragment2(position)
                idExplore -> ExploreFragment(position)
                else -> MyFragment(position)
            }
        }

        override fun getCount(): Int {
            return bottomMenuCount
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            var fragment = super.instantiateItem(container, position) as Fragment
            if (fragment.isCreated && getItemPosition(fragment) == POSITION_NONE) {
                destroyItem(container, position, fragment)
                fragment = super.instantiateItem(container, position) as Fragment
            }
            fragmentMap[getId(position)] = fragment
            return fragment
        }

    }

    override fun openImportUi(type: Int, source: String) {
        when (type) {
            0 -> showDialogFragment(
                ImportBookSourceDialog(source)
            )
            2 -> showDialogFragment(
                ImportReplaceRuleDialog(source)
            )
        }
    }

}
