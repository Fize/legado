package io.legado.app.ui.platformadapter

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element

/** For providers without document-start hooks, execute the bridge before any source script. */
internal fun htmlWithDocumentBridge(html: String, script: String): String = Jsoup.parse(html).apply {
    head().prependChild(Element("script").appendChild(DataNode(script)))
}.outerHtml()
