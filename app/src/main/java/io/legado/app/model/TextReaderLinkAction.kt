package io.legado.app.model

import java.net.URI
import java.net.URLDecoder
import io.legado.app.model.localBook.EpubResourceAddress

sealed interface TextReaderLinkAction {
    data class External(val target: String) : TextReaderLinkAction
    data class Jump(val position: Int) : TextReaderLinkAction
    data class Note(val anchor: TextReaderAnchor) : TextReaderLinkAction
    data class Resource(val address: EpubResourceAddress, val noteReference: Boolean) : TextReaderLinkAction
    data object Missing : TextReaderLinkAction
    data object Unsupported : TextReaderLinkAction
}

/** Only a fragment in this document can address its anchors; other schemes never become commands. */
fun TextReaderDocument.linkAction(link: TextReaderLink, chapterUrl: String): TextReaderLinkAction {
    val target = link.target.trim()
    if (target.startsWith("epub:/", true)) {
        val address = EpubResourceAddress.resolve("resource", target) ?: return TextReaderLinkAction.Missing
        val canUseRoot = !link.noteReference || completeResources
        val anchor = anchors.firstOrNull { it.resource == address.path && it.id == address.fragment.orEmpty() &&
            (it.id.isNotEmpty() || canUseRoot) }
            ?: anchors.firstOrNull { canUseRoot && it.resource == address.path && it.id.isEmpty() && address.fragment.equals("top",true) }
        if (anchor != null) return if (anchor.note || link.noteReference) {
            if (anchor.end <= anchor.start || text.substring(anchor.start, anchor.end).isBlank()) TextReaderLinkAction.Missing
            else TextReaderLinkAction.Note(anchor)
        } else TextReaderLinkAction.Jump(position(anchor.start))
        return if (completeResources && address.path in resources) {
            if (address.fragment.isNullOrEmpty()) TextReaderLinkAction.Jump(0) else TextReaderLinkAction.Missing
        } else TextReaderLinkAction.Resource(address, link.noteReference)
    }
    val hash = target.indexOf('#')
    val sameDocument = hash == 0 || hash > 0 && chapterUrl.isNotBlank() && runCatching {
        val base = URI(chapterUrl)
        fun withoutFragment(uri: URI) = URI(uri.toASCIIString().substringBefore('#')).normalize()
        withoutFragment(base.resolve(URI(target.substring(0, hash)))) == withoutFragment(base)
    }.getOrDefault(false)
    if (sameDocument) {
        val id = runCatching { URLDecoder.decode(target.substring(hash + 1).replace("+", "%2B"), "UTF-8") }.getOrNull()
            ?: return TextReaderLinkAction.Missing
        val anchor = anchors.firstOrNull { it.id == id }
        if (anchor == null) return if (id.isEmpty() || id.equals("top", true)) TextReaderLinkAction.Jump(0) else TextReaderLinkAction.Missing
        return if (anchor.note || link.noteReference) {
            if (anchor.end <= anchor.start || text.substring(anchor.start, anchor.end).isBlank()) TextReaderLinkAction.Missing
            else TextReaderLinkAction.Note(anchor)
        } else TextReaderLinkAction.Jump(position(anchor.start))
    }
    return if (target.startsWith("https://", true) || target.startsWith("http://", true)) TextReaderLinkAction.External(target)
        else TextReaderLinkAction.Unsupported
}
