package io.legado.app.service

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.CacheBookRef
import io.legado.app.data.repository.transferBookKey
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The remaining legacy text host submits the same immutable command as Compose readers. */
internal fun Context.enqueueReaderDownload(book: Book,start: Int,end: Int) {
    val application=applicationContext
    val ref=CacheBookRef(book.bookUrl,transferBookKey(book))
    val component=EntryPointAccessors.fromApplication(application,BookTransferEntryPoint::class.java)
    component.transferRuntime().cleanupScope.launch {
        try { component.downloadCommands().start(ref,start,end) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { AppLog.put("Reader download request failed",error); application.toastOnUi(R.string.cache_operation_failed) }
    }
}
