package io.legado.app.help.storage

import android.app.Application
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.SharedPreferences
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
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

/** Opt-in host fixture: broadcast delivery uses the real framework in a non-instrumented process. */
@RunWith(AndroidJUnit4::class)
class RestoreReceiverContextProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root(): File {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the receiver context host controller", args.getString("receiverContextProbe") == "yes")
        val id = requireNotNull(args.getString("receiverContextId"))
        require(UUID.fromString(id).toString() == id)
        return File(context.cacheDir, "compat-receiver/$id")
    }
    private fun key(root: File) = "receiver-context-${root.name}"
    private fun save(file: File, bytes: ByteArray) = FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    private fun put(editor: SharedPreferences.Editor, values: Map<String, *>) {
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value); is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value); is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
        } }
    }

    @Test fun holdValidSettingsForAutomaticRecovery() = runBlocking {
        val root = root()
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(context.defaultSharedPreferences.contains(key(root)))
        backupRestoreMutex.recover {
            check(root.mkdirs())
            writePreferenceSnapshot(context, root.path, "default-original") {
                put(this, context.defaultSharedPreferences.all.filterKeys { it in setOf(PreferKey.language, PreferKey.fontScale) })
            }
            writePreferenceSnapshot(context, root.path, "local-original") {
                put(this, LocalConfig.all.filterKeys { it == "appCrash" })
            }
            check(context.defaultSharedPreferences.edit().putString(key(root), "old")
                .putString(PreferKey.language, "zh").putInt(PreferKey.fontScale, 12).commit())
            val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            transaction.editDefault().putString(key(root), "pending").putString(PreferKey.language, "en")
                .putInt(PreferKey.fontScale, 16).apply()
            val journal = File(context.filesDir, "restore-settings-v1.bin")
            save(File(root, "valid.bin"), journal.readBytes())
            val blocked = InstrumentationRegistry.getArguments().getString("receiverContextBlocked") == "yes"
            if (blocked) {
                val corrupt = journal.readBytes().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() }
                save(File(root, "corrupt.bin"), corrupt)
                save(journal, corrupt)
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(journal.readBytes()).joinToString("") { "%02x".format(it) }
            save(File(root, "ready.json"), JSONObject().put("id", root.name).put("pid", Process.myPid())
                .put("uid", Process.myUid()).put("transaction", transaction.id).put("key", key(root))
                .put("pending_sha256", hash).put("blocked", blocked)
                .put("valid_sha256", MessageDigest.getInstance("SHA-256").digest(File(root, "valid.bin").readBytes()).joinToString("") { "%02x".format(it) }).toString().toByteArray())
        }
        try { Thread.sleep(60_000); fail("Host must terminate this owned writer") }
        finally { cleanup(root, false) }
    }

    @Test fun verifyApplicationAndActivityResourcesAfterAutomaticRecovery() {
        val root = root()
        try {
            assertEquals(RestoreRecoveryStatus.Ready, RestoreRecoveryGate.status.value)
            assertTrue(!RestoreFileRecovery.hasPending(context) || RestoreFileRecovery.pendingIsResolved(context))
            assertEquals("old", context.defaultSharedPreferences.getString(key(root), null))
            val application = context.applicationContext as Application
            assertFalse(application.baseContext is MutableContextWrapper)
            assertEquals("zh", application.resources.configuration.locales[0].language)
            assertEquals(1.2f, application.resources.configuration.fontScale, .01f)
            assertEquals("zh", application.baseContext.resources.configuration.locales[0].language)
            assertEquals(1.2f, application.baseContext.resources.configuration.fontScale, .01f)
            ActivityScenario.launch<AppActivity>(Intent(context, AppActivity::class.java)).use { scenario ->
                scenario.onActivity {
                    assertFalse(it.isFinishing)
                    assertEquals("zh", it.resources.configuration.locales[0].language)
                    assertEquals(1.2f, it.resources.configuration.fontScale, .01f)
                }
            }
            save(File(root, "verified.json"), JSONObject().put("id", root.name).put("pid", Process.myPid())
                .put("uid", Process.myUid()).put("base_context_class", application.baseContext.javaClass.name)
                .put("application_and_activity_locale_font_restored", true).toString().toByteArray())
        } finally { cleanup(root, false) }
    }

    @Test fun cleanupOwnedReceiverFixture() {
        val root = root()
        cleanup(root, InstrumentationRegistry.getArguments().getString("restoreOwnedCrashFlag") == "yes")
    }

    private fun cleanup(root: File, restoreOwnedCrash: Boolean) = runBlocking {
        backupRestoreMutex.recover {
            val ready = File(root, "ready.json").takeIf(File::isFile)?.let { JSONObject(it.readText()) }
            val pendingFile = File(context.filesDir, "restore-settings-v1.bin")
            val corrupt = File(root, "corrupt.bin")
            if (pendingFile.isFile && corrupt.isFile && pendingFile.readBytes().contentEquals(corrupt.readBytes())) {
                save(pendingFile, File(root, "valid.bin").readBytes())
            }
            val pending = RestoreSettingsTransaction.read(context)
            if (pending != null) check(pending.id == ready?.getString("transaction")) { "Unowned pending record" }
            RestoreFileRecovery.recover(context, cleanup = true)
            readPreferenceSnapshot(context, root.path, "default-original")?.let { original ->
                val editor = context.defaultSharedPreferences.edit().remove(key(root)).remove(PreferKey.language).remove(PreferKey.fontScale)
                put(editor, original); check(editor.commit())
            }
            if (restoreOwnedCrash) readPreferenceSnapshot(context, root.path, "local-original")?.let { original ->
                val editor = LocalConfig.edit().remove("appCrash"); put(editor, original); check(editor.commit())
            }
            RestoreRecoveryGate.onStartupReady()
        }
    }
}
