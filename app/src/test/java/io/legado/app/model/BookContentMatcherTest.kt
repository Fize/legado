package io.legado.app.model

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BookContentMatcherTest {
    @Test fun literalSearchUsesUtf16AndHonorsCase() {
        val text="😀 Needle needle NEEDLE"
        assertEquals(listOf(10 until 16),BookContentMatcher("needle",false,true).matches(text).toList())
        assertEquals(listOf(3 until 9,10 until 16,17 until 23),BookContentMatcher("needle",false,false).matches(text).toList())
        assertEquals(listOf(0 until 2),BookContentMatcher("😀",false,true).matches(text).toList())
    }
    @Test fun zeroWidthMatchesAdvanceByCodePointAndCanResumeAtTheEnd() {
        val matcher=BookContentMatcher("(?=.)",true,true)
        assertEquals(listOf(0,1,3),matcher.matches("a😀b").map { it.first }.toList())
        assertEquals(listOf(3),matcher.matches("a😀b",3).map { it.first }.toList())
        assertTrue(matcher.matches("a😀b",5).none())
        assertEquals(listOf(4),BookContentMatcher("$",true,true).matches("a😀b").map { it.first }.toList())
    }
    @Test fun resumedRegexRetainsWholeDocumentAnchorsAndLookbehind() {
        assertTrue(BookContentMatcher("^needle",true,true).matches("xxneedle",2).none())
        assertEquals(listOf(8 until 14),BookContentMatcher("\\bneedle",true,true).matches("aneedle needle",2).toList())
    }
    @Test fun contiguousRegexMatchesAndResumeUseThePreviousMatchBoundary() {
        val matcher=BookContentMatcher("\\G.",true,true)
        assertEquals(listOf(0 until 1,1 until 3,3 until 4),matcher.matches("a😀b").toList())
        assertEquals(listOf(1 until 3,3 until 4),matcher.matches("a😀b",1).toList())
    }
    @Test fun invalidAndOversizedPatternsFailBeforeSearching() {
        assertThrows(IllegalArgumentException::class.java) { BookContentMatcher("",false,true) }
        assertThrows(IllegalArgumentException::class.java) { BookContentMatcher("x".repeat(2049),false,true) }
        assertThrows(IllegalArgumentException::class.java) { BookContentMatcher("(",true,true) }
    }
    @Test fun cancellationIsCheckedInsideRegexBacktracking() {
        var calls=0
        assertThrows(CancellationException::class.java) {
            BookContentMatcher("(a+)+$",true,true).matches("a".repeat(4000)+"!") {
                if(++calls>20) throw CancellationException("fixture")
            }.toList()
        }
        assertTrue(calls>20)
    }
    @Test fun literalMetacharactersNeverBecomeRegexCommands() {
        assertEquals(listOf(2 until 5),BookContentMatcher("a+b",false,true).matches("x a+b aaab").toList())
    }
}
