package io.legado.app.ui.main.my

import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.writePreferenceSnapshot
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SettingsRoute
import io.legado.app.ui.navigation.SettingsSection
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MyPageCustomizationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = preferences.all
    private val savedLocalConfig = LocalConfig.all
    private var scenario: ActivityScenario<AppActivity>? = null

    @Before
    fun setUp() {
        preferences.edit().putStringSet(PreferKey.myMoreItems, setOf(
            MyAction.TextTocRuleManage.key,
            MyAction.ReplaceRuleManage.key,
            MyAction.DictionaryRuleManage.key,
        )).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", appInfo.versionCode).putString("password", "").commit()
        launchMore()
    }

    @After
    fun cleanUp() {
        scenario?.close()
        preferences.edit().clear().apply { savedPreferences.forEach { (key, value) -> putValue(key, value) } }.commit()
        LocalConfig.edit().clear().apply { savedLocalConfig.forEach { (key, value) -> putValue(key, value) } }.commit()
    }

    @Test
    fun typedMoreDestinationShowsOnlyCustomizedEntries() {
        assertPresent(R.string.reader_menu_more)
        assertPresent(R.string.txt_toc_rule)
        assertPresent(R.string.replace_purify)
        assertPresent(R.string.dict_rule)
        assertMissing(R.string.check_update)
        assertMissing(R.string.book_source_manage)
    }

    @Test
    fun restoredCustomizationIsReflectedByMoreDestination() {
        val directory = File(context.cacheDir, "my-page-backup-${UUID.randomUUID()}").apply { mkdirs() }
        val selected = setOf(MyAction.TextTocRuleManage.key)
        try {
            writePreferenceSnapshot(context, directory.absolutePath, "config") {
                putStringSet(PreferKey.myMoreItems, selected)
            }
            runBlocking(Dispatchers.IO) { Restore.restoreLocked(directory.absolutePath) }
            assertEquals(selected, preferences.getStringSet(PreferKey.myMoreItems, emptySet()))
            scenario?.close()
            launchMore()
            assertPresent(R.string.txt_toc_rule)
            assertMissing(R.string.replace_purify)
            assertMissing(R.string.dict_rule)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun launchMore() {
        scenario = ActivityScenario.launch(
            AppActivity.startIntent(context, SettingsRoute(SettingsSection.Root))
        )
        composeRule.waitForIdle()
    }

    private fun assertPresent(stringRes: Int) =
        composeRule.onNodeWithText(uiString(stringRes)).assertExists()

    private fun assertMissing(stringRes: Int) =
        composeRule.onNodeWithText(uiString(stringRes)).assertDoesNotExist()

    private fun uiString(stringRes: Int): String {
        var value = ""
        scenario?.onActivity { value = it.getString(stringRes) }
        return value
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
        }
    }
}
