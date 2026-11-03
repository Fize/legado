package io.legado.app.model

/** Monotone UTF-16 projection between persistent reading text and a rendered representation. */
class TextReaderPositionMap private constructor(private val forward: IntArray, private val backward: IntArray,
    private val ends: IntArray) {
    fun toDisplay(position: Int): Int = forward[position.coerceIn(0, forward.lastIndex)]
    fun toDisplayEnd(position: Int): Int = ends[position.coerceIn(0, ends.lastIndex)]
    fun toStorage(position: Int): Int = backward[position.coerceIn(0, backward.lastIndex)]

    companion object {
        private class Matches {
            private var values = IntArray(128)
            var size = 0; private set
            fun add(a: Int, b: Int) {
                if (size * 2 == values.size) values = values.copyOf(values.size * 2)
                values[size * 2] = a; values[size * 2 + 1] = b; size++
            }
            fun a(index: Int) = values[index * 2]
            fun b(index: Int) = values[index * 2 + 1]
            fun clear() { size = 0 }
        }
        fun between(storage: String, display: String): TextReaderPositionMap {
            val forward = IntArray(storage.length + 1)
            val ends = IntArray(storage.length + 1)
            val backward = IntArray(display.length + 1)
            val matches = Matches()
            var a = 0; var b = 0
            // Reflow and paragraph indentation are linear even for very large chapters.
            while (true) {
                while (a < storage.length && storage[a].isWhitespace()) a++
                while (b < display.length && display[b].isWhitespace()) b++
                if (a == storage.length || b == display.length || storage[a] != display[b]) break
                matches.add(a++, b++)
            }
            if (a != storage.length || b != display.length) {
                matches.clear()
                if (!semantic(storage, display, matches)) {
                    matches.clear()
                    match(storage, 0, storage.length, display, 0, display.length, matches)
                }
            }
            var previousA = 0; var previousB = 0
            for (index in 0 until matches.size) {
                val x = matches.a(index); val y = matches.b(index)
                gap(forward, previousA, x, previousB, y)
                gap(ends, previousA, x, previousB, y, true)
                gap(backward, previousB, y, previousA, x)
                forward[x] = y; backward[y] = x
                previousA = x + 1; previousB = y + 1
            }
            gap(forward, previousA, storage.length, previousB, display.length)
            gap(ends, previousA, storage.length, previousB, display.length, true)
            gap(backward, previousB, display.length, previousA, storage.length)
            forward[storage.length] = display.length; backward[display.length] = storage.length
            return TextReaderPositionMap(forward, backward, ends)
        }

        private fun gap(map: IntArray, start: Int, end: Int, target: Int, targetEnd: Int, left: Boolean = false) {
            for (i in start..end) map[i] = if (end == start) if (left) target else targetEnd
                else target + ((i - start).toLong() * (targetEnd - target) / (end - start)).toInt()
        }

        private fun formatting(char: Char) = char.isWhitespace() || char == '\u200c' || char == '\u200d' || char == '\ufeff'
        /** Entities and formatting have source ranges of their own, rather than consuming the diff budget. */
        private fun semantic(a: String, b: String, matches: Matches): Boolean {
            var x = 0; var y = 0
            while (x < a.length) {
                if (formatting(a[x])) { x++; continue }
                val end = if (a[x] == '&') a.indexOf(';', x + 1).takeIf { it in (x + 2)..minOf(a.lastIndex, x + 34) } else null
                val source = if (end != null) a.substring(x, end + 1) else a[x].toString()
                val decoded = if (end != null) org.apache.commons.text.StringEscapeUtils.unescapeHtml4(source) else source
                if (decoded != source && decoded.length <= source.length) {
                    for (i in decoded.indices) {
                        if (formatting(decoded[i])) continue
                        while (y < b.length && formatting(b[y])) y++
                        if (y >= b.length || decoded[i] != b[y]) return false
                        matches.add(x + i, y++)
                    }
                    x += source.length
                } else {
                    while (y < b.length && formatting(b[y])) y++
                    if (y >= b.length || a[x] != b[y]) return false
                    matches.add(x++, y++)
                }
            }
            while (y < b.length && formatting(b[y])) y++
            return y == b.length
        }

        /** Bounded Myers alignment; whole replacement regions preserve their relative position. */
        private fun match(a: String, a0: Int, aEnd: Int, b: String, b0: Int, bEnd: Int,
            matches: Matches) {
            var startA = a0; var startB = b0
            while (startA < aEnd && startB < bEnd && a[startA] == b[startB]) matches.add(startA++, startB++)
            var endA = aEnd; var endB = bEnd
            while (endA > startA && endB > startB && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
            val n = endA - startA; val m = endB - startB
            val limit = minOf(n + m, 512)
            if (n > 0 && m > 0 && kotlin.math.abs(n - m) <= limit) {
                val offset = limit + 1
                val v = IntArray(2 * limit + 3) { -1 }; v[offset + 1] = 0
                val trace = ArrayList<IntArray>()
                var solved = -1
                search@ for (d in 0..limit) {
                    trace += v.copyOf()
                    for (k in -d..d step 2) {
                        val slot = offset + k
                        var x = if (k == -d || k != d && v[slot - 1] < v[slot + 1]) v[slot + 1] else v[slot - 1] + 1
                        var y = x - k
                        while (x < n && y < m && a[startA + x] == b[startB + y]) { x++; y++ }
                        v[slot] = x
                        if (x >= n && y >= m) { solved = d; break@search }
                    }
                }
                if (solved >= 0) {
                    var x = n; var y = m
                    val reversed = Matches()
                    for (d in solved downTo 0) {
                        val old = trace[d]; val k = x - y
                        val previousK = if (k == -d || k != d && old[offset + k - 1] < old[offset + k + 1]) k + 1 else k - 1
                        val previousX = old[offset + previousK].coerceAtLeast(0)
                        val previousY = previousX - previousK
                        while (x > previousX && y > previousY && x > 0 && y > 0) reversed.add(startA + --x, startB + --y)
                        x = previousX; y = previousY
                    }
                    for (i in reversed.size - 1 downTo 0) matches.add(reversed.a(i), reversed.b(i))
                }
            }
            while (endA < aEnd && endB < bEnd) matches.add(endA++, endB++)
        }
    }
}
