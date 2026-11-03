package io.legado.app.ui.book.read.compose

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.HighlightStyle
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.highlight.HighlightManagerArtifacts
import io.legado.app.ui.navigation.TextReaderRoute
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TextReaderRuleUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val id=UUID.randomUUID().toString()
    private val book=Book(bookUrl="fixture://rule-ui-$id",origin="source-$id",name=id,author=id,
        readConfig=Book.ReadConfig(useReplaceRule=false),totalChapterNum=1)
    private val chapter=BookChapter(bookUrl=book.bookUrl,url="fixture://rule-chapter-$id",index=0,title="Rule chapter")
    private val background=ReadBookConfig.getConfig(ReadBookConfig.readStyleSelect)
    private val oldBackground=background.copy()
    private val oldAlpha=ReadBookConfig.bgAlpha
    private var scenario: ActivityScenario<AppActivity>?=null
    private val managerArtifacts=HighlightManagerArtifacts()
    private var rule=HighlightRule(name="Rule $id",pattern="amber",scope=book.origin,order=Int.MAX_VALUE)
        .apply { applyStyle(HighlightStyle(fill=0xff00ff00.toInt())) }
    @Before fun setup() {
        background.bgType=0; background.bgStr="#ffffff"; background.bgTypeNight=0; background.bgStrNight="#ffffff"
        background.bgTypeEInk=0; background.bgStrEInk="#ffffff"; ReadBookConfig.bgAlpha=100
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter)
        BookHelp.saveText(book,chapter,(0..100).joinToString("\n") { "Rule amber and violet phrase $it." })
        rule=rule.copy(id=appDb.highlightRuleDao.insert(rule).single())
    }
    @After fun cleanup() {
        scenario?.close(); managerArtifacts.cleanup(); io.legado.app.help.globalExecutor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
        appDb.openHelper.writableDatabase.execSQL("DELETE FROM highlight_rule_drafts WHERE ruleId = ?",arrayOf(rule.id))
        appDb.highlightRuleDao.findById(rule.id)?.takeIf { it.uuid==rule.uuid }?.let { appDb.highlightRuleDao.delete(it) }
        appDb.readerSessionDao.get(id)?.let { appDb.readerSessionDao.close(id,it.ownerToken) }
        appDb.bookDao.delete(book); BookHelp.clearCache(book)
        background.bgType=oldBackground.bgType; background.bgStr=oldBackground.bgStr
        background.bgTypeNight=oldBackground.bgTypeNight; background.bgStrNight=oldBackground.bgStrNight
        background.bgTypeEInk=oldBackground.bgTypeEInk; background.bgStrEInk=oldBackground.bgStrEInk; ReadBookConfig.bgAlpha=oldAlpha
    }
    private val selection=SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection)
    private fun canvas()=compose.onNode(selection)
    private fun label(name: String=rule.name)=context.getString(R.string.reader_highlight_rule_action,name)
    private fun actions()=canvas().fetchSemanticsNode().config[SemanticsActions.CustomActions]
    private fun awaitRule(name: String=rule.name) {
        compose.waitUntil(15_000) { compose.onAllNodes(selection).fetchSemanticsNodes().size==1 }
        compose.waitUntil(10_000) { actions().any { it.label==label(name) } }
    }
    private fun launch() { scenario=ActivityScenario.launch(AppActivity.startIntent(context,TextReaderRoute(book.bookUrl,requestId=id))); awaitRule() }
    private fun openRule() { val action=actions().single { it.label==label() }; compose.runOnIdle { assertTrue(action.action()) }
        compose.onNodeWithTag("text_reader_rule_actions").assertExists() }
    private fun greenPixels(): Int {
        val bitmap=canvas().captureToImage().asAndroidBitmap()
        val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return pixels.count { android.graphics.Color.green(it)>220 && android.graphics.Color.red(it)<100 && android.graphics.Color.blue(it)<100 }
    }
    @Test fun rulesPaintRealPixelsAndCanBeDisabledFromTheReader() {
        launch(); compose.waitUntil(10_000) { greenPixels()>20 }; assertTrue(greenPixels()>20)
        val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        openRule(); compose.onNodeWithTag("text_reader_rule_disable").performClick()
        compose.waitUntil(10_000) { !appDb.highlightRuleDao.findById(rule.id)!!.isEnabled &&
            compose.onAllNodesWithTag("text_reader_rule_actions").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { actions().none { it.label==label() } }
        assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun sameIdentityEditsAreObservedWithoutLegacyReaderRefreshEvents() {
        launch(); val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        val updated=rule.copy(name="Changed $id",pattern="violet").apply { applyStyle(HighlightStyle(fill=0xff00ff00.toInt(),bold=true)) }
        appDb.highlightRuleDao.update(updated); awaitRule(updated.name)
        assertFalse(actions().any { it.label==label() }); compose.waitUntil(10_000) { greenPixels()>20 }
        val action=actions().single { it.label==label(updated.name) }; compose.runOnIdle { assertTrue(action.action()) }
        compose.onNodeWithText("violet").assertExists()
        assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun popupRecreationRestoresOnlyTheCurrentRuleAndDisableStillTargetsIt() {
        launch(); val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos; openRule()
        scenario!!.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("text_reader_rule_actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(rule.name).assertExists()
        compose.onNodeWithTag("text_reader_rule_disable").performClick()
        compose.waitUntil(10_000) { !appDb.highlightRuleDao.findById(rule.id)!!.isEnabled }
        assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun ruleManagerRoundTripPreservesTheReadingAnchor() {
        launch(); val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
        canvas().performTouchInput { click(center) }
        compose.onNodeWithTag("text_reader_highlight_rules").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(selection).fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("highlightRuleEnabled:${rule.uuid}"))
        compose.onNodeWithTag("highlightRuleEnabled:${rule.uuid}").performClick()
        compose.waitUntil(10_000) { !appDb.highlightRuleDao.findById(rule.id)!!.isEnabled }
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(15_000) { compose.onAllNodes(selection).fetchSemanticsNodes().size==1 }
        compose.waitUntil(10_000) { actions().none { it.label==label() } }
        assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun editingFromTheRulePopupReturnsToTheSameReaderAndRefreshesItsActions() {
        launch(); val original=appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos; openRule()
        compose.onNodeWithTag("text_reader_rule_edit").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("highlightRuleEditName") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("highlightRuleEditName").performTextReplacement("Edited $id")
        compose.onNodeWithTag("highlightRuleEditPattern").performTextReplacement("violet")
        compose.onNodeWithTag("highlightRuleEditSave").performClick()
        awaitRule("Edited $id")
        assertFalse(actions().any { it.label==label() })
        assertEquals("violet",appDb.highlightRuleDao.findById(rule.id)!!.pattern)
        assertEquals(original,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }

}
