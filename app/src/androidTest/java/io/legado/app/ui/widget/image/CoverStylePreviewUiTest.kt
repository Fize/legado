package io.legado.app.ui.widget.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import io.legado.app.ui.compose.component.CoverImageStatus
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SettingsRoute
import io.legado.app.ui.navigation.SettingsSection
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoverStylePreviewUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val saved = HashMap(preferences.all)
    private val savedNight = AppConfig.isNightTheme
    private val files = mutableListOf<File>()
    private var previewCapture = 0
    @After
    fun tearDown() {
        preferences.edit().clear().apply {
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
        instrumentation.runOnMainSync {
            AppConfig.isNightTheme = savedNight
            ThemeConfig.applyDayNight(context)
        }
        files.forEach(File::delete)
    }

    private fun launch(): ActivityScenario<AppActivity> {
        preferences.edit().putBoolean(PreferKey.coverShowName, true)
            .putBoolean(PreferKey.coverShowAuthor, true)
            .putBoolean(PreferKey.coverShowNameN, true)
            .putBoolean(PreferKey.coverShowAuthorN, true).commit()
        instrumentation.runOnMainSync { BookCover.upDefaultCover() }
        return ActivityScenario.launch(AppActivity.startIntent(context, SettingsRoute(SettingsSection.CoverFont)))
    }

    @Test
    fun settingsUpdateRealPreviewsAndSurviveRecreation() {
        preferences.edit().putBoolean(PreferKey.coverHorizontal, false)
            .putBoolean(PreferKey.coverTitleAdaptive, true)
            .putBoolean(PreferKey.coverKeepPunctuation, false)
            .putBoolean(PreferKey.coverCustomFontSize, false).putString(PreferKey.coverFont, "").commit()
        launch().use { settings ->
            val before = previews(settings)
            for ((_, label) in listOf(PreferKey.coverHorizontal to R.string.cover_horizontal,
                PreferKey.coverTitleAdaptive to R.string.cover_title_adaptive,
                PreferKey.coverKeepPunctuation to R.string.cover_keep_punctuation,
                PreferKey.coverCustomFontSize to R.string.cover_custom_font_size)) {
                clickLabel(settings, label)
            }
            val after = previews(settings)
            assertFalse("real cover pixels update without leaving settings", before[1].contentEquals(after[1]))
            assertTrue(BookCover.drawBookNameHorizontal)
            assertFalse(BookCover.adaptiveTitleSize)
            assertTrue(BookCover.keepPunctuation)
            assertNotNull(BookCover.fontSizes)
            screenshot("cover-style-settings-live")
            settings.recreate()
            val recreated = previews(settings)
            after.zip(recreated).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
            screenshot("cover-style-settings-recreated")
        }
    }

    @Test
    fun selectedFontIsIndependentAndDayNightPreviewsUseActualCustomBackgrounds() {
        val readerFont = ReadBookConfig.textFont
        val readerTitleFont = ReadBookConfig.titleFont
        val systemFont = AppConfig.systemTypefaces
        val sourceFont = File("/system/fonts").listFiles().orEmpty().first {
            it.extension == "ttf" && it.name.contains("Serif")
        }
        val font = File(context.externalFiles, "font/cover-preview-${UUID.randomUUID()}.ttf")
        font.parentFile!!.mkdirs()
        sourceFont.copyTo(font)
        files.add(font)
        preferences.edit().remove(PreferKey.fontFolder).putString(PreferKey.coverFont, "").commit()
        for ((night, color) in listOf(false to Color.rgb(241, 219, 184), true to Color.rgb(39, 71, 95))) {
            val image = File(context.cacheDir, "cover-preview-${UUID.randomUUID()}.png")
            Bitmap.createBitmap(90, 120, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
                image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            files.add(image)
            preferences.edit().putString(if (night) PreferKey.defaultCoverDark else PreferKey.defaultCover,
                image.path).commit()
            instrumentation.runOnMainSync {
                AppConfig.isNightTheme = night
                ThemeConfig.applyDayNight(context)
            }
            launch().use { settings ->
                val background = previews(settings)
                background.forEach { assertEquals("current custom background is rendered", color, it[kotlin.math.sqrt(it.size * .75).toInt().let { width -> width * 8 + width / 2 }]) }
                clickLabel(settings, R.string.cover_font_select)
                compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText(font.name))
                compose.onNodeWithText(font.name).performClick()
                waitUntil { preferences.getString(PreferKey.coverFont, "") == font.path }
                assertNotNull(BookCover.fontTypeface)
                assertEquals(readerFont, ReadBookConfig.textFont)
                assertEquals(readerTitleFont, ReadBookConfig.titleFont)
                assertEquals(systemFont, AppConfig.systemTypefaces)
                previews(settings)
                screenshot("cover-style-font-${if (night) "night" else "day"}")
                settings.recreate()
                previews(settings)
                assertEquals(font.path, preferences.getString(PreferKey.coverFont, ""))
                clickLabel(settings, R.string.cover_font_select)
                var defaultLabel = ""
                settings.onActivity { defaultLabel = it.getString(R.string.default_font) }
                compose.onNodeWithText(defaultLabel).performClick()
                assertEquals("", preferences.getString(PreferKey.coverFont, ""))
                assertNull(BookCover.fontTypeface)
                assertEquals(systemFont, AppConfig.systemTypefaces)
                assertEquals(readerFont, ReadBookConfig.textFont)
                assertEquals(readerTitleFont, ReadBookConfig.titleFont)
            }
        }
    }

    private fun clickLabel(settings: ActivityScenario<AppActivity>, id: Int) {
        var label = ""
        settings.onActivity { label = it.getString(id) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
    }

    private fun previews(settings: ActivityScenario<AppActivity>): List<IntArray> {
        var labels = emptyList<String>()
        settings.onActivity { labels = listOf(it.getString(R.string.cover_preview_short), it.getString(R.string.cover_preview_long)) }
        return labels.map { label ->
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(label))
            // The preview is the last item. Use the same absolute screen origin before
            // comparing pixels: API37 dithers the background at screen coordinates.
            compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(CoverImageStatus, "default"))
            val bitmap = compose.onNodeWithContentDescription(label).captureToImage().asAndroidBitmap()
            val capture = ++previewCapture
            File(context.cacheDir, "cover-preview-$capture.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            File(context.cacheDir, "cover-preview-$capture.txt").writeText("${bitmap.width}x${bitmap.height} $bounds")
            IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        }
    }

    private fun waitUntil(predicate: () -> Boolean) = compose.waitUntil(10_000, predicate)

    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(null), "ui-regression/$name.png")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().apply {
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }

}
