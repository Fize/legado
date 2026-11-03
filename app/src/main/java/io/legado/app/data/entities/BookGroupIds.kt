package io.legado.app.data.entities

/** Reserve the sign bit for existing legacy data; new groups use the 63 positive bits. */
fun availableBookGroupId(ids: Iterable<Long>): Long? {
    val occupied = ids.filter { it > 0 || it == Long.MIN_VALUE }.fold(0L) { mask, id -> mask or id }
    return (0..62).asSequence().map { 1L shl it }.firstOrNull { occupied and it == 0L }
}
