package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource

/** Preserve parser inputs which are intentionally excluded from the Room entity constructor. */
internal fun Book.infoSnapshot(): Book = copy(
    readConfig = readConfig?.let { it.copy(manualReplaceRuleIds = it.manualReplaceRuleIds.orEmpty().toList()) },
).also { snapshot ->
    snapshot.infoHtml = infoHtml
    snapshot.tocHtml = tocHtml
    snapshot.downloadUrls = downloadUrls?.toList()
    snapshot.variableMap.clear()
    snapshot.variableMap.putAll(variableMap)
}

internal fun BookChapter.infoSnapshot(): BookChapter = copy().also { snapshot ->
    snapshot.variableMap.clear()
    snapshot.variableMap.putAll(variableMap)
    snapshot.titleMD5 = titleMD5
}

internal fun BookSource.infoSnapshot(): BookSource = copy(
    ruleExplore = ruleExplore?.copy(),
    ruleSearch = ruleSearch?.copy(),
    ruleBookInfo = ruleBookInfo?.copy(),
    ruleToc = ruleToc?.copy(),
    ruleContent = ruleContent?.copy(),
    ruleReview = ruleReview?.copy(),
)
