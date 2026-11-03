package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ReaderAnnotationDraft

@Dao
interface ReaderAnnotationDao {
    @Query("SELECT * FROM reader_annotation_drafts WHERE readerId=:readerId")
    fun draft(readerId: String): ReaderAnnotationDraft?
    @Insert(onConflict=OnConflictStrategy.REPLACE)
    fun put(draft: ReaderAnnotationDraft)
    @Query("DELETE FROM reader_annotation_drafts WHERE readerId=:readerId AND id=:id")
    fun discard(readerId: String,id: String): Int
}
