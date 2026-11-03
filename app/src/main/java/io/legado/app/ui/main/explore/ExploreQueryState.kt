package io.legado.app.ui.main.explore

internal const val GROUP_QUERY_PREFIX = "group:"

internal fun exploreGroupFromQuery(query: CharSequence?): String? =
    query?.toString()
        ?.takeIf { it.startsWith(GROUP_QUERY_PREFIX) }
        ?.removePrefix(GROUP_QUERY_PREFIX)

internal fun isExploreAllQuery(query: CharSequence?): Boolean =
    exploreGroupFromQuery(query) == null

internal fun selectedExploreGroup(
    query: CharSequence?,
    groups: Set<String>,
): String? = exploreGroupFromQuery(query)?.takeIf(groups::contains)

internal fun exploreScrollState(
    pending: Pair<Int, Int>?,
    position: Int,
    currentTop: Int?,
): Pair<Pair<Int, Int>?, Int> {
    val offset = pending?.takeIf { it.first == position }?.second ?: currentTop ?: 0
    val nextPending = if (pending?.first == position) null else position to offset
    return nextPending to offset
}
