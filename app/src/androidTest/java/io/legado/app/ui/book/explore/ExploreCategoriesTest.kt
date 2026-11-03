package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.ui.book.explore.ExploreCategory
import io.legado.app.ui.book.explore.ExploreShowScreen
import io.legado.app.ui.book.explore.splitExploreCategoryRows
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.ExploreBooksRoute
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the migrated Explore detail categories through Compose semantics. */
@RunWith(AndroidJUnit4::class)
class ExploreCategoriesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences
    private val originalCategories = preferences.all[PreferKey.showExploreCategories]
    private val originalCronet = preferences.all[PreferKey.cronet]
    private val categories = List(22) { index -> ExploreCategory("Category $index", "https://example.invalid/$index") }
    private val showCategories = mutableStateOf(true)
    private val selectedCategory = mutableStateOf<ExploreCategory?>(categories.first())
    private val selectedByCallback = mutableStateOf<ExploreCategory?>(null)
    private var directory: File? = null
    private val slowStarted = CountDownLatch(1)
    private val releaseSlow = CountDownLatch(1)
    private val slowFinished = CountDownLatch(1)
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/category/1") {
                slowStarted.countDown()
                releaseSlow.await(20, TimeUnit.SECONDS)
            }
            val category = session.uri.substringAfterLast('/')
            val page = session.parameters["page"]?.firstOrNull() ?: "1"
            val ids = when {
                category == "2" && page == "3" -> (50..69).toList()
                category == "2" && page == "2" -> listOf(51) + (30..48)
                else -> (1..20).toList()
            }
            val html = ids.joinToString("") { id ->
                val path = if (category == "2") "/book/$category/$id" else "/book/$category/$page/$id"
                "<a href='$path'>Category $category page $page book $id</a>"
            }
            if (category == "1") slowFinished.countDown()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
    }
    private lateinit var source: BookSource
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var viewModel: ExploreShowViewModel
    private val app by lazy { context.applicationContext as Application }
    private val serverBase get() = "http://127.0.0.1:${server.listeningPort}"
    private val sourceUrl get() = "$serverBase/source"
    private fun categoryUrl(index: Int) = "$serverBase/category/$index?page={{page}}"

    @get:Rule
    val composeRule = createAndroidComposeRule<AppActivity>()

    @Before
    fun setUp() {
        preferences.edit().putBoolean(PreferKey.cronet, false).commit()
        server.start()
        source = BookSource(
            bookSourceUrl = sourceUrl,
            bookSourceName = "Explore categories fixture",
            exploreUrl = (0 until 22).joinToString("&&") { "Category $it::${categoryUrl(it)}" },
            ruleExplore = ExploreRule(bookList = "tag.a", name = "text", bookUrl = "href"),
        )
        appDb.bookSourceDao.insert(source)
        createViewModel()
        onMain { viewModel.initData(exploreIntent()) }
        awaitViewModel { viewModel.getLoadedBooks().size == 20 }
        render()
    }

    @After
    fun tearDown() {
        releaseSlow.countDown()
        if (::viewModelStore.isInitialized) viewModelStore.clear()
        if (::source.isInitialized) {
            appDb.bookSourceDao.delete(source.bookSourceUrl)
            appDb.openHelper.writableDatabase.execSQL(
                "DELETE FROM searchBooks WHERE origin = ?", arrayOf(source.bookSourceUrl),
            )
        }
        server.stop()
        preferences.edit().apply {
            if (originalCategories == null) remove(PreferKey.showExploreCategories)
            else putBoolean(PreferKey.showExploreCategories, originalCategories as Boolean)
            if (originalCronet == null) remove(PreferKey.cronet)
            else putBoolean(PreferKey.cronet, originalCronet as Boolean)
        }.commit()
        directory?.deleteRecursively()
    }

    @Test
    fun categoriesRenderInBalancedRowsAndSelectionInvokesTheComposeCallback() {
        assertEquals(listOf(8, 7, 7), splitExploreCategoryRows(categories).map { it.size })
        composeRule.onNodeWithText("Category 0").assertIsDisplayed()
        composeRule.onNodeWithText("Category 21").assertIsDisplayed()
        composeRule.onNodeWithText("Category 0 page 1 book 1").assertIsDisplayed()
        val lastCategory = composeRule.onNodeWithText("Category 21").fetchSemanticsNode().boundsInRoot
        val firstBook = composeRule.onNodeWithText("Category 0 page 1 book 1").fetchSemanticsNode().boundsInRoot
        assertTrue("All category rows precede the book grid", lastCategory.bottom <= firstBook.top)

        composeRule.onNodeWithText("Category 8").performClick()

        assertEquals(categories[8], selectedByCallback.value)
    }

    @Test
    fun typedRouteOpensExploreDestinationInAppActivity() {
        val intent = AppActivity.startIntent(
            context,
            ExploreBooksRoute(sourceUrl, "Category 0", categoryUrl(0)),
        )
        ActivityScenario.launch<AppActivity>(intent).use {
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithText("Category 0 page 1 book 1")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Category 0 page 1 book 1").assertIsDisplayed()
        }
    }

    @Test
    fun globalToggleHidesAndRestoresTheComposeCategoryRows() {
        AppConfig.showExploreCategories = true
        composeRule.onNodeWithContentDescription(context.getString(R.string.more_menu)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.show_explore_categories)).performClick()
        assertFalse(AppConfig.showExploreCategories)
        composeRule.onAllNodesWithText("Category 0").assertCountEquals(0)

        // Recreate the detail composition from the app-wide setting, as reopening the page does.
        showCategories.value = AppConfig.showExploreCategories
        render()
        composeRule.onAllNodesWithText("Category 0").assertCountEquals(0)
        composeRule.onNodeWithContentDescription(context.getString(R.string.more_menu)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.show_explore_categories)).performClick()
        assertTrue(AppConfig.showExploreCategories)
        composeRule.onNodeWithText("Category 0").assertIsDisplayed()
    }

    @Test
    fun lateCategoryResponseIsRejectedAndSavedPageRestoresFromState() {
        onMain { viewModel.switchCategory(ExploreCategory("Category 1", categoryUrl(1))) }
        assertTrue("Slow category request starts", slowStarted.await(15, TimeUnit.SECONDS))
        onMain { viewModel.switchCategory(ExploreCategory("Category 8", categoryUrl(8))) }
        awaitViewModel { viewModel.getLoadedBooks().firstOrNull()?.name?.startsWith("Category 8") == true }
        releaseSlow.countDown()
        assertTrue("Slow response finishes", slowFinished.await(15, TimeUnit.SECONDS))

        onMain { viewModel.explore() }
        awaitViewModel { viewModel.uiState.value.page == 2 && viewModel.getLoadedBooks().size == 40 }
        assertTrue(viewModel.getLoadedBooks().all { it.name.startsWith("Category 8") })
        val savedState = Bundle()
        onMain { viewModel.saveState(savedState) }
        assertEquals(2, savedState.getInt("explorePage"))

        viewModelStore.clear()
        createViewModel()
        onMain { viewModel.initData(exploreIntent(), savedState) }
        awaitViewModel { viewModel.uiState.value.page == 2 && viewModel.getLoadedBooks().size == 20 }
        assertEquals(2, viewModel.uiState.value.page)
        assertEquals("Category 8", viewModel.uiState.value.selectedCategory?.title)
        assertTrue(viewModel.getLoadedBooks().all { it.name.startsWith("Category 8") })
    }

    @Test
    fun prependingOverlappingPageDeduplicatesBooksAndReportsInsertedCount() {
        onMain { viewModel.switchCategory(ExploreCategory("Category 2", categoryUrl(2))) }
        awaitViewModel { viewModel.getLoadedBooks().size == 20 }
        onMain { viewModel.skipPage(3); viewModel.explore() }
        awaitViewModel { viewModel.uiState.value.page == 3 && viewModel.getLoadedBooks().size == 20 }
        onMain { viewModel.explore(2) }
        awaitViewModel { viewModel.uiState.value.page == 2 && viewModel.getLoadedBooks().size == 39 }

        val loaded = viewModel.getLoadedBooks()
        assertEquals(39, loaded.size)
        assertEquals(19, viewModel.uiState.value.prependCount)
        assertEquals(39, loaded.map { it.bookUrl }.toSet().size)
        assertTrue(loaded.first().name.startsWith("Category 2 page 2"))
        assertFalse(loaded.last().name.startsWith("Category 2 page 2"))
    }

    @Test
    fun backupRestoresToggleAndLegacyBackupResetsIt() {
        val backupDir = File(context.cacheDir, "explore-backup-${UUID.randomUUID()}").also { directory = it }
        check(backupDir.mkdirs())
        writePreferenceSnapshot(context, backupDir.path, "config") {
            putBoolean(PreferKey.showExploreCategories, true)
        }
        AppConfig.showExploreCategories = false
        runBlocking(Dispatchers.IO) { Restore.restoreLocked(backupDir.path) }
        assertTrue(AppConfig.showExploreCategories)

        writePreferenceSnapshot(context, backupDir.path, "config") { }
        runBlocking(Dispatchers.IO) { Restore.restoreLocked(backupDir.path) }
        assertEquals(false, AppConfig.showExploreCategories)
    }

    private fun render() {
        composeRule.activityRule.scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ComposeView
            root.setContent {
                ExploreShowScreen(
                title = "Explore fixture",
                onBack = null,
                page = 1,
                oldPage = 1,
                showCategories = showCategories.value,
                categories = categories,
                selectedCategory = selectedCategory.value,
                addBooksBusy = false,
                books = viewModel.getLoadedBooks(),
                topLoading = false,
                topError = null,
                bottomLoading = false,
                bottomHasMore = false,
                bottomError = null,
                adapterRefreshVersion = 0,
                isInBookshelf = { false },
                onToggleCategories = {
                    AppConfig.showExploreCategories = !showCategories.value
                    showCategories.value = !showCategories.value
                },
                onAddLoadedBooks = {},
                onChangePageClick = {},
                onSelectCategory = { category ->
                    selectedCategory.value = category
                    selectedByCallback.value = category
                },
                onLoadBottom = {},
                onLoadTop = {},
                    onBookClick = {},
                )
            }
        }
    }

    private fun createViewModel() {
        viewModelStore = ViewModelStore()
        viewModel = ViewModelProvider(
            viewModelStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app),
        )[ExploreShowViewModel::class.java]
    }

    private fun exploreIntent() = Intent().apply {
        putExtra("sourceUrl", source.bookSourceUrl)
        putExtra("exploreName", "Category 0")
        putExtra("exploreUrl", categoryUrl(0))
    }

    private fun awaitViewModel(condition: () -> Boolean) {
        composeRule.waitUntil(15_000, condition)
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
