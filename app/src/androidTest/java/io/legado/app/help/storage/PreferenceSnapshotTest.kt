package io.legado.app.help.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PreferenceSnapshotTest {

    @Test fun allFrameworkPreferenceTypesAndEscapedStringsRemainReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "preference-types-${UUID.randomUUID()}")
        try {
            writePreferenceSnapshot(context, root.absolutePath, "config") {
                putString("text", "中文 <tag> & \"quoted\"\nnext")
                putString("empty", "")
                putInt("int", Int.MIN_VALUE)
                putLong("long", Long.MAX_VALUE)
                putFloat("float", 1.25f)
                putBoolean("boolean", false)
                putStringSet("set", setOf("", "中文", "<&>"))
                putStringSet("emptySet", emptySet())
            }
            val values = readPreferenceSnapshot(context, root.absolutePath, "config")!!
            assertEquals("中文 <tag> & \"quoted\"\nnext", values["text"])
            assertEquals("", values["empty"])
            assertEquals(Int.MIN_VALUE, values["int"])
            assertEquals(Long.MAX_VALUE, values["long"])
            assertEquals(1.25f, values["float"])
            assertEquals(false, values["boolean"])
            assertEquals(setOf("", "中文", "<&>"), values["set"])
            assertEquals(emptySet<String>(), values["emptySet"])
            assertEquals(listOf("config.xml"), root.list()?.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedSnapshotsDoNotReuseCachedPreferenceFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "preference-snapshot-test").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val first = File(root, "first")
        val second = File(root, "second")
        val normalName = "preference_snapshot_${UUID.randomUUID()}"
        try {
            writePreferenceSnapshot(context, first.absolutePath, "config") {
                putString("sentinel", "first")
            }
            writePreferenceSnapshot(context, second.absolutePath, "config") {
                putString("sentinel", "second")
            }

            assertEquals(
                "first",
                readPreferenceSnapshot(context, first.absolutePath, "config")?.get("sentinel"),
            )
            assertEquals(
                "second",
                readPreferenceSnapshot(context, second.absolutePath, "config")?.get("sentinel"),
            )
            assertEquals(listOf("config.xml"), first.list()?.sorted())
            assertEquals(listOf("config.xml"), second.list()?.sorted())

            assertTrue(
                context.getSharedPreferences(normalName, Context.MODE_PRIVATE)
                    .edit()
                    .putString("sentinel", "normal")
                    .commit()
            )
            assertTrue(
                File(context.applicationInfo.dataDir, "shared_prefs/${normalName}.xml").isFile
            )
            assertTrue(!File(second, "${normalName}.xml").exists())
        } finally {
            context.getSharedPreferences(normalName, Context.MODE_PRIVATE).edit().clear().commit()
            File(context.applicationInfo.dataDir, "shared_prefs/${normalName}.xml").delete()
            root.deleteRecursively()
        }
    }
}
