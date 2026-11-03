package io.legado.app.ui.book.info

import androidx.appcompat.app.AppCompatActivity
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.login.SourceLoginJsExtensions
import kotlinx.coroutines.currentCoroutineContext

/** The script job belongs to the visible UI rather than the retained ViewModel. */
internal suspend fun runBookInfoScript(
    activity: AppCompatActivity,
    source: BookSource,
    book: Book,
    script: String,
    uiScope: kotlinx.coroutines.CoroutineScope? = null,
    ownerKey: String? = null,
) {
    val extensions = SourceLoginJsExtensions(activity, source).apply { this.uiScope = uiScope; uiOwnerKey = ownerKey }
    runScriptWithContext(currentCoroutineContext()) {
        source.evalJS(script) {
            put("result", null)
            put("java", extensions)
            put("book", book)
        }
    }
}
