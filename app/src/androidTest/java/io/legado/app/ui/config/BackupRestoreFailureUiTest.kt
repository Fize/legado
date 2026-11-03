package io.legado.app.ui.config

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.BackupSettingsRepository
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.RestoreFileRecovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupRestoreFailureUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun malformedBackupReportsFailureWithoutSettingSuccessNotice() = runBlocking {
        val lastBackup = LocalConfig.lastBackup
        val invalid = File.createTempFile("invalid-backup-", ".zip", context.cacheDir)
        invalid.writeText("not a zip archive")
        try {
            val viewModel = BackupSettingsViewModel(BackupSettingsRepository(context))
            viewModel.restoreLocal(invalid.toUri())

            val deadline = System.currentTimeMillis() + 10_000
            while (viewModel.uiState.value.notice == null
                && System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }

            val notice = viewModel.uiState.value.notice
            assertNotNull(notice)
            assertNotEquals(R.string.success, notice!!.resourceId)
            assertNotEquals(R.string.restore_success, notice.resourceId)
            assertFalse(notice.text.isNullOrBlank())
            assertNotEquals(context.getString(R.string.success), notice.text)
            assertNotEquals(context.getString(R.string.restore_success), notice.text)
            assertEquals(lastBackup, LocalConfig.lastBackup)
            assertFalse(RestoreFileRecovery.hasPending(context))
        } finally {
            invalid.delete()
        }
    }
}
