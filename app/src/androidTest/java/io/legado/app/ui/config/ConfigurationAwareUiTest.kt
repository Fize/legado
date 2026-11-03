package io.legado.app.ui.config

import android.content.Intent
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.PopupMenu
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.IntentData
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.association.*
import io.legado.app.ui.book.searchContent.SearchContentScreen
import io.legado.app.ui.navigation.*
import io.legado.app.ui.replace.edit.ReplaceRuleEditScreen
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.iconItemOnLongClick
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConfigurationAwareUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val locale=mutableStateOf(Locale.ENGLISH)
    private val prefs=context.defaultSharedPreferences
    private val previous=listOf(PreferKey.autoBackup,PreferKey.autoBackupWebDav,PreferKey.autoBackupIntervalDays,PreferKey.importReplaceSource,PreferKey.importRememberGroup).associateWith { prefs.all[it] }
    private val privacy=LocalConfig.all["privacyPolicyOk"]
    @Before fun setup() {
        prefs.edit().putBoolean(PreferKey.autoBackup,false).putBoolean(PreferKey.importReplaceSource,false).putBoolean(PreferKey.importRememberGroup,false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk",true).commit()
    }
    @After fun restore() {
        prefs.edit().apply { previous.forEach { (key,value) -> if(value==null) remove(key) else when(value) { is Boolean -> putBoolean(key,value); is Int -> putInt(key,value); else -> error("Unexpected preference type") } } }.commit()
        LocalConfig.edit().apply { if(privacy==null) remove("privacyPolicyOk") else putBoolean("privacyPolicyOk",privacy as Boolean) }.commit()
    }
    private fun text(id:Int,selected:Locale=locale.value,vararg args:Any):String {
        val config=Configuration(context.resources.configuration).apply { setLocale(selected) }
        return context.createConfigurationContext(config).getString(id,*args)
    }
    private fun show(content:@Composable (AppActivity)->Unit,body:(ActivityScenario<AppActivity>)->Unit) {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val selected=locale.value
                val wrapped=remember(selected) { ContextThemeWrapper(activity,androidx.appcompat.R.style.Theme_AppCompat).apply {
                    applyOverrideConfiguration(Configuration(activity.resources.configuration).apply { setLocale(selected) })
                } }
                CompositionLocalProvider(LocalContext provides wrapped,LocalConfiguration provides wrapped.resources.configuration) {
                    MaterialTheme { content(activity) }
                }
            } }
            compose.waitForIdle()
            body(scenario)
        }
    }
    private fun chinese() { instrumentation.runOnMainSync { locale.value=Locale.SIMPLIFIED_CHINESE };compose.waitForIdle() }
    @Test fun rememberedThemeSummaryUsesTheCurrentLocaleWithoutRefreshingSettings() {
        val scale=AppContextWrapper.getFontScale(context)
        show({ ThemeSettingsContent(0,{}) }) {
            val english=text(R.string.font_scale_summary,Locale.ENGLISH,scale)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(english))
            compose.onNodeWithText(english).performScrollTo().assertIsDisplayed()
            chinese()
            compose.onNodeWithText(text(R.string.font_scale_summary,Locale.SIMPLIFIED_CHINESE,scale)).assertIsDisplayed()
            compose.onNodeWithText(english).assertDoesNotExist()
        }
    }
    @Test fun openBackupOptionsTranslateWithoutChangingThePendingSelection() {
        var model:BackupSettingsViewModel?=null
        show({ activity ->
            val vm=remember(activity) { ViewModelProvider(activity)[BackupSettingsViewModel::class.java] };model=vm
            BackupSettingsScreen({}, {}, vm)
        }) {
            val initial=model!!.contentOptions().first();val title=text(initial.titleId)
            val contentTitle=text(R.string.backup_content)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(contentTitle))
            compose.onNodeWithText(contentTitle).performScrollTo().performClick()
            compose.onNodeWithText(title).assertHeightIsAtLeast(48.dp).performClick()
            val expected=if(initial.enabled) ToggleableState.Off else ToggleableState.On
            compose.onNodeWithText(title).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,expected))
            chinese()
            compose.waitUntil(5_000) { compose.onNodeWithText(text(initial.titleId)).isDisplayed() }
            compose.onNodeWithText(text(initial.titleId)).assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState,expected))
            compose.onNodeWithText(title).assertDoesNotExist()
            assertEquals(initial.enabled,model.contentOptions().first().enabled)
            compose.onNodeWithText(text(R.string.cancel)).performClick()
        }
    }
    @Test fun backupSummaryObservesDestinationAndIntervalChangesInTheOpenScreen() {
        show({ BackupSettingsScreen({}, {}) }) {
            instrumentation.runOnMainSync {
                prefs.edit().putBoolean(PreferKey.autoBackup,true)
                    .putBoolean(PreferKey.autoBackupWebDav,false)
                    .putInt(PreferKey.autoBackupIntervalDays,3).commit()
            }
            val initial=text(R.string.auto_backup_destination_summary,Locale.ENGLISH,text(R.string.backup_local_only),3)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(initial))
            compose.onNodeWithText(initial).performScrollTo().assertIsDisplayed()
            instrumentation.runOnMainSync {
                prefs.edit().putBoolean(PreferKey.autoBackupWebDav,true)
                    .putInt(PreferKey.autoBackupIntervalDays,7).commit()
            }
            compose.onNodeWithText(text(R.string.auto_backup_destination_summary,Locale.ENGLISH,text(R.string.backup_local_webdav),7)).assertIsDisplayed()
            chinese()
            compose.onNodeWithText(text(R.string.auto_backup_destination_summary,Locale.SIMPLIFIED_CHINESE,text(R.string.backup_local_webdav),7)).assertIsDisplayed()
        }
    }
    @Test fun sourcePresetAndMetadataFilteringSurviveLocaleChangesAndSameUrlEdits() {
        val id=UUID.randomUUID().toString()
        val first=BookSource("https://locale.invalid/$id/one","Alpha literal",enabled=true)
        val second=BookSource("https://locale.invalid/$id/two","Other literal",enabled=false)
        val route=BookSourceImportRoute(GSON.toJson(listOf(first,second)))
        var model:ImportBookSourceViewModel?=null
        show({ activity ->
            val vm=remember(activity) { ViewModelProvider(activity)[ImportBookSourceViewModel::class.java] };model=vm
            BookSourceImportScreen(route,{}, {},vm)
        }) {
            compose.waitUntil(10_000) { model!!.uiState.value.successCount==2 }
            compose.onNodeWithTag("sourceImportSearch").performTextReplacement(text(R.string.enabled))
            compose.waitUntil(5_000) { model!!.uiState.value.searchPreset==SourceImportPreset.Enabled }
            compose.onNodeWithTag("sourceImportRow:0").assertIsDisplayed()
            compose.onNodeWithTag("sourceImportRow:1").assertDoesNotExist()
            chinese()
            compose.onNodeWithTag("sourceImportSearch").assertTextEquals(text(R.string.enabled))
            compose.onNodeWithText("Alpha literal").assertIsDisplayed()
            instrumentation.runOnMainSync { model!!.updateSource(0,first.copy(enabled=false)) }
            compose.onNodeWithTag("sourceImportRow:0").assertDoesNotExist()
            instrumentation.runOnMainSync { model!!.searchQuery="Alpha" }
            compose.onNodeWithTag("sourceImportRow:0").assertIsDisplayed()
            instrumentation.runOnMainSync { model!!.updateSource(0,first.copy(bookSourceName="Renamed literal",enabled=false)) }
            compose.onNodeWithTag("sourceImportRow:0").assertDoesNotExist()
            instrumentation.runOnMainSync { model!!.searchQuery="Renamed" }
            compose.onNodeWithText("Renamed literal").assertIsDisplayed()
        }
    }
    @Test fun previewErrorsTranslateWhileTheRuleAndSampleRemainUnchanged() {
        val rule=ReplaceRule(name="Literal rule",pattern="[",isRegex=true)
        appDb.replaceRuleDao.insert(rule)
        try {
            show({ ReplaceRuleEditScreen(ruleId=rule.id,onBack={},onSaved={}) }) {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Literal rule").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasSetTextAction() and hasText(text(R.string.replace_preview_input))).performScrollTo().performTextReplacement("Literal sample")
                val english=text(R.string.replace_preview_error)
                compose.waitUntil(10_000) { compose.onAllNodesWithText(english).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(english).performScrollTo().assertIsDisplayed()
                chinese()
                compose.onNodeWithText(text(R.string.replace_preview_error)).assertIsDisplayed()
                assertEquals("[",appDb.replaceRuleDao.findById(rule.id)!!.pattern)
                assertNull(appDb.replaceRuleDao.findById(rule.id)!!.previewText)
                compose.onNode(hasSetTextAction() and hasText(text(R.string.replace_preview_input))).assertTextContains("Literal sample")
            }
        } finally {appDb.replaceRuleDao.delete(rule)}
    }
    @Test fun searchScreenResolvesTheActivityThroughAWrappedContext() {
        io.legado.app.ui.book.searchContent.ContentSearchFixture().use { fixture ->
            val result=AtomicReference<Intent?>()
            show({ SearchContentScreen(SearchContentRoute(fixture.book.bookUrl,"needle"),{},result::set) }) {
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("content_search_hit_0").assertTextContains("needle",substring=true).performClick()
                compose.waitUntil(5_000) { result.get()!=null }
                assertEquals(0,result.get()!!.getIntExtra("contentSearchOrdinal",-1))
            }
        }
    }

    @Test fun legacyMenuLongClickUsesTheSuppliedThemedContextAndPublicMenuApi() {
        show({}) { scenario -> scenario.onActivity { activity ->
            val themed=ContextThemeWrapper(activity,androidx.appcompat.R.style.ThemeOverlay_AppCompat_Dark)
            val popup=PopupMenu(activity,View(activity));val item=popup.menu.add(0,12345,0,"Literal action")
            var clicked=0;var longClicked=0
            item.setOnMenuItemClickListener { clicked++;true }
            popup.menu.iconItemOnLongClick(themed,item.itemId) { longClicked++ }
            assertSame(themed,item.actionView!!.context);assertEquals("Literal action",item.actionView!!.contentDescription)
            item.actionView!!.performClick();item.actionView!!.performLongClick()
            assertEquals(1,clicked);assertEquals(1,longClicked)
        } }
    }
}
