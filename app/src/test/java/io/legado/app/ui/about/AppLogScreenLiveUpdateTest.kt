package io.legado.app.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLogScreenLiveUpdateTest {

    private val root = repositoryRoot()
    private val appLog = source("app/src/main/java/io/legado/app/constant/AppLog.kt")
    private val screen = source("app/src/main/java/io/legado/app/ui/about/AppLogScreen.kt")
    private val navigation = source("app/src/main/java/io/legado/app/ui/about/AppLogNavigation.kt")
    private val eventBus = source("app/src/main/java/io/legado/app/constant/EventBus.kt")

    @Test
    fun `saved logs update the visible screen through the listener flow`() {
        assertEquals(
            2,
            Regex("postEvent\\(EventBus\\.APP_LOG_UPDATED, true\\)")
                .findAll(appLog)
                .count(),
        )
        assertTrue(eventBus.contains("const val APP_LOG_UPDATED = \"appLogUpdated\""))
        assertTrue(appLog.contains("fun addListener(listener:"))
        assertTrue(appLog.contains("listeners.forEach { listener -> runCatching { listener(snapshot) } }"))
        assertTrue(screen.contains("AppLog.addListener(listener)"))
        assertTrue(screen.contains("AppLog.removeListener(listener)"))
    }

    @Test
    fun `clearing logs requires confirmation and clears HTTP records too`() {
        val clearAction = screen.substringAfter("fun clear()")

        assertTrue(screen.contains("text = { Text(stringResource(R.string.clear_log_confirm)) }"))
        assertTrue(screen.contains("viewModel.clear(); showClearConfirmation = false"))
        assertTrue(clearAction.contains("AppLog.clear()"))
        assertTrue(clearAction.contains("HttpLogStore.clear()"))
    }

    @Test
    fun `legacy log entry points use the typed route without a dialog fragment`() {
        assertTrue(navigation.contains("AppLogRoute"))
        assertTrue(navigation.contains("AppActivity.startIntent(this, AppLogRoute)"))
        assertFalse(File(root, "app/src/main/java/io/legado/app/ui/about/AppLogDialog.kt").exists())
        val sourceRoot = File(root, "app/src/main/java/io/legado/app/ui")
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file -> assertFalse(file.readText().contains("AppLogDialog")) }
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
