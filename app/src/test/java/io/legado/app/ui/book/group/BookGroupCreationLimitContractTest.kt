package io.legado.app.ui.book.group

import io.legado.app.data.entities.availableBookGroupId
import org.junit.Assert.*
import org.junit.Test

class BookGroupCreationLimitContractTest {
    @Test fun only63PositiveBitsCanBeAllocated() {
        val groups = mutableListOf<Long>()
        repeat(63) { bit ->
            val id = availableBookGroupId(groups)
            assertEquals(1L shl bit, id)
            groups.add(checkNotNull(id))
        }
        assertNull(availableBookGroupId(groups))
        assertFalse(groups.contains(Long.MIN_VALUE))
    }
    @Test fun aRemovedBitCanBeReused() {
        assertEquals(2L, availableBookGroupId(listOf(1L, 4L, 8L)))
    }
    @Test fun legacySignBitDoesNotOccupyThePositiveBits() {
        assertEquals(1L, availableBookGroupId(listOf(Long.MIN_VALUE, -1L, -2L, -100L)))
    }
    @Test fun malformedLegacyCombinationsStillReserveAllTheirBits() {
        assertEquals(4L, availableBookGroupId(listOf(3L)))
    }
}
