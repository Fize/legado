package io.legado.app.ui.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateDialogLifecycleTest {

    @Test
    fun `update callbacks skip dialogs after fragment state is saved`() {
        val main = functionBody(
            "src/main/java/io/legado/app/ui/main/MainActivity.kt",
            "private fun upVersion()",
            "private fun notifyAppCrash()"
        )
        val shared = projectFile(
            "src/main/java/io/legado/app/ui/about/CheckAppUpdate.kt"
        ).readText()

        assertGuardBeforeDialog(main, "supportFragmentManager.isStateSaved")
        assertGuardBeforeDialog(shared, "isAdded && !childFragmentManager.isStateSaved")
        assertTrue(shared.contains("!isFinishing && !supportFragmentManager.isStateSaved"))
        val about = projectFile("src/main/java/io/legado/app/ui/about/AboutActivity.kt").readText()
        assertTrue(about.contains("onClick = { checkAppUpdate() }"))
        assertTrue(about.contains("onClick = { checkAppUpdate(true) }"))
        val my = projectFile("src/main/java/io/legado/app/ui/main/my/MyFragment.kt").readText()
        assertTrue(my.contains("\"check_update\" -> checkAppUpdate()"))
        assertTrue(my.contains("\"check_beta_update\" -> checkAppUpdate(beta = true)"))
    }

    @Test
    fun `update dialog carries release metadata into the toolbar`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()

        assertTrue(source.contains("putLong(\"size\", updateInfo.size)"))
        assertTrue(source.contains("putLong(\"createdAt\", updateInfo.createdAt)"))
        assertTrue(source.contains("ConvertUtils.formatFileSize(size)"))
        assertTrue(source.contains("DateTimeFormatter.ISO_LOCAL_DATE"))
    }

    @Test
    fun `update dialog uses the system download action for beta and formal updates`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()

        assertTrue(source.contains("putBoolean(\"isBeta\", updateInfo.isBeta)"))
        assertTrue(source.contains("setLayout(0.9f, 0.8f)"))
        assertTrue(source.contains("R.string.beta_update_now"))
        val btnBetaSection = source.substringAfter("// btnBetaUpdate")
            .substringBefore("private fun formatUpdateMetadata")
        assertTrue(btnBetaSection.contains("startDownload(arguments?.getString(\"url\"))"))
        assertFalse(btnBetaSection.contains("openUrl"))
    }

    @Test
    fun `formal update hides only the primary toolbar download action`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()

        assertTrue(source.contains("val showPrimaryDownload = false"))
        assertTrue(source.contains("val showBackupDownload = !isBetaUpdate && !arguments?.getString(\"backupUrl\").isNullOrBlank()"))
        assertTrue(source.contains("val showMirrorDownload = !isBetaUpdate && !arguments?.getString(\"mirrorUrl\").isNullOrBlank()"))
        assertTrue(source.contains("val showAlternateMirrorDownload = !isBetaUpdate && !arguments?.getString(\"alternateMirrorUrl\").isNullOrBlank()"))
    }

    @Test
    fun `formal update dialog exposes the backup cdn separately from github`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()

        assertTrue(source.contains("putString(\"mirrorUrl\", updateInfo.mirrorDownloadUrl)"))
        assertTrue(
            source.contains(
                "putString(\"alternateMirrorUrl\", updateInfo.alternateMirrorDownloadUrl)"
            )
        )
        assertTrue(source.contains("R.string.download_from_backup_cdn"))
        assertTrue(source.contains("startDownload(arguments?.getString(\"mirrorUrl\"))"))
        assertTrue(source.contains("R.string.download_from_second_cdn"))
        assertTrue(source.contains("startDownload(arguments?.getString(\"alternateMirrorUrl\"))"))
        assertTrue(source.contains("R.string.download_from_github"))
        assertTrue(source.contains("startDownload(arguments?.getString(\"backupUrl\"))"))
    }

    @Test
    fun `manual update errors show their message without a redundant action prefix`() {
        val shared = projectFile(
            "src/main/java/io/legado/app/ui/about/CheckAppUpdate.kt"
        ).readText()
        assertTrue(shared.contains("AppUpdate.checkBeta(lifecycleScope)"))
        assertTrue(shared.contains("AppUpdate.gitHubUpdate.check(lifecycleScope)"))
        assertTrue(shared.contains("appCtx.toastOnUi(it.localizedMessage)"))
        assertFalse(shared.contains("getString(R.string.check_update)"))
        assertFalse(shared.contains("getString(R.string.check_beta_update)"))
    }

    private fun assertGuardBeforeDialog(source: String, guard: String) {
        assertTrue(source.indexOf(guard) in 0 until source.indexOf("UpdateDialog(it)"))
    }

    private fun functionBody(path: String, start: String, end: String): String {
        return projectFile(path).readText().substringAfter(start).substringBefore(end)
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
