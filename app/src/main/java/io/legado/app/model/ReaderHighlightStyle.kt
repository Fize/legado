package io.legado.app.model

import com.google.gson.JsonParser
import io.legado.app.help.HighlightStyle
import io.legado.app.utils.GSON

/** Explicit style changes preserve extension fields; editing a note never rewrites the style. */
fun mergeReaderHighlightStyle(original: String,style: HighlightStyle): String {
    val target=runCatching { JsonParser.parseString(original).asJsonObject }.getOrNull() ?: com.google.gson.JsonObject()
    val normalized=style.normalized()
    val replacement=GSON.toJsonTree(normalized).asJsonObject
    // Gson omits null values, so clear every known channel before applying the new style.
    val nested=mapOf("underline" to listOf("kind","color","width","distance"),"shadow" to listOf("radius","dx","dy","color"),
        "strike" to listOf("color"),"box" to listOf("color"),"emphasis" to listOf("color"))
    nested.forEach { (name,known) ->
        val old=target.get(name); val next=replacement.get(name)
        if(old?.isJsonObject==true && next?.isJsonObject==true) {
            val merged=old.asJsonObject.deepCopy(); known.forEach(merged::remove)
            next.asJsonObject.entrySet().forEach { (key,value) -> merged.add(key,value) }; replacement.add(name,merged)
        }
    }
    listOf("fill","fillShape","textColor","bold","italic","underline","strike","box","emphasis","shadow",
        "fontPath","pillPaddingScale","fontSize","letterSpacing").forEach(target::remove)
    replacement.entrySet().forEach { (key,value) -> target.add(key,value) }
    return GSON.toJson(target)
}
