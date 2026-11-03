package io.legado.app.help.storage

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in host probe. Normal connected tests must never terminate their own process. */
@RunWith(AndroidJUnit4::class)
class RestoreConfigurationDeathProbe {
    private fun root(): File {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run through the host config-write probe", args.getString("configWriteDeath") == "yes")
        val id = requireNotNull(args.getString("configWriteId"))
        require(UUID.fromString(id).toString() == id)
        return File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "compat-config-write/$id")
    }

    private fun save(file: File, value: JSONObject) {
        FileOutputStream(file).use { output ->
            output.write(value.toString().toByteArray())
            output.fd.sync()
        }
    }

    @Test fun holdPendingWriteForHostKill() {
        val root = root().apply { check(mkdirs()) }
        val target = File(root, "style.json").apply { writeText("old:${root.name}") }
        writeRestoredConfiguration(target) { output ->
            output.write("partial:${root.name}".toByteArray())
            output.flush()
            output.fd.sync()
            save(File(root, "pending.json"), JSONObject().put("pid", Process.myPid()).put("id", root.name))
            check(CountDownLatch(1).await(45, TimeUnit.SECONDS)) { "Host did not kill the pending writer" }
        }
        fail("Host probe must end with an actual SIGKILL")
    }

    @Test fun recoverAfterHostKill() {
        val root = root()
        val target = File(root, "style.json")
        assertEquals("old:${root.name}", target.readText())
        assertEquals("partial:${root.name}", File(target.path + ".new").readText())
        writeRestoredConfiguration(target) { it.write("new:${root.name}".toByteArray()) }
        assertEquals("new:${root.name}", target.readText())
        assertFalse(File(target.path + ".new").exists())
        save(File(root, "recovered.json"), JSONObject().put("pid", Process.myPid()).put("id", root.name))
    }
}
