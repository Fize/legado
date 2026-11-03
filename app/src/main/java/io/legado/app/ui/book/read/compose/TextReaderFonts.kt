package io.legado.app.ui.book.read.compose

import android.content.Context
import android.graphics.Typeface
import androidx.core.net.toUri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import io.legado.app.model.TextReaderTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TextReaderFonts(val body: FontFamily, val title: FontFamily)

/** Loading has no config side effects; an unavailable font falls back without changing the anchor. */
internal suspend fun loadTextReaderFonts(context: Context, typography: TextReaderTypography): TextReaderFonts =
    withContext(Dispatchers.IO) {
        val fallback = when (typography.systemFont) {
            1 -> FontFamily.Serif
            2 -> FontFamily.Monospace
            else -> FontFamily.SansSerif
        }
        fun load(path: String): FontFamily = if (path.isBlank()) fallback else runCatching {
            if (path.startsWith("content://", true)) {
                context.contentResolver.openFileDescriptor(path.toUri(), "r")?.use {
                    Typeface.Builder(it.fileDescriptor).build()
                }
            } else Typeface.createFromFile(path)
        }.getOrNull()?.let(::FontFamily) ?: fallback
        val body = load(typography.bodyFont)
        val title = if (typography.titleFont == typography.bodyFont) body else load(typography.titleFont)
        TextReaderFonts(body, title)
    }

@Composable
internal fun rememberTextReaderFonts(typography: TextReaderTypography): TextReaderFonts {
    val context = LocalContext.current.applicationContext
    val fallback = when (typography.systemFont) {
        1 -> FontFamily.Serif
        2 -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    // Font identity is independent of chapter, color, padding and text weight.
    return key(context, typography.bodyFont, typography.titleFont, typography.systemFont) {
        val value by produceState(TextReaderFonts(fallback, fallback)) {
            value = loadTextReaderFonts(context, typography)
        }
        value
    }
}

@Composable
internal fun rememberTextReaderAnnotationFonts(annotations: List<TextReaderAnnotation>): Map<String,FontFamily> {
    val context=LocalContext.current.applicationContext
    val paths=annotations.map { it.style.resolvedFontPath }.filter { it.isNotBlank() }.distinct().sorted()
    return key(context,paths) {
        val value by produceState<Map<String,FontFamily>>(emptyMap()) {
            value=withContext(Dispatchers.IO) {
                paths.mapNotNull { path ->
                    val typeface=runCatching {
                        if(path.startsWith("content://",true)) context.contentResolver.openFileDescriptor(path.toUri(),"r")?.use { Typeface.Builder(it.fileDescriptor).build() }
                        else Typeface.createFromFile(path)
                    }.getOrNull()
                    typeface?.let { path to FontFamily(it) }
                }.toMap()
            }
        }
        value
    }
}
