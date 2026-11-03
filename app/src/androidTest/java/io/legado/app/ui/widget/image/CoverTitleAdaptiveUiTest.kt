package io.legado.app.ui.widget.image

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import io.legado.app.ui.compose.component.CoverImageStatus
import org.junit.Rule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.AppActivity
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.navigation.SettingsRoute
import io.legado.app.ui.navigation.SettingsSection
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoverTitleAdaptiveUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val saved = HashMap(preferences.all)
    private val savedUseDefault = AppConfig.useDefaultCover
    private val savedReaderFont = ReadBookConfig.textFont
    private val savedReaderTitleFont = ReadBookConfig.titleFont
    private var scenario: ActivityScenario<AppActivity>? = null

    @Before
    fun setUp() {
        preferences.edit()
            .putBoolean(PreferKey.useDefaultCover, false)
            .putBoolean(PreferKey.coverShowName, true)
            .putBoolean(PreferKey.coverShowNameN, true)
            .putBoolean(PreferKey.coverShowAuthor, true)
            .putBoolean(PreferKey.coverShowAuthorN, true)
            .putBoolean(PreferKey.coverHorizontal, false)
            .putBoolean(PreferKey.coverTitleAdaptive, true)
            .putBoolean(PreferKey.coverKeepPunctuation, true)
            .putBoolean(PreferKey.coverCustomFontSize, false)
            .remove(PreferKey.coverFont)
            .putInt(PreferKey.coverTitleLargeSize, 100)
            .putInt(PreferKey.coverTitleSmallSize, 100)
            .putInt(PreferKey.coverAuthorLargeSize, 100)
            .putInt(PreferKey.coverAuthorSmallSize, 100)
            .commit()
        AppConfig.useDefaultCover = false
        BookCover.upDefaultCover()
        launch(SettingsSection.CoverFont)
    }

    @After
    fun tearDown() {
        scenario?.close()
        preferences.edit().clear().apply { saved.forEach { (key, value) -> putValue(key, value) } }.commit()
        AppConfig.useDefaultCover = savedUseDefault
        ReadBookConfig.textFont = savedReaderFont
        ReadBookConfig.titleFont = savedReaderTitleFont
        BookCover.upDefaultCover()
    }

    @Test
    fun adaptiveAndCustomSizeSettingsChangeTheRenderedComposePreviews() {
        var adaptiveLabel = ""
        var customLabel = ""
        checkNotNull(scenario).onActivity { adaptiveLabel = it.getString(R.string.cover_title_adaptive); customLabel = it.getString(R.string.cover_custom_font_size) }
        compose.onNodeWithText(adaptiveLabel).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(customLabel))
        compose.onNodeWithText(customLabel).assertIsDisplayed()

        for (horizontal in listOf(false, true)) {
            preferences.edit().putBoolean(PreferKey.coverHorizontal, horizontal)
                .putBoolean(PreferKey.coverTitleAdaptive, true)
                .putBoolean(PreferKey.coverCustomFontSize, false).commit()
            BookCover.upDefaultCover()
            val adaptive = renderedPreviews()

            preferences.edit().putBoolean(PreferKey.coverTitleAdaptive, false).commit()
            BookCover.upDefaultCover()
            val fixed = renderedPreviews()
            assertEquals(2, adaptive.size)
            if (horizontal) assertArrayEquals("A short horizontal title that already fits keeps its size", adaptive[0], fixed[0])
            assertFalse("An overflowing title changes its layout; horizontal=$horizontal", adaptive[1].contentEquals(fixed[1]))

            preferences.edit().putBoolean(PreferKey.coverTitleAdaptive, true)
                .putBoolean(PreferKey.coverCustomFontSize, true)
                .putInt(PreferKey.coverTitleLargeSize, 145)
                .putInt(PreferKey.coverTitleSmallSize, 150)
                .putInt(PreferKey.coverAuthorLargeSize, 130)
                .putInt(PreferKey.coverAuthorSmallSize, 150).commit()
            BookCover.upDefaultCover()
            val custom = renderedPreviews()
            preferences.edit().putBoolean(PreferKey.coverCustomFontSize, false).commit()
            BookCover.upDefaultCover()
            val restored = renderedPreviews()
            custom.zip(restored).forEachIndexed { index, (before, after) ->
                assertFalse("custom size pixels change in preview $index; horizontal=$horizontal",
                    before.contentEquals(after))
            }
        }
    }

    @Test
    fun composeFontSizeControlsPersistThroughActivityRecreation() {
        preferences.edit().putBoolean(PreferKey.coverCustomFontSize, true)
            .putInt(PreferKey.coverTitleLargeSize, 120)
            .putInt(PreferKey.coverTitleSmallSize, 130)
            .putInt(PreferKey.coverAuthorLargeSize, 140)
            .putInt(PreferKey.coverAuthorSmallSize, 150).commit()
        scenario?.recreate()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("120%"))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("150%"))
        scenario?.close()
        launch(SettingsSection.CoverFont)
        assertEquals(120, preferences.getInt(PreferKey.coverTitleLargeSize, 0))
        assertEquals(130, preferences.getInt(PreferKey.coverTitleSmallSize, 0))
        assertEquals(140, preferences.getInt(PreferKey.coverAuthorLargeSize, 0))
        assertEquals(150, preferences.getInt(PreferKey.coverAuthorSmallSize, 0))
    }

    @Test
    fun recordCoverPreferencesCopySelectedImagesThroughActivityResult() {
        scenario?.close()
        preferences.edit().remove(PreferKey.readRecordCover).remove(PreferKey.readRecordCoverDark).commit()
        val image = File.createTempFile("record-cover-selection", ".png", context.cacheDir)
        Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(49, 123, 191))
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val existing = File(context.externalFiles, "covers").listFiles()?.map { it.name }.orEmpty().toSet()
        val copied = mutableSetOf<File>()
        var selections = 0
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != HandleFileActivity::class.java.name) return null
                assertEquals(HandleFileContract.IMAGE, intent.getIntExtra("mode", -1))
                selections++
                val result = Intent().setData(FileProvider.getUriForFile(
                    context, "${context.packageName}.fileProvider", image))
                    .putExtra("value", intent.getStringExtra("value"))
                val restoredResult = HandleFileContract().parseResult(Activity.RESULT_OK, result)
                assertEquals(0, restoredResult.requestCode)
                assertEquals(result.data, restoredResult.uri)
                assertEquals(intent.getStringExtra("value"), restoredResult.value)
                assertTrue(restoredResult.value in listOf(PreferKey.readRecordCover, PreferKey.readRecordCoverDark))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, result)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launch(SettingsSection.Cover)
            for ((key, label) in listOf(PreferKey.readRecordCover to R.string.read_record_cover_day,
                PreferKey.readRecordCoverDark to R.string.read_record_cover_night)) {
                clickLabel(label)
                waitUntil { preferences.getString(key, null) != null }
                val file = File(checkNotNull(preferences.getString(key, null)))
                if (file.name !in existing) copied.add(file)
                assertEquals(File(context.externalFiles, "covers"), file.parentFile)
                assertArrayEquals(image.readBytes(), file.readBytes())
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            }
            assertEquals(2, selections)
        } finally {
            instrumentation.removeMonitor(monitor)
            copied.forEach { it.delete() }
            image.delete()
        }
    }

    private fun launch(section: SettingsSection) {
        scenario = ActivityScenario.launch(
            AppActivity.startIntent(context, SettingsRoute(section))
        )
    }

    private fun renderedPreviews(): List<IntArray> {
        var labels = emptyList<String>()
        checkNotNull(scenario).onActivity { labels = listOf(it.getString(R.string.cover_preview_short), it.getString(R.string.cover_preview_long)) }
        return labels.map { label ->
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(label))
            compose.waitForIdle()
            compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(CoverImageStatus, "default"))
            val bitmap = compose.onNodeWithContentDescription(label).captureToImage().asAndroidBitmap()
            IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        }
    }

    private fun clickLabel(id: Int) {
        var label = ""
        checkNotNull(scenario).onActivity { label = it.getString(id) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
    }

    private fun waitUntil(predicate: () -> Boolean) = compose.waitUntil(10_000, predicate)

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
