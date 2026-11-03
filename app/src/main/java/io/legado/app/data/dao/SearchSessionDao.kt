package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Upsert
import androidx.room.Query
import io.legado.app.data.entities.SearchScreenDraft
import io.legado.app.data.entities.SearchSessionSource
import io.legado.app.data.entities.SearchSession
import io.legado.app.data.entities.SearchSessionResult
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchSessionDao {
    @Upsert
    fun saveDraft(draft: SearchScreenDraft)

    @Query("SELECT * FROM search_screen_drafts WHERE id = :id")
    fun draft(id: String): SearchScreenDraft?

    @Upsert
    fun insertSources(sources: List<SearchSessionSource>)

    @Query("SELECT * FROM search_session_sources WHERE sessionId = :id ORDER BY position")
    fun sources(id: String): List<SearchSessionSource>

    @Upsert
    fun insert(session: SearchSession)

    @Query("SELECT * FROM search_sessions WHERE id = :id")
    fun get(id: String): SearchSession?

    @Query("SELECT * FROM search_sessions WHERE id = :id")
    fun observe(id: String): Flow<SearchSession?>

    @Query("SELECT * FROM search_session_results WHERE sessionId = :id ORDER BY position, arrival")
    fun results(id: String): List<SearchSessionResult>

    @Query("SELECT * FROM search_session_results WHERE sessionId = :session AND resultKey = :key AND origin = :origin AND bookUrl = :url LIMIT 1")
    fun variant(session: String, key: String, origin: String, url: String): SearchSessionResult?

    @Query("SELECT coalesce(max(arrival), -1) + 1 FROM search_session_results WHERE sessionId = :session")
    fun nextArrival(session: String): Long

    @Upsert
    fun insertResults(rows: List<SearchSessionResult>)

    @Query("DELETE FROM search_session_results WHERE sessionId = :id")
    fun clearResults(id: String)

    @Query("DELETE FROM search_sessions WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM search_sessions WHERE updatedAt < :cutoff AND id NOT IN (SELECT sessionId FROM search_screen_drafts WHERE sessionId IS NOT NULL AND updatedAt >= :cutoff)")
    fun deleteExpired(cutoff: Long)
}
