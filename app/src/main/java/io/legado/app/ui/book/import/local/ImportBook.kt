package io.legado.app.ui.book.import.local

import io.legado.app.utils.FileDoc
import io.legado.app.data.entities.Book

data class ImportBook(
    val file: FileDoc,
    var isOnBookShelf: Boolean,
    val preview: Book? = null,
) {
    val name get() = file.name
    val isDir get() = file.isDir
    val size get() = file.size
    val lastModified get() = file.lastModified
}
