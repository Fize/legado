package io.legado.app.ui.main.explore

import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.help.CacheManager
import io.legado.app.ui.AppActivity
import io.legado.app.help.source.clearExploreKindsCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Verifies dynamic source controls and refresh callbacks on the Navigation 3 Explore route. */
@RunWith(AndroidJUnit4::class)
class ExploreRefreshUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val sourceId = UUID.randomUUID().toString()
    private val source = BookSource(
        bookSourceUrl = "https://explore-refresh.invalid/$sourceId",
        bookSourceName = "Explore refresh $sourceId",
        exploreUrl = """
            <js>
            var state = JSON.parse(String(source.getLoginHeader() || '{}'));
            var kinds = [], row = {layout_flexBasisPercent: 1};
            function label(title) { kinds.push({title: title, style: row}); }
            kinds.push({title:'More categories', type:'toggle', chars:['+ ', '- '],
                action:"changeKind('expanded', infoMap['More categories'])", style:row});
            kinds.push({title:'Category mode', type:'select', chars:['Short list', 'Long list'],
                action:"changeKind('mode', infoMap['Category mode'])", style:row});
            kinds.push({title:'Search categories', type:'text', style:row});
            kinds.push({title:'Refresh controls', type:'button',
                action:"changeKind('button', 'pressed')", style:row});
            label('Rendered ' + (state.expanded || '+ ') + (state.mode || 'Short list') +
                (state.button ? ' / ' + state.button : ''));
            JSON.stringify(kinds);
            </js>
        """.trimIndent(),
        jsLib = """
            function changeKind(key, value) {
                var source = this.source, java = this.java;
                var state = JSON.parse(String(source.getLoginHeader() || '{}'));
                state[key] = String(value);
                if (state.forceGc) {
                    function marker() { return new Packages.java.lang.ref.WeakReference(new Packages.java.lang.Object()); }
                    var weak = marker();
                    for (var attempt = 0; weak.get() != null && attempt < 20; attempt++) {
                        Packages.java.lang.System.gc();
                        Packages.java.lang.System.runFinalization();
                        Packages.java.lang.Thread.sleep(10);
                    }
                    if (weak.get() != null) throw new Error('GC did not collect the control probe');
                    state.gcObserved = true;
                }
                source.putLoginHeader(JSON.stringify(state));
                source.refreshExplore();
                java.refreshExplore();
            }
        """.trimIndent(),
        ruleExplore = ExploreRule(bookList = "tag.a", name = "text", bookUrl = "href"),
    )
    @get:Rule
    val composeRule = createAndroidComposeRule<AppActivity>()

    @Before
    fun setUp() {
        appDb.bookSourceDao.insert(source)
        composeRule.onNodeWithText(context.getString(R.string.discovery)).performClick()
        awaitText(source.bookSourceName)
    }

    @After
    fun tearDown() {
        runBlocking { source.clearExploreKindsCache() }
        // The fixture writes only this unique header, never a Cookie. Avoid touching
        // the shared WebView cookie store when cleaning up unrelated test state.
        CacheManager.delete("loginHeader_${source.getKey()}")
        appDb.bookSourceDao.delete(source.bookSourceUrl)
    }

    @Test
    fun toggleAndSelectActionsRefreshTheirComposeState() {
        composeRule.onNodeWithText(source.bookSourceName).performClick()
        awaitText("+ More categories")
        awaitText("Category mode: Short list")
        composeRule.onNodeWithText("Refresh controls").performClick()
        awaitText("Rendered + Short list / pressed")
        composeRule.onNodeWithText("Search categories").performTextInput("magic")
        composeRule.onNodeWithText("magic").assertIsDisplayed()
        composeRule.onNodeWithText("+ More categories").performClick()
        awaitText("- More categories")
        awaitText("Rendered - Short list / pressed")

        composeRule.onNodeWithText("Category mode: Short list").performClick()
        composeRule.onNodeWithText("Long list").performClick()
        awaitText("Category mode: Long list")
        awaitText("Rendered - Long list / pressed")
    }

    @Test
    fun callbackRemainsLiveWhileTheSourceScriptForcesGarbageCollection() {
        source.putLoginHeader("""{"forceGc":true}""")
        composeRule.onNodeWithText(source.bookSourceName).performClick()
        awaitText("Category mode: Short list")
        composeRule.onNodeWithText("Category mode: Short list").performClick()
        composeRule.onNodeWithText("Long list").performClick()
        awaitText("Rendered + Long list")
        assertTrue(source.getLoginHeader().orEmpty().contains("\"gcObserved\":true"))
    }

    @Test
    fun dynamicControlsRemainReachableByScrollingInShortWindows() {
        fun reveal(text: String) {
            composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
            composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText(source.bookSourceName).performClick()
        awaitText("+ More categories")
        reveal("Refresh controls")
        composeRule.onNodeWithText("Refresh controls").performClick()
        reveal("Rendered + Short list / pressed")
        reveal("+ More categories")
        composeRule.onNodeWithText("+ More categories").performClick()
        reveal("Rendered - Short list / pressed")
    }

    @Test
    fun rootSearchInputAcceptsTextAndRemainsVisibleWithIme() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.search)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.screen_find)).performTextInput("reader")
        awaitImeVisible()
        composeRule.onNodeWithText("reader").assertIsDisplayed()
    }

    private fun awaitText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching { composeRule.onNodeWithText(text).assertIsDisplayed() }.isSuccess) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Explore screen did not show '$text'")
    }

    private fun awaitImeVisible() = awaitImeState(visible = true)
    private fun awaitImeState(visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var actual = false
            instrumentation.runOnMainSync {
                actual = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            if (actual == visible) return
            SystemClock.sleep(50)
        }
        throw AssertionError("IME visibility did not become $visible")
    }
}
