package io.legado.app.data.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Per-book cache invalidation. Only books observed by a screen allocate a signal. */
@Singleton
class BookCacheChanges @Inject constructor() {
    private val revisions=ConcurrentHashMap<String,MutableStateFlow<Long>>()
    fun observe(url: String): StateFlow<Long> = revisions.computeIfAbsent(url) { MutableStateFlow(0) }.asStateFlow()
    fun changed(url: String) { revisions[url]?.update { it+1 } }
    fun changedAll() { revisions.values.forEach { value -> value.update { it+1 } } }
}
