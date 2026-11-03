package io.legado.app.data.entities

/** Room projections; no introduction, parser variables or content are loaded for the list. */
data class CacheShelfRecord(val bookUrl: String,val origin: String,val originName: String,val name: String,
    val author: String,val type: Int,val charset: String?,val tocUrl: String,val totalChapterNum: Int,
    val durChapterIndex: Int,val durChapterTime: Long,val latestChapterTime: Long,val order: Int) {
    fun identityBook()=Book(bookUrl=bookUrl,origin=origin,originName=originName,name=name,author=author,
        type=type,charset=charset,tocUrl=tocUrl)
}
data class CacheCatalogEntry(val url: String,val title: String,val index: Int,val isVolume: Boolean) {
    fun fileName()=BookChapter(url=url,title=title,index=index,isVolume=isVolume).getFileName()
}
