package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class ReadPositionVersionTest {

    @Test
    fun `a late automatic reload loses reset permission after user movement`() {
        val version = ReadPositionVersion()
        val requestVersion = version.snapshot()

        assertTrue(version.isCurrent(requestVersion))
        version.markChanged()
        assertFalse(version.isCurrent(requestVersion))
    }
    @Test
    fun `concurrent movements cannot reuse a position version`() {
        val version = ReadPositionVersion()
        val start = CountDownLatch(1)
        val threads = List(4) { Thread { start.await(); repeat(1_000) { version.markChanged() } } }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(4_000L, version.snapshot())
    }
}
