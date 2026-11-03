package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.getInt
import io.legado.app.utils.putInt
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ReadRecordContent(
    val allRecords: List<ReadRecordShow>,
    val visibleRecords: List<ReadRecordShow>,
    val booksByIdentity: Map<Pair<String, String>, Book>,
)

data class ReadRecordSettings(
    val sortMode: Int,
    val enabled: Boolean,
    val simpleLayout: Boolean,
    val useDays: Boolean,
    val showSeconds: Boolean,
    val fixedCard: Boolean,
)

@Singleton
class ReadRecordRepository @Inject constructor() {

    fun settings() = ReadRecordSettings(
        sortMode = LocalConfig.getInt("readRecordSort"),
        enabled = AppConfig.enableReadRecord,
        simpleLayout = AppConfig.readRecordSimpleLayout,
        useDays = AppConfig.readRecordUseDays,
        showSeconds = AppConfig.readRecordShowSeconds,
        fixedCard = AppConfig.readRecordFixedCard,
    )

    fun setSortMode(value: Int) = LocalConfig.putInt("readRecordSort", value)
    fun setEnabled(value: Boolean) { AppConfig.enableReadRecord = value }
    fun setSimpleLayout(value: Boolean) { AppConfig.readRecordSimpleLayout = value }
    fun setUseDays(value: Boolean) { AppConfig.readRecordUseDays = value }
    fun setShowSeconds(value: Boolean) { AppConfig.readRecordShowSeconds = value }
    fun setFixedCard(value: Boolean) { AppConfig.readRecordFixedCard = value }

    suspend fun load(query: String, sortMode: Int): ReadRecordContent = withContext(Dispatchers.IO) {
        val books = appDb.bookDao.all
            .associateBy { it.name to it.author }
        val all = appDb.readRecordDao.allShow
        val filtered = if (query.isBlank()) all else appDb.readRecordDao.search(query)
        val sorted = when (sortMode) {
            1 -> filtered.sortedByDescending(ReadRecordShow::readTime)
            2 -> filtered.sortedByDescending(ReadRecordShow::lastRead)
            else -> filtered.sortedWith { first, second ->
                first.bookName.cnCompare(second.bookName).takeIf { it != 0 }
                    ?: first.displayAuthor.cnCompare(second.displayAuthor)
            }
        }
        ReadRecordContent(all, sorted, books)
    }

    suspend fun findBook(record: ReadRecordShow): Book? = withContext(Dispatchers.IO) {
        appDb.bookDao.findByName(record.bookName)
            .filter { it.author == record.author }
            .maxByOrNull(Book::durChapterTime)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        appDb.readRecordDao.clear()
        ReadRecordCoverCache.prune()
    }

    suspend fun delete(record: ReadRecordShow) = withContext(Dispatchers.IO) {
        appDb.readRecordDao.deleteByBook(record.bookName, record.author)
        ReadRecordCoverCache.prune()
    }

    suspend fun removeLegacyAuthor(record: ReadRecordShow, author: String) = withContext(Dispatchers.IO) {
        appDb.readRecordDao.removeLegacyAuthor(record.bookName, record.author, author)
        ReadRecordCoverCache.prune()
    }
}
