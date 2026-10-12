package io.legado.app.ui.book.source.edit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourceEditLayoutTest {

    @Test
    fun `options use a compact card with one accessible header`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()
        val optionsSection = source.section(
            "private fun SourceOptionsCardComposable()",
            "private fun OptionCheckBoxItem("
        )

        assertTrue(optionsSection.contains("Card("))
        assertTrue(optionsSection.contains("RoundedCornerShape(8.dp)"))
        assertTrue(optionsSection.contains("heightIn(min = 48.dp)"))
        assertTrue(optionsSection.contains("semantics { contentDescription = headerContentDesc }"))
        assertTrue(optionsSection.contains("clickable { updateOptionPanel(!optionsExpandedState) }"))
        assertTrue(optionsSection.contains("maxLines = 1"))
        assertTrue(optionsSection.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(optionsSection.contains("if (optionsExpandedState) R.drawable.ic_expand_less else R.drawable.ic_expand_more"))
    }

    @Test
    fun `type and six existing switches start inside collapsed content`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()
        val optionsSection = source.section(
            "private fun SourceOptionsCardComposable()",
            "private fun OptionCheckBoxItem("
        )

        assertTrue(source.contains("private var optionsExpandedState by mutableStateOf(false)"))
        assertTrue(optionsSection.contains("if (optionsExpandedState)"))
        assertTrue(optionsSection.contains("stringArrayResource(R.array.book_type)"))
        assertTrue(optionsSection.contains("FlowRow("))
        listOf(
            "isEnableState",
            "isEnableExploreState",
            "isEnableCookieState",
            "isEnableReviewState",
            "isEventListenerState",
            "isCustomButtonState"
        ).forEach { stateVar ->
            assertTrue("missing option state in FlowRow: $stateVar", optionsSection.contains(stateVar))
        }
    }

    @Test
    fun `activity refreshes summary and keeps every source option mapping`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()
        val optionsSection = source.section(
            "private fun SourceOptionsCardComposable()",
            "private fun OptionCheckBoxItem("
        )
        val upSource = source.section("private fun upSourceView(", "private fun applyPendingEditResult(")
        val getSource = source.section("private fun getSource(): BookSource", "sourceEntities.forEach")

        assertTrue(optionsSection.contains("selectedTypePositionState"))
        assertTrue(upSource.contains("selectedTypePositionState = when (it.bookSourceType)"))
        assertTrue(getSource.contains("source.bookSourceType = when (selectedTypePositionState)"))
        listOf(
            "4 -> BookSourceType.video",
            "3 -> BookSourceType.file",
            "2 -> BookSourceType.image",
            "1 -> BookSourceType.audio",
            "else -> BookSourceType.default"
        ).forEach { mapping ->
            assertTrue("missing type mapping: $mapping", getSource.contains(mapping))
        }
        listOf(
            "BookSourceType.video -> 4",
            "BookSourceType.file -> 3",
            "BookSourceType.image -> 2",
            "BookSourceType.audio -> 1",
            "else -> 0"
        ).forEach { mapping ->
            assertTrue("missing type restore mapping: $mapping", upSource.contains(mapping))
        }
        SAVE_MAPPINGS.forEach { mapping ->
            assertTrue("missing save mapping: $mapping", source.contains(mapping))
        }
        assertTrue(upSource.contains("updateOptionPanel()"))
        assertTrue(optionsSection.contains("headerContentDesc"))
    }

    @Test
    fun `edit fields handle unsafe text and inline edit safety`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()
        val fieldRow = source.section(
            "private fun EditEntityFieldRow(",
            "override fun finish()"
        )

        assertTrue(fieldRow.contains("EditSafety.isTooLongForInline(rawText)"))
        assertTrue(fieldRow.contains("EditSafety.presentation(rawText, combiningPlaceholder)"))
        assertTrue(fieldRow.contains("val isUnsafeText = !presentation.isInlineEditable"))
        assertTrue(fieldRow.contains("if (isUnsafeText)"))
        assertTrue(fieldRow.contains("openUnsafeTextEditor(editEntity)"))
        assertTrue(fieldRow.contains("maxLines = EditSafety.PREVIEW_LINES"))
    }

    @Test
    fun `ported panel stays independent from legadoT theme stack`() {
        val source = File(repositoryRoot, ACTIVITY_PATH).readText()

        listOf(
            "Widget.Material3",
            "M3ColorScheme",
            "AppColorScheme",
            "FieldNavBar",
            "skin_"
        ).forEach { dependency ->
            assertFalse("unexpected dependency: $dependency", source.contains(dependency))
        }
    }

    @Test
    fun `source editor provides main and field scrollable tab navigation`() {
        val activity = File(repositoryRoot, ACTIVITY_PATH).readText()
        assertTrue(activity.contains("ScrollableTabRow("))
        assertTrue(activity.contains("listState.animateScrollToItem(index)"))
    }

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

    private val repositoryRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }

    private companion object {
        const val ACTIVITY_PATH =
            "app/src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt"
        val SAVE_MAPPINGS = listOf(
            "source.enabled = isEnableState",
            "source.enabledExplore = isEnableExploreState",
            "source.enabledCookieJar = isEnableCookieState",
            "reviewRule.enabled = isEnableReviewState",
            "source.eventListener = isEventListenerState",
            "source.customButton = isCustomButtonState"
        )
    }
}
