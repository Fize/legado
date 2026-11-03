package io.legado.app.data.cache

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CacheResourceLeasesTest {
    private val key = CacheResourceKey("book", "one")
    @Test fun activeLeaseBlocksRemovalAndCanCloseOnAnotherThread() {
        val leases = CacheResourceLeases(); val lease = leases.acquire(key)
        assertFalse(remove(leases, key) { fail("Active resource removed") })
        val thread = Thread { lease.close(); lease.close() }; thread.start(); thread.join()
        assertTrue(remove(leases, key) {})
        assertEquals(0, leases.entryCount())
    }
    @Test fun publicationInvalidatesAPreviouslyBuiltRetentionPlan() {
        val leases = CacheResourceLeases()
        leases.plan(key).use { plan ->
            leases.use(key, mutating = true) {}
            assertFalse(plan.removeIfUnchanged { fail("Obsolete plan applied") })
        }
        assertEquals(0, leases.entryCount())
    }
    @Test fun readingDoesNotInvalidateThePlanButProtectsTheResourceWhileActive() {
        val leases = CacheResourceLeases()
        leases.plan(key).use { plan ->
            leases.use(key) { assertFalse(plan.removeIfUnchanged { fail() }) }
            assertTrue(plan.removeIfUnchanged {})
        }
    }
    @Test fun aRemovalBlocksOnlyAcquisitionForTheSameResource() {
        val leases = CacheResourceLeases(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val attempted = CountDownLatch(1); val acquired = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val removing = executor.submit { remove(leases, key) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val writing = executor.submit { attempted.countDown(); leases.use(key) { acquired.countDown() } }
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            leases.use(CacheResourceKey("book", "other")) {}
            assertFalse(acquired.await(100, TimeUnit.MILLISECONDS))
            release.countDown(); removing.get(5, TimeUnit.SECONDS); writing.get(5, TimeUnit.SECONDS)
            assertEquals(0, leases.entryCount())
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun exceptionsReleaseOwnershipAndDoNotLeakEntries() {
        val leases = CacheResourceLeases()
        assertThrows(IllegalStateException::class.java) { leases.use(key, true) { error("write") } }
        assertThrows(IllegalStateException::class.java) { remove(leases, key) { error("remove") } }
        assertEquals(0, leases.entryCount())
    }
    private fun remove(leases: CacheResourceLeases, key: CacheResourceKey, action: () -> Unit) =
        leases.plan(key).use { it.removeIfUnchanged(action) }

}
