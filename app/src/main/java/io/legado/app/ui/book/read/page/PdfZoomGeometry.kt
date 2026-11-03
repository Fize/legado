package io.legado.app.ui.book.read.page

/** Keep a spread pinch over paper when fitted portrait pages leave a wide empty gutter. */
internal fun resolvePdfSpreadFocusX(
    focusX: Float,
    scale: Float,
    offsetX: Float,
    pageRanges: List<ClosedFloatingPointRange<Float>>,
): Float {
    if (!focusX.isFinite() || !scale.isFinite() || scale <= 0 || !offsetX.isFinite()) return focusX
    val x = (focusX - offsetX) / scale
    val ranges = pageRanges.filter { it.start.isFinite() && it.endInclusive.isFinite() && it.endInclusive > it.start }
    if (ranges.size < 2 || ranges.any { x in it }) return focusX
    val left = ranges.filter { it.endInclusive < x }.maxByOrNull { it.endInclusive } ?: return focusX
    val right = ranges.filter { it.start > x }.minByOrNull { it.start } ?: return focusX
    val nearest = if (x - left.endInclusive <= right.start - x) left else right
    return ((nearest.start + nearest.endInclusive) / 2) * scale + offsetX
}
