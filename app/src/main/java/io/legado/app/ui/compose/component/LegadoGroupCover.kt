package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.legado.app.data.entities.BookshelfBook

@Composable
fun LegadoGroupCover(customCover: String?, books: List<BookshelfBook>, modifier: Modifier = Modifier) {
    if (!customCover.isNullOrBlank() || books.isEmpty()) {
        LegadoBookCover(customCover, null, null, modifier)
    } else {
        Column(modifier.aspectRatio(3f / 4f)) {
            repeat(2) { row -> Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(2) { column ->
                    val index = row * 2 + column
                    val book = books.getOrNull(index)
                    if (book == null) Spacer(Modifier.weight(1f).fillMaxHeight())
                    else LegadoBookCover(book.displayCover, book.name, book.author,
                        Modifier.weight(1f).fillMaxHeight().testTag("group_cover_$index"), sourceOrigin = book.coverSourceOrigin)
                }
            } }
        }
    }
}
