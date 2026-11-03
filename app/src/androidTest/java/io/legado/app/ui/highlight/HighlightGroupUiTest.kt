package io.legado.app.ui.highlight

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.HighlightRuleRoute
import io.legado.app.utils.GSON
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HighlightGroupUiTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dao = appDb.highlightRuleDao
    private val namedUngrouped = context.getString(R.string.no_group)
    private var savedRules = emptyList<HighlightRule>()
    private var scenario: ActivityScenario<AppActivity>? = null
    private val artifacts=HighlightManagerArtifacts()
    private val fixtures = listOf(
        HighlightRule(name = "Alice", pattern = "Alice", group = "Characters", order = 0).apply {
            applyStyle(HighlightStyle(fill = 0xFF209050.toInt(),
                fillShape = HighlightStyle.FillShape.PILL, pillPaddingScale = 1.25f))
        },
        HighlightRule(name = "Bob", pattern = "Bob", group = "Characters", order = 1),
        HighlightRule(name = "Quote", pattern = "Quote", group = "Quotes", order = 2),
        HighlightRule(name = "Named group", pattern = "Named", group = namedUngrouped, order = 3),
        HighlightRule(name = "Loose rule", pattern = "Loose", order = 4),
    )

    @Before fun setUp() {
        savedRules = dao.all
        dao.deleteAll()
        dao.insert(*fixtures.toTypedArray())
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, HighlightRuleRoute))
        awaitRules(dao.all)
    }

    @After fun cleanUp() {
        scenario?.close(); artifacts.cleanup()
        val fixtureUuids=fixtures.map { it.uuid }.toSet()
        dao.all.filter { it.uuid in fixtureUuids }.forEach { rule ->
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM highlight_rule_drafts WHERE ruleId = ?",arrayOf(rule.id))
        }
        dao.deleteAll()
        if (savedRules.isNotEmpty()) dao.insert(*savedRules.toTypedArray())
    }

    @Test fun fontSizeAndNegativeSpacingPersistAndResetThroughTheActualComposeEditor() {
        fun openStyle() {
            composeRule.onNodeWithTag("highlightRuleEdit:${fixtures.first().uuid}").performClick()
            await { composeRule.onAllNodesWithTag("highlightRuleEditStyle").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("highlightRuleEditSave").assertIsEnabled()
            composeRule.onNodeWithTag("highlightRuleEditStyle").performScrollTo().performClick()
        }
        fun progress(tag: String,value: Float) {
            composeRule.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
            composeRule.waitForIdle()
            assertEquals(value,composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current,0.0001f)
        }
        fun save() {
            composeRule.onNodeWithTag("highlightRuleEditSave").performClick()
            await { composeRule.onAllNodesWithTag("highlight_rule_editor").fetchSemanticsNodes().isEmpty() }
        }
        openStyle()
        composeRule.onNodeWithTag("annotation_font_size_toggle").performScrollTo().performClick()
        progress("annotation_font_size",42f)
        composeRule.onNodeWithTag("annotation_letter_spacing_toggle").performScrollTo().performClick()
        progress("annotation_letter_spacing",-0.2f)
        screenshot("highlight-font-metrics-settings")
        save()
        await { dao.all.first().styleObj().let { it.fontSize==42f && it.letterSpacing==-0.2f } }
        scenario!!.recreate(); awaitRules(dao.all); openStyle()
        composeRule.onNodeWithTag("annotation_font_size_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("annotation_letter_spacing_toggle").performScrollTo().performClick()
        save()
        await { dao.all.first().styleObj().let { it.fontSize==null && it.letterSpacing==null } }
    }

    @Test fun pillMarginEditsPersistAndResetThroughTheActualComposeEditor() {
        fun openStyle() {
            composeRule.onNodeWithTag("highlightRuleEdit:${fixtures.first().uuid}").performClick()
            await { composeRule.onAllNodesWithTag("highlightRuleEditStyle").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("highlightRuleEditStyle").performScrollTo().performClick()
        }
        fun saveStyle() {
            composeRule.onNodeWithTag("highlightRuleEditSave").performClick()
            await { composeRule.onAllNodesWithTag("highlight_rule_editor").fetchSemanticsNodes().isEmpty() }
        }
        openStyle()
        composeRule.onNodeWithTag("annotation_pill_padding").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(150f)) }
        composeRule.onNodeWithTag("annotation_fill").performScrollTo().performClick()
        composeRule.onNodeWithTag("annotation_fill").performScrollTo().performClick()
        composeRule.onNodeWithTag("annotation_pill_padding").performScrollTo()
        screenshot("highlight-pill-margin-settings")
        saveStyle(); await { dao.all.first().styleObj().resolvedPillPaddingScale==1.5f }
        scenario!!.recreate(); awaitRules(dao.all); openStyle()
        composeRule.onNodeWithTag("annotation_pill_padding_reset").performScrollTo().performClick()
        saveStyle(); await { dao.all.first().styleObj().pillPaddingScale==null }
    }

    @Test fun filterRenameMoveAndDeleteUseRealDialogsAndPreserveOtherRules() {
        composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        instrumentation.waitForIdleSync()
        val menuBitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "highlight-more-menu.png")
                .outputStream().use { assertTrue(menuBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { menuBitmap.recycle() }
        pressBack()
        filter("[Characters]")
        awaitRules(dao.all.filter { it.group == "Characters" })
        screenshot("highlight-group-filter")

        menu(R.string.highlight_rule_group_manage)
        groupAction("Characters", edit = true)
        composeRule.onNode(hasSetTextAction()).performTextReplacement("People")
        composeRule.onNodeWithText(activityString(R.string.ok)).performClick()
        awaitGroup("People")
        screenshot("highlight-group-manager")
        closeGroupManager()
        // Renaming the active group must not leave a stale, empty filter behind.
        awaitRules(dao.all)

        filter("[People]")
        awaitRules(dao.all.filter { it.group == "People" })
        menu(R.string.highlight_rule_group_manage)
        groupAction("People", edit = false)
        composeRule.onNodeWithTag("highlightGroupMove").performClick()
        // This is a real group named like the special ungrouped option.
        choose("[$namedUngrouped]")
        await { dao.all.count { it.group == namedUngrouped } == 3 }
        assertEquals("Quotes", dao.all.single { it.name == "Quote" }.group)
        assertNull(dao.all.single { it.name == "Loose rule" }.group)
        closeGroupManager()
        awaitRules(dao.all)

        filter("[$namedUngrouped]")
        awaitRules(dao.all.filter { it.group == namedUngrouped })
        menu(R.string.highlight_rule_group_manage)
        groupAction(namedUngrouped, edit = false)
        composeRule.onNodeWithTag("highlightGroupMove").performClick()
        choose(activityString(R.string.no_group))
        await { dao.all.count { it.group == null } == 4 }
        closeGroupManager()
        awaitRules(dao.all)
        filter(activityString(R.string.no_group))
        awaitRules(dao.all.filter { it.group == null })

        filter("[Quotes]")
        awaitRules(dao.all.filter { it.group == "Quotes" })
        menu(R.string.highlight_rule_group_manage)
        groupAction("Quotes", edit = false)
        composeRule.onNodeWithTag("highlightManagerConfirm").performClick()
        await { dao.all.size == 4 }
        assertEquals(fixtures.filter { it.group != "Quotes" }.map { it.uuid }.toSet(),
            dao.all.map { it.uuid }.toSet())
        closeGroupManager()
        awaitRules(dao.all)
        screenshot("highlight-group-after-move-delete")
    }

    @Test fun exportAllIncludesRulesHiddenByGroupFilter() {
        filter("[Characters]"); awaitRules(dao.all.filter { it.group=="Characters" })
        val root=File(context.filesDir,"highlight-rule-transfers")
        val old=root.listFiles()?.map { it.name }?.toSet().orEmpty()
        val launched=AtomicReference<Intent?>(); val payload=AtomicReference<String?>()
        val monitor=object: Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_CREATE_DOCUMENT) return null
                payload.set(root.listFiles()!!.single { it.name !in old && it.extension=="json" }.readText())
                launched.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            menu(R.string.export_all); await { launched.get()!=null }
            val intent=launched.get()!!
            assertEquals("application/json",intent.type)
            assertEquals("HighlightRules.json",intent.getStringExtra(Intent.EXTRA_TITLE))
            assertFalse(intent.hasExtra("fileKey")); assertNull(intent.component)
            val exported=GSON.fromJson(payload.get(),HighlightRuleFile::class.java)
            assertEquals(HighlightRuleFile.TYPE,exported.type)
            val rows=checkNotNull(exported.rules)
            assertEquals(fixtures.map { it.uuid }.toSet(),rows.map { it!!.uuid }.toSet()); assertEquals(5,rows.size)
            composeRule.onNodeWithTag("highlightExportSuccess").assertDoesNotExist()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun exportingWritesTheDocumentBeforeSuccessAndRestoresTheReceipt() {
        val target=File(context.filesDir,"highlight-document-${java.util.UUID.randomUUID()}.json")
        target.writeText("before")
        val uri=androidx.core.content.FileProvider.getUriForFile(context,io.legado.app.constant.AppConst.authority,target)
        val monitor=object: Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_CREATE_DOCUMENT) return null
                return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            menu(R.string.export_all)
            await { composeRule.onAllNodesWithTag("highlightExportSuccess").fetchSemanticsNodes().isNotEmpty() }
            val exported=GSON.fromJson(target.readText(),HighlightRuleFile::class.java)
            assertEquals(HighlightRuleFile.TYPE,exported.type)
            assertEquals(fixtures.map { it.uuid }.toSet(),exported.rules!!.map { it!!.uuid }.toSet())
            assertEquals(5,exported.rules!!.size)
            scenario!!.recreate()
            await { composeRule.onAllNodesWithTag("highlightExportSuccess").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText(uri.toString()).assertExists()
            screenshot("highlight-export-success")
            pressBack()
            await { composeRule.onAllNodesWithTag("highlightExportSuccess").fetchSemanticsNodes().isEmpty() }
        } finally { instrumentation.removeMonitor(monitor); target.delete() }
    }

    @Test fun importingGroupAndEnabledChangesRefreshesBothVisibleFields() {
        val changed = dao.all.first().copy(group = "Updated", isEnabled = false)
        dao.importRules(listOf(changed))
        await { dao.all.first().let { rule -> rule.group == "Updated" && !rule.isEnabled } }
        awaitRules(dao.all)
        screenshot("highlight-group-import-refresh")
    }

    private fun menu(titleRes: Int) {
        composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        composeRule.onNodeWithText(activityString(titleRes)).performClick()
    }

    private fun activityString(resId: Int): String {
        lateinit var value: String
        scenario!!.onActivity { value = it.getString(resId) }
        return value
    }

    private fun closeGroupManager() {
        pressBack()
        await { composeRule.onAllNodesWithTag("highlightGroupManager").fetchSemanticsNodes().isEmpty() }
    }
    private fun filter(label: String) { menu(R.string.highlight_rule_group_filter); choose(label) }
    private fun choose(label: String) {
        val kind=if(composeRule.onAllNodesWithTag("highlightChoice:filter:ungrouped").fetchSemanticsNodes().isNotEmpty()) "filter" else "moveGroup"
        val key=if(label==activityString(R.string.no_group)) "ungrouped" else "group:${label.removePrefix("[").removeSuffix("]")}"
        composeRule.onNodeWithTag("highlightChoice:$kind:$key").performClick()
    }

    private fun groupAction(group: String, edit: Boolean) {
        awaitGroup(group)
        composeRule.onNodeWithTag(
            if (edit) "highlightGroupEdit:$group" else "highlightGroupDelete:$group"
        ).performClick()
    }

    private fun awaitGroup(group: String) = await {
        composeRule.onAllNodesWithTag("highlightGroupRow:$group").fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitRules(expected: List<HighlightRule>) {
        val expectedUuids = expected.mapTo(hashSetOf()) { it.uuid }
        await {
            runCatching {
                composeRule.waitForIdle()
                expected.forEach {
                    composeRule.onNodeWithTag("highlightRuleRow:${it.uuid}").fetchSemanticsNode()
                }
                fixtures.filterNot { it.uuid in expectedUuids }.forEach {
                    composeRule.onAllNodesWithTag("highlightRuleRow:${it.uuid}").assertCountEquals(0)
                }
            }.isSuccess
        }
        expected.forEach { rule ->
            val label = rule.group?.takeIf { it.isNotBlank() }
                ?.let { "[$it] ${rule.getDisplayName()}" }
                ?: rule.getDisplayName()
            composeRule.onNodeWithTag("highlightRuleRow:${rule.uuid}").assertTextContains(label)
            composeRule.onNodeWithTag("highlightRuleEnabled:${rule.uuid}").let {
                if (rule.isEnabled) it.assertIsOn() else it.assertIsOff()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            composeRule.waitForIdle()
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Highlight groups did not reach the expected state", condition())
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle(); instrumentation.waitForIdleSync()
        if(name=="highlight-font-metrics-settings") {
            composeRule.onNodeWithTag("highlight_rule_editor").assertExists()
            scenario!!.onActivity { assertTrue(it.window.decorView.hasWindowFocus()) }
        }
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue(bitmap.width>0 && bitmap.height>0)
            File(context.getExternalFilesDir("ui-regression"),"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
}
