package io.legado.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.availableBookGroupId
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface BookGroupDao {

    @Query("select * from book_groups where groupId = :id")
    fun getByID(id: Long): BookGroup?

    @Query("select * from book_groups where groupName = :groupName")
    fun getByName(groupName: String): BookGroup?

    @Query("select * from book_groups where generation = :generation")
    fun getByGeneration(generation: String): BookGroup?

    @Query("SELECT * FROM book_groups ORDER BY `order`")
    fun flowAll(): Flow<List<BookGroup>>

    @get:Query(
        """
        with const as (SELECT coalesce(sum(groupId), 0) sumGroupId FROM book_groups where groupId > 0 OR groupId = ${Long.MIN_VALUE})
        SELECT book_groups.* FROM book_groups join const 
        where show > 0 
        and (
            ((groupId >= 0 OR groupId = ${Long.MIN_VALUE}) and exists (select 1 from books where (`group` & book_groups.groupId) != 0))
            or groupId = -1
            or (groupId = -2 and exists (select 1 from books where type & ${BookType.local} > 0))
            or (groupId = -3 and exists (select 1 from books where type & ${BookType.audio} > 0))
            or (groupId = -6 and exists (select 1 from books where type & ${BookType.video} > 0))
            or (groupId = -11 and exists (select 1 from books where type & ${BookType.updateError} > 0))
            or (groupId = -4 
                and exists (
                    select 1 from books 
                    where type & ${BookType.audio} = 0
                    and type & ${BookType.video} = 0
                    and type & ${BookType.local} = 0
                    and const.sumGroupId & `group` = 0
                )
            )
            or (groupId = -5
                and exists (
                    select 1 from books 
                    where type & ${BookType.audio} = 0
                    and type & ${BookType.video} = 0
                    and type & ${BookType.local} > 0
                    and const.sumGroupId & `group` = 0
                )
            )
        )
        ORDER BY `order`"""
    )
    val show: LiveData<List<BookGroup>>

    @Query("SELECT * FROM book_groups where groupId >= 0 OR groupId = ${Long.MIN_VALUE} ORDER BY `order`")
    fun flowSelect(): Flow<List<BookGroup>>

    @get:Query("SELECT coalesce(sum(groupId), 0) FROM book_groups where groupId >= 0 OR groupId = ${Long.MIN_VALUE}")
    val idsSum: Long

    @get:Query("SELECT MAX(`order`) FROM book_groups where groupId >= 0 OR groupId = ${Long.MIN_VALUE}")
    val maxOrder: Int

    @get:Query("SELECT * FROM book_groups ORDER BY `order`")
    val all: List<BookGroup>

    @get:Query("select count(*) < 63 from book_groups where groupId > 0")
    val canAddGroup: Boolean

    @Query("update book_groups set show = 1 where groupId = :groupId")
    fun enableGroup(groupId: Long)

    @Query("update book_groups set show = :visible where groupId = :id")
    fun setVisible(id: Long, visible: Boolean)

    @Query("update book_groups set `order` = :order where groupId = :id")
    fun setOrder(id: Long, order: Int)

    @Query("select groupName from book_groups where (groupId > 0 OR groupId = ${Long.MIN_VALUE}) and (groupId & :id) != 0")
    fun getGroupNames(id: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRows(vararg bookGroup: BookGroup)

    @androidx.room.Transaction
    fun insert(vararg bookGroup: BookGroup) {
        bookGroup.forEach { group -> insertRows(normalizeGeneration(group, false)) }
    }

    @Update
    fun updateRows(vararg bookGroup: BookGroup)

    @androidx.room.Transaction
    fun update(vararg bookGroup: BookGroup) {
        bookGroup.forEach { group -> updateRows(normalizeGeneration(group, true)) }
    }

    fun normalizeGeneration(group: BookGroup, preserveExisting: Boolean): BookGroup {
        val generation = group.generation?.takeIf(String::isNotBlank)
        val owner = generation?.let(::getByGeneration)
        val normalized = if (generation != null && (owner == null || owner.groupId == group.groupId)) generation
        else if (preserveExisting) getByID(group.groupId)?.generation?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        else UUID.randomUUID().toString()
        return group.copy(generation = normalized)
    }

    @Delete
    fun delete(vararg bookGroup: BookGroup)

    fun isInRules(id: Long): Boolean {
        if (id < 0) {
            return true
        }
        return id and (id - 1) == 0L
    }

    fun getUnusedId(): Long {
        return checkNotNull(availableBookGroupId(all.map { it.groupId })) { "The limit of 63 custom groups has been reached." }
    }
}
