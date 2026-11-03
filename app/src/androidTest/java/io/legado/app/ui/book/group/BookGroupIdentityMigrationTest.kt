package io.legado.app.ui.book.group

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.BookGroup
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookGroupIdentityMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "group-migration-${UUID.randomUUID()}"
    @get:Rule val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java, DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun freshDatabaseSeedsHaveStableUniqueIdentitiesAcrossReopen() {
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addLegadoMigrations().addCallback(AppDatabase.dbCallback).build()
        val first = open()
        val identities = try {
            val groups = first.bookGroupDao.all
            assertEquals(7, groups.size)
            assertTrue(groups.all { !it.generation.isNullOrBlank() })
            assertEquals(groups.size, groups.map { it.generation }.toSet().size)
            groups.associate { it.groupId to it.generation }
        } finally { first.close() }
        val reopened = open()
        try {
            assertEquals(identities, reopened.bookGroupDao.all.associate { it.groupId to it.generation })
        } finally { reopened.close() }
    }

    @Test fun upgrading113PreservesGroupsAndMembershipAndAssignsUniquePersistentIdentities() {
        val ids = listOf(1L, 2L, Long.MIN_VALUE, BookGroup.IdAll)
        val membership = Long.MIN_VALUE or 3L
        helper.createDatabase(name, 113).use { database ->
            ids.forEachIndexed { index, id ->
                database.execSQL("INSERT INTO book_groups(groupId, groupName, cover, `order`, enableRefresh, show, bookSort, onlyUpdateRead) VALUES(?, ?, NULL, ?, 1, 1, -1, 0)",
                    arrayOf<Any?>(id, "Group $id", index + 1))
            }
            val values = ContentValues()
            database.query("PRAGMA table_info(books)").use { cursor ->
                while (cursor.moveToNext()) {
                    val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val required = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                    val hasDefault = !cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))
                    if (required && !hasDefault) when (cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase()) {
                        "TEXT" -> values.put(column, "")
                        "REAL" -> values.put(column, 0.0)
                        "BLOB" -> values.put(column, ByteArray(0))
                        else -> values.put(column, 0L)
                    }
                }
            }
            values.put("bookUrl", "migration://book")
            values.put("name", "Retained book")
            values.put("group", membership)
            val entries = values.valueSet().map { it.key to it.value }
            database.execSQL("INSERT INTO books (${entries.joinToString { "`${it.first}`" }}) VALUES (${entries.joinToString { "?" }})",
                entries.map { it.second }.toTypedArray())
        }
        helper.runMigrationsAndValidate(name, 114, true, DatabaseMigrations.migration_113_114).use { database ->
            database.query("SELECT `group` FROM books WHERE bookUrl = 'migration://book'").use {
                assertTrue(it.moveToFirst()); assertEquals(membership, it.getLong(0))
            }
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addLegadoMigrations().build()
        try {
            val groups = database.bookGroupDao.all
            assertEquals(ids, groups.map { it.groupId })
            assertEquals(ids.map { "Group $it" }, groups.map { it.groupName })
            assertTrue(groups.all { !it.generation.isNullOrBlank() })
            assertEquals(ids.size, groups.map { it.generation }.toSet().size)
            val oldGeneration = database.bookGroupDao.getByID(1)!!.generation
            database.bookGroupDao.insert(BookGroup(1, "Old backup", generation = null))
            assertNotEquals(oldGeneration, database.bookGroupDao.getByID(1)!!.generation)
            assertEquals(ids.size, database.bookGroupDao.all.size)
        } finally { database.close() }
    }
}
