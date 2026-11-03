package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.availableBookGroupId
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

enum class BookGroupError { EmptyName, Limit, Missing, Changed, Protected, Storage }
class BookGroupException(val reason: BookGroupError) : IllegalStateException(reason.name)

data class BookGroupDraft(
    val id: Long? = null,
    val name: String = "",
    val cover: String? = null,
    val sort: Int = -1,
    val refresh: Boolean = true,
    val onlyRead: Boolean = false,
    val importedCover: Boolean = false,
    val generation: String = UUID.randomUUID().toString(),
)

fun BookGroup.isSelectableGroup() = groupId > 0 || groupId == Long.MIN_VALUE

@Singleton
class BookGroupRepository @Inject constructor(
    private val database: AppDatabase,
    @ApplicationContext private val context: Context,
    private val covers: CoverFileRepository = CoverFileRepository(context),
) {
    val groups get() = database.bookGroupDao.flowAll()

    suspend fun save(draft: BookGroupDraft): BookGroup = withContext(IO) {
        val name = draft.name.trim()
        if (name.isEmpty()) throw BookGroupException(BookGroupError.EmptyName)
        if (draft.generation.isBlank()) throw BookGroupException(BookGroupError.Changed)
        database.runInTransaction<BookGroup> {
            val existing = if (draft.id == null) database.bookGroupDao.getByGeneration(draft.generation)
                else database.bookGroupDao.getByID(draft.id) ?: throw BookGroupException(BookGroupError.Missing)
            if (existing != null && existing.generation != draft.generation) throw BookGroupException(BookGroupError.Changed)
            val group = existing ?: run {
                val groups = database.bookGroupDao.all
                if (groups.count { it.groupId > 0 } >= 63) throw BookGroupException(BookGroupError.Limit)
                val id = availableBookGroupId(groups.map { it.groupId })
                    ?: throw BookGroupException(BookGroupError.Limit)
                database.bookDao.clearGroupBit(id, id.inv())
                BookGroup(groupId = id, order = (database.bookGroupDao.all.maxOfOrNull { it.order } ?: 0) + 1, generation = draft.generation)
            }
            val saved = group.copy(groupName = name, cover = draft.cover?.takeIf(String::isNotBlank),
                bookSort = draft.sort.takeIf { it in -1..5 } ?: -1, enableRefresh = draft.refresh, onlyUpdateRead = draft.onlyRead)
            database.bookGroupDao.insert(saved)
            saved
        }
    }

    suspend fun delete(id: Long, generation: String? = null) = withContext(IO) {
        if (id <= 0 && id != Long.MIN_VALUE) throw BookGroupException(BookGroupError.Protected)
        database.runInTransaction {
            val group = database.bookGroupDao.getByID(id) ?: throw BookGroupException(BookGroupError.Missing)
            if (generation != null && group.generation != generation) throw BookGroupException(BookGroupError.Changed)
            database.bookGroupDao.delete(group)
            database.bookDao.clearGroupBit(id, id.inv())
        }
    }

    suspend fun setVisible(id: Long, visible: Boolean, generation: String? = null) = withContext(IO) {
        database.runInTransaction {
            checkGeneration(id, generation)
            database.bookGroupDao.setVisible(id, visible)
        }
    }

    suspend fun move(id: Long, direction: Int, selectionOnly: Boolean, generation: String? = null) = withContext(IO) {
        require(direction == -1 || direction == 1)
        database.runInTransaction {
            checkGeneration(id, generation)
            val all = database.bookGroupDao.all.toMutableList()
            val positions = all.indices.filter { !selectionOnly || all[it].isSelectableGroup() }
            val from = positions.indexOfFirst { all[it].groupId == id }
            val to = from + direction
            if (from >= 0 && to in positions.indices) {
                val a = positions[from]; val b = positions[to]
                val group = all[a]; all[a] = all[b]; all[b] = group
                all.forEachIndexed { index, item -> database.bookGroupDao.setOrder(item.groupId, index + 1) }
            }
        }
    }

    private fun checkGeneration(id: Long, generation: String?) {
        val group = database.bookGroupDao.getByID(id) ?: throw BookGroupException(BookGroupError.Missing)
        if (generation != null && group.generation != generation) throw BookGroupException(BookGroupError.Changed)
    }

    suspend fun importCover(uri: Uri): String = covers.importCover(uri)
    suspend fun coverBitmap(path: String?): Bitmap? = covers.coverBitmap(path)
}
