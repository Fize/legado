package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadPaddingBehaviorSourceTest {

    @Test
    fun `compose padding slider preserves final callback order`() {
        val source = paddingDialogSource()
        assertTrue(source.contains("onDragging(it.roundToInt().coerceIn(0, max))"))
        val finished = source.substringAfter("onValueChangeFinished = {")
            .substringBefore("valueRange =")
        assertTrue(finished.indexOf("onChanged(progress)") < finished.indexOf("onTrackingStop()"))
    }

    @Test
    fun `compose padding slider derives state from dialog progress`() {
        val source = paddingDialogSource()
        assertTrue(source.contains("var isDragging by remember { mutableStateOf(false) }"))
        assertTrue(source.contains("value = progress.toFloat()"))
        assertTrue(source.contains("onChanged(progress - 1)"))
        assertFalse(projectPath("src/main/java/io/legado/app/ui/widget/DetailSeekBar.kt").exists())
    }

    @Test
    fun `pending body edits stay scoped and are not discarded`() {
        val source = paddingDialogSource()

        assertTrue(source.contains("val region: Region"))
        assertTrue(source.contains("private var pendingBodyEdit: PaddingEdit?"))
        assertTrue(source.contains("private val bodyThrottle = throttle<Unit>(150, leading = false)"))
        assertTrue(source.contains("finishPendingBodyEditIfDifferent(edit)"))
        assertTrue(source.contains("finishPendingBodyEdit()\n        curRegion = region"))
        assertTrue(source.contains("override fun onDestroyView() {\n        finishPendingBodyEdit()"))
        assertTrue(source.contains("override fun onDismiss(dialog: DialogInterface) {\n        finishPendingBodyEdit()"))
    }

    @Test
    fun `tracking blocks region actions and reset uses config defaults`() {
        val source = paddingDialogSource()

        assertTrue(source.contains("setPanelActionsEnabled(false)"))
        assertTrue(source.contains("setPanelActionsEnabled(true)"))
        assertTrue(source.contains("private var activeTrackingCount = 0"))
        assertTrue(source.contains("private val defaultConfig = ReadBookConfig.Config()"))
        assertTrue(source.contains("lockLR && region == curRegion"))
        assertTrue(source.contains("ReadBookConfig.save()"))
        assertTrue(source.contains("bottomDialog++"))
        assertTrue(source.contains("bottomDialog--"))
    }

    @Test
    fun `padding panel is centered without covering the page footer`() {
        val source = paddingDialogSource()

        assertTrue(source.contains("gravity = Gravity.CENTER"))
        assertFalse(source.contains("gravity = Gravity.BOTTOM"))
        assertTrue(source.contains("setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)"))
        assertTrue(source.contains("cornerRadius = radius"))
    }

    private fun paddingDialogSource(): String = projectFile(
        "src/main/java/io/legado/app/ui/book/read/config/PaddingConfigDialog.kt"
    ).readText().normalizeLines()

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private fun projectPath(pathInApp: String): File =
        if (File("app").isDirectory) File("app/$pathInApp") else File(pathInApp)
}
