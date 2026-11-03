package io.legado.app.ui.book.read

import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.model.ContentSearchResult
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SearchMenuAccessibilityTest {
    @Test fun bottomActionsFitLargeTextAcrossPhoneAndWideWindows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    setLocale(Locale.ENGLISH)
                    fontScale = 2f
                }
                val themed = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme_Light)
                val menu = SearchMenu(themed)
                val density = themed.resources.displayMetrics.density
                for (widthDp in listOf(320, 600, 900)) {
                    val width = (widthDp * density + .5f).toInt()
                    val height = (480 * density + .5f).toInt()
                    menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    menu.layout(0, 0, width, height)
                    val actions = listOf(R.id.ll_search_results, R.id.ll_main_menu, R.id.ll_search_exit)
                        .map { menu.findViewById<View>(it) }
                    assertEquals(width, actions.sumOf { it.width })
                    assertTrue(actions.all { it.width >= (48 * density).toInt() })
                    listOf(R.id.tv_search_results, R.id.tv_main_menu, R.id.tv_search_exit).forEach { id ->
                        val label = menu.findViewById<TextView>(id)
                        val layout = label.layout
                        assertTrue("Label is truncated at ${widthDp}dp: ${label.text}",
                            layout != null && layout.getLineEnd(layout.lineCount - 1) == label.text.length &&
                                (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 && layout.getLineWidth(it) <= label.width })
                    }
                }
            }
        }
    }

    @Test fun emptyResultsDisableNavigationAndControlsUseLocalizedLabels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply { setLocale(Locale.ENGLISH) }
                val english = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme_Light)
                val menu = SearchMenu(english)
                val navigation = listOf(R.id.fabLeft, R.id.fabRight, R.id.iv_search_content_up, R.id.iv_search_content_down)
                    .map { menu.findViewById<View>(it) }
                navigation.forEach {
                    assertFalse(it.isEnabled)
                    it.performClick() // No callback or indexing of an empty result list.
                }
                assertEquals("Previous search result", navigation[0].contentDescription)
                assertEquals("Next search result", navigation[1].contentDescription)
                assertEquals("Previous search result", navigation[2].contentDescription)
                assertEquals("Next search result", navigation[3].contentDescription)
                val minPx = (48 * english.resources.displayMetrics.density + .5f).toInt()
                assertEquals(minPx, navigation[2].layoutParams.width)
                assertEquals(minPx, menu.findViewById<View>(R.id.ll_search_base_info).layoutParams.height)
                menu.upSearchResultList(listOf(ContentSearchResult(query = "sample")))
                assertTrue(navigation.all { it.isEnabled })
                menu.upSearchResultList(emptyList())
                navigation.forEach { assertFalse(it.isEnabled); it.performClick() }
                assertEquals(english.getString(R.string.search_menu_results), menu.findViewById<View>(R.id.ll_search_results).contentDescription)
                assertEquals(english.getString(R.string.exit), menu.findViewById<View>(R.id.ll_search_exit).contentDescription)
                assertEquals("Search results: 2 / Current chapter: Chapter", english.getString(R.string.search_menu_chapter_summary, 2, "Chapter"))
                val chinese = ContextThemeWrapper(
                    activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }),
                    R.style.AppTheme_Light,
                )
                val chineseMenu = SearchMenu(chinese)
                assertEquals("上个搜索结果", chineseMenu.findViewById<View>(R.id.iv_search_content_up).contentDescription)
                assertEquals("搜索结果：2 / 当前章节：章节", chinese.getString(R.string.search_menu_chapter_summary, 2, "章节"))
            }
        }
    }
}
