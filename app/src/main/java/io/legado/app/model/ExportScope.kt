package io.legado.app.model

/** Chapter numbers are one based in the UI and validated before any range is expanded. */
internal fun parseExportScope(expression: String,chapterCount: Int): Set<Int> {
    require(chapterCount>0 && expression.isNotBlank() && expression.length<=100_000) { "Invalid export range" }
    val selected=java.util.BitSet(chapterCount)
    val pattern=Regex("""(\d+)(?:\s*-\s*(\d+))?""")
    expression.split(',').forEach { part ->
        val match=checkNotNull(pattern.matchEntire(part.trim())) { "Invalid export range" }
        val first=match.groupValues[1].toIntOrNull()
        val last=if(match.groupValues[2].isEmpty()) first else match.groupValues[2].toIntOrNull()
        require(first!=null && last!=null && first in 1..chapterCount && last in first..chapterCount) { "Export range is outside the book" }
        selected.set(first-1,last)
    }
    val result=linkedSetOf<Int>()
    var index=selected.nextSetBit(0)
    while(index>=0) { result.add(index); index=selected.nextSetBit(index+1) }
    check(result.isNotEmpty())
    return java.util.Collections.unmodifiableSet(result)
}
