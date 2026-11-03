package io.legado.app.help.source

import androidx.collection.LruCache
import io.legado.app.utils.InfoMap

/** Shared explore-control state used by discovery rendering and source requests. */
object ExploreInfoMapCache {
    private val maps = LruCache<String, InfoMap>(99)

    @Synchronized
    fun get(sourceUrl: String): InfoMap = maps[sourceUrl]
        ?: InfoMap(sourceUrl).also { maps.put(sourceUrl, it) }

    @Synchronized
    fun saveNeeded() {
        maps.snapshot().values.filter(InfoMap::needSave).forEach(InfoMap::saveNow)
    }
}
