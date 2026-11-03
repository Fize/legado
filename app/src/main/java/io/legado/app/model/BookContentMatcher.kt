package io.legado.app.model

import java.util.regex.Pattern

/** UTF-16 ranges match the document model. Checks also run inside Java's regex backtracking. */
class BookContentMatcher(query: String,regex: Boolean,matchCase: Boolean) {
    private val pattern: Pattern
    init {
        require(query.isNotEmpty() && query.length<=2048)
        pattern=try { Pattern.compile(query,(if(regex) 0 else Pattern.LITERAL) or
            (if(matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)) }
        catch(error: StackOverflowError) { throw IllegalArgumentException("Pattern nesting is too deep",error) }
    }
    fun matches(text: String,start: Int=0,checkActive: () -> Unit = {}): Sequence<IntRange> = sequence {
        var deadline=0L
        var calls=0
        fun check() {
            if(calls++ and 63==0) {
                checkActive()
                if(System.nanoTime()>deadline) throw IllegalArgumentException("Search expression timed out")
            }
        }
        class Checked(private val begin: Int,private val finish: Int): CharSequence {
            override val length get()=finish-begin
            override fun get(index: Int): Char { check(); return text[begin+index] }
            override fun subSequence(startIndex: Int,endIndex: Int): CharSequence = Checked(begin+startIndex,begin+endIndex)
            override fun toString()=text.substring(begin,finish)
        }
        if(start>text.length) return@sequence
        require(start>=0)
        val matcher=pattern.matcher(Checked(0,text.length)).region(start,text.length)
            .useTransparentBounds(true).useAnchoringBounds(false)
        while(true) {
            checkActive(); deadline=System.nanoTime()+1_000_000_000L
            val found=try { matcher.find() } catch(error: StackOverflowError) {
                throw IllegalArgumentException("Pattern nesting is too deep",error)
            }
            if(!found) break
            val a=matcher.start(); val b=matcher.end()
            if(a==b && a in 1 until text.length && text[a].isLowSurrogate() && text[a-1].isHighSurrogate()) continue
            yield(a until b)
        }
    }
}
