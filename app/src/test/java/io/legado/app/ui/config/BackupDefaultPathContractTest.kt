package io.legado.app.ui.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupDefaultPathContractTest {

    @Test
    fun `default path is used and stale selected folders are reselected`() {
        val repository = source("app/src/main/java/io/legado/app/data/repository/BackupSettingsRepository.kt")
        val screen = source("app/src/main/java/io/legado/app/ui/config/BackupSettingsScreen.kt")
        val viewModel = source("app/src/main/java/io/legado/app/ui/config/BackupSettingsViewModel.kt")

        assertTrue(repository.contains("context.externalFiles.absolutePath"))
        assertTrue(screen.contains("backupPathSummary(backupPath, viewModel.defaultBackupPath, stringResource(R.string.default_path))"))
        assertTrue(repository.contains("FileDoc.fromDir(path).checkWrite()"))
        assertTrue(screen.contains("context.defaultSharedPreferences.edit().remove(PreferKey.backupPath).apply()"))
        assertTrue(screen.contains("HandleFileContract.DIR_SYS"))
        assertTrue(screen.contains("viewModel.backup(null, uploadWebDav)"))
        assertTrue(screen.contains("viewModel.backup(path, uploadWebDav)"))
        assertTrue(screen.contains("if (canWrite)"))
        assertTrue(repository.contains("Backup.backupLocked(context, path, uploadWebDav)"))
        assertTrue(viewModel.contains("fun backup(path: String?, uploadWebDav: Boolean)"))
        assertTrue(viewModel.contains("suspend fun canWriteBackupPath(path: String)"))
        assertFalse(screen.contains("BackupConfigFragment"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
