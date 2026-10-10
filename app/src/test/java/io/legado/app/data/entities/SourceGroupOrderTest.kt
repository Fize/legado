package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceGroupOrderTest {

    @Test
    fun `group updates preserve existing order`() {
        val bookSource = BookSource(bookSourceGroup = "B,A")
        bookSource.addGroup("A;C")
        assertEquals("B,A,C", bookSource.bookSourceGroup)
        bookSource.bookSourceGroup = "C,B,A"
        bookSource.removeGroup("B")
        assertEquals("C,A", bookSource.bookSourceGroup)

        val bookSourcePart = BookSourcePart(bookSourceGroup = "B,A")
        bookSourcePart.addGroup("A；C")
        assertEquals("B,A,C", bookSourcePart.bookSourceGroup)
        bookSourcePart.bookSourceGroup = "C,B,A"
        bookSourcePart.removeGroup("B")
        assertEquals("C,A", bookSourcePart.bookSourceGroup)

        val replaceRule = ReplaceRule(group = "B,A")
        replaceRule.addGroup("A；C")
        assertEquals("B,A,C", replaceRule.group)
        replaceRule.group = "C,B,A"
        replaceRule.removeGroup("B")
        assertEquals("C,A", replaceRule.group)
    }
}
