package io.legado.app.help.storage

import io.legado.app.data.AppDatabase

/** The enclosing restore transaction also rolls this retirement back if restore fails. */
internal fun retireBookTransfersForRestore(db: AppDatabase) {
    check(db.inTransaction())
    val dao = db.bookTransferDao
    dao.active("download").forEach { dao.update(it.copy(phase = "interrupted", owner = "")) }
    dao.active("export").forEach { dao.update(it.copy(phase = "interrupted", owner = "")) }
    // An interrupted batch is resumable by the OS. Retire its old dispatch permanently;
    // explicit task retry creates a new batch and remains available after recovery.
    dao.unfinishedTransferBatches().forEach { dao.update(it.copy(phase = "cancelled", owner = "")) }
}
