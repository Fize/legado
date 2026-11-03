package io.legado.app.service

import io.legado.app.model.parseExportScope
import org.junit.Assert.*
import org.junit.Test

class ExportScopeTest {
    @Test fun `range uses one based chapter numbers and returns an immutable sorted selection`() {
        val result=parseExportScope(" 3,1-2, 2 - 4,1 ",4)
        assertEquals(listOf(0,1,2,3),result.toList())
        assertThrows(UnsupportedOperationException::class.java) { (result as MutableSet<Int>).clear() }
    }
    @Test fun `invalid ranges are rejected before expanding them`() {
        listOf("", "0", "-1", "2-1", "1-5", "1,", "1-2147483647", "1-999999999999999999999", "1-2-3", "nope").forEach { value ->
            assertThrows("Range $value",Exception::class.java) { parseExportScope(value,4) }
        }
        assertThrows(Exception::class.java) { parseExportScope("1",0) }
        assertThrows(Exception::class.java) { parseExportScope("1,".repeat(50_001),4) }
    }
    @Test fun `overlapping large ranges do not allocate every duplicate chapter`() {
        assertEquals(100_000,parseExportScope(List(100) { "1-100000" }.joinToString(","),100_000).size)
    }
}
