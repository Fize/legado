package io.legado.app.help.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.utils.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@RunWith(AndroidJUnit4::class)
class FileDocWriteContextTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "filedoc-scope-${UUID.randomUUID()}").apply { check(mkdir()) }
    private val folder = FileDoc.fromFile(root)
    @After fun cleanup() { check(root.deleteRecursively() || !root.exists()) }
    private class Marker(private val marker: String) : FileDocWriteInterceptor {
        override fun create(parent: FileDoc, name: String, subDirs: Array<out String>, execution: CoroutineContext) =
            parent.copy(name = marker + name)
        override fun write(destination: FileDoc, source: File, execution: CoroutineContext) = false
    }

    @Test fun concurrentCoroutinesKeepTheirOwnAdapterAcrossSuspensions() = runBlocking {
        withContext(Dispatchers.Default) {
            (0..15).map { index -> async(FileDocWriteContext(Marker("$index:"))) {
                repeat(25) { yield(); assertEquals("$index:owned", folder.createFileIfNotExist("owned").name) }
            } }.awaitAll()
        }
        assertFalse(File(root, "owned").exists())
    }

    @Test fun nestedScopesRestoreTheOuterAdapterAndThenNormalFileIo() = runBlocking {
        withContext(FileDocWriteContext(Marker("outer:"))) {
            assertEquals("outer:a", folder.createFileIfNotExist("a").name)
            withContext(Dispatchers.IO + FileDocWriteContext(Marker("inner:"))) {
                assertEquals("inner:b", folder.createFileIfNotExist("b").name)
            }
            assertEquals("outer:c", folder.createFileIfNotExist("c").name)
        }
        val source = File(root, "source").apply { writeText("ordinary bytes") }
        val destination = folder.createFileIfNotExist("destination"); destination.writeFile(source)
        assertEquals("ordinary bytes", destination.readText())
        assertEquals(setOf("source", "destination"), root.list()!!.toSet())
    }

    @Test fun cancellingTheActualChildAbortsItsBridgeWithoutCancellingTheParent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val boundary = ExportRestoreBoundary(db, restoreWriteGeneration.capture(), currentCoroutineContext())
            val entered = CountDownLatch(1); val calls = AtomicInteger()
            val interceptor = object : FileDocWriteInterceptor {
                override fun create(parent: FileDoc, name: String, subDirs: Array<out String>, execution: CoroutineContext): FileDoc? = null
                override fun write(destination: FileDoc, source: File, execution: CoroutineContext): Boolean {
                    entered.countDown(); boundary.synchronous(execution) { calls.incrementAndGet() }; return true
                }
            }
            backupRestoreMutex.recover {
                val work = scope.async(FileDocWriteContext(interceptor)) { folder.writeFile(File(root, "unused")) }
                assertTrue(entered.await(5, TimeUnit.SECONDS)); delay(100); assertFalse(work.isCompleted)
                work.cancel(); withTimeout(5000) { work.join() }; assertEquals(0, calls.get())
            }
            currentCoroutineContext().ensureActive(); assertEquals(0, calls.get())
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
    }
}
