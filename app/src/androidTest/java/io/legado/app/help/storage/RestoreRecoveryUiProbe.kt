package io.legado.app.help.storage

import android.content.SharedPreferences
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import io.legado.app.ui.AppActivity
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.config.AppConfig
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Host-only fixture: the UI must retain a bad record until the host repairs this fixture's copy. */
@RunWith(AndroidJUnit4::class)
class RestoreRecoveryUiProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root(): File {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the host recovery UI controller", args.getString("restoreRecoveryUi") == "yes")
        val id = requireNotNull(args.getString("restoreRecoveryUiId"))
        require(UUID.fromString(id).toString() == id)
        return File(context.cacheDir, "compat-recovery-ui/$id")
    }
    private fun key(root: File) = "recovery_ui_${root.name}"
    private fun stage(root: File) = File(Backup.backupPath, "recovery-ui-${root.name}.bin")
    private fun journal() = File(context.filesDir, "restore-settings-v1.bin")
    private fun save(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun json(file: File, data: JSONObject) = save(file, data.toString().toByteArray())
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun put(editor: SharedPreferences.Editor, values: Map<String, *>) {
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value); is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value); is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
        } }
    }

    @Test fun holdCorruptOwnedRecordBeforeHostKill() = runBlocking {
        val root = root()
        assertTrue(Process.myUid() >= 10000)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(context.defaultSharedPreferences.contains(key(root)))
        backupRestoreMutex.recover {
            check(root.mkdirs())
            writePreferenceSnapshot(context, root.path, "original") { put(this, context.defaultSharedPreferences.all) }
            check(context.defaultSharedPreferences.edit().putString(key(root), "old")
                .putString(PreferKey.language, "zh").putInt(PreferKey.fontScale, 12).commit())
            val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            val poison = InstrumentationRegistry.getArguments().getString("restoreRecoveryUiBadPreferences") == "yes"
            val preferences = context.defaultSharedPreferences
            // Own, journaled wrong-type values expose constructor reads in the next fresh process.
            if (poison) preferences.unregisterOnSharedPreferenceChangeListener(AppConfig)
            try {
                transaction.editDefault().putString(key(root), "mixed").putString(PreferKey.language, "en")
                    .putInt(PreferKey.fontScale, 16).apply {
                        if (poison) {
                            putString(PreferKey.editFontScale, "invalid-owned-fixture")
                            putString(PreferKey.editAutoWrap, "invalid-owned-fixture")
                            putString(PreferKey.showSourceCheckStatus, "invalid-owned-fixture")
                        }
                    }.apply()
            } finally {
                if (poison) preferences.registerOnSharedPreferenceChangeListener(AppConfig)
            }
            save(File(root, "valid.bin"), journal().readBytes())
            val corrupt = journal().readBytes().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() }
            save(journal(), corrupt)
            stage(root).apply { parentFile!!.mkdirs(); writeText("preserve-$root") }
            File(root, "existing.zip").writeText("original archive ${root.name}")
            json(File(root, "ready.json"), JSONObject().put("id", root.name).put("pid", Process.myPid())
                .put("uid", Process.myUid()).put("key", key(root)).put("valid_sha256", hash(File(root, "valid.bin")))
                .put("corrupt_sha256", hash(journal())).put("transaction", transaction.id)
                .put("stage_relative", "files/backup/${stage(root).name}").put("stage_sha256", hash(stage(root))))
        }
        try {
            Thread.sleep(60_000)
            fail("Host must kill this prepared fixture")
        } finally { cleanup(root) }
    }

    @Test fun verifySelectedConstructorDoesNotReadRecoveryPreferences() {
        root() // Require explicit host opt-in.
        assertTrue(RestoreRecoveryGate.isQuarantined)
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("restoreRecoveryUiNativeEntry"))
        require(name in setOf("io.legado.app.ui.code.CodeEditActivity",
            "io.legado.app.ui.book.source.manage.BookSourceActivity"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val activity = context.classLoader.loadClass(name).getDeclaredConstructor().newInstance()
                assertEquals(name, activity.javaClass.name)
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw AssertionError("Blocked native constructor read pending preferences", it) }
    }

    @Test fun verifyBlockedBackupBeforeRepair() = runBlocking {
        val root = root()
        assertTrue(RestoreRecoveryGate.isQuarantined)
        val before = journal().readBytes()
        val failure = runCatching { Backup.backupLocked(context, root.path, uploadWebDav = false) }.exceptionOrNull()
        assertTrue(failure is RestoreRecoveryRequiredException)
        Backup.clearCache()
        assertArrayEquals(before, journal().readBytes())
        assertEquals("preserve-$root", stage(root).readText())
        assertEquals("original archive ${root.name}", File(root, "existing.zip").readText())
    }

    @Test fun verifyNewApplicationAfterUiClose() = runBlocking {
        val root = root()
        val ready = JSONObject(File(root, "ready.json").readText())
        try {
            assertNotEquals(ready.getInt("pid"), Process.myPid())
            assertEquals(RestoreRecoveryStatus.Ready, RestoreRecoveryGate.status.value)
            assertFalse(RestoreFileRecovery.hasPending(context))
            assertEquals("old", context.defaultSharedPreferences.getString(key(root), null))
            val configuration = context.applicationContext.resources.configuration
            assertEquals("zh", configuration.locales[0].language)
            assertEquals(1.2f, configuration.fontScale, 0.01f)
            assertTrue(backupRestoreMutex.tryLock()); backupRestoreMutex.unlock()
            ActivityScenario.launch<AppActivity>(Intent(context, AppActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertEquals("zh", activity.resources.configuration.locales[0].language)
                    assertEquals(1.2f, activity.resources.configuration.fontScale, 0.01f)
                }
            }
            // A healthy startup may now clear resolved staging; the actual archive must remain.
            assertEquals("original archive ${root.name}", File(root, "existing.zip").readText())
            json(File(root, "verified.json"), JSONObject().put("id", root.name).put("pid", Process.myPid())
                .put("normal_uid", Process.myUid()).put("barrier_released_after_reopen", true)
                .put("font_and_locale_restored", true).put("existing_archive_preserved", true))
        } finally { cleanup(root) }
    }

    @Test fun cleanupOwnedFixtureAfterHostFailure() { cleanup(root()) }

    private fun cleanup(root: File) = runBlocking {
        backupRestoreMutex.recover {
            if (journal().isFile && File(root, "valid.bin").isFile) save(journal(), File(root, "valid.bin").readBytes())
            RestoreFileRecovery.recover(context, cleanup = true)
            readPreferenceSnapshot(context, root.path, "original")?.let { values ->
                context.defaultSharedPreferences.edit().clear().apply { put(this, values); check(commit()) }
            }
            stage(root).delete()
            RestoreRecoveryGate.onStartupReady()
        }
    }
}
