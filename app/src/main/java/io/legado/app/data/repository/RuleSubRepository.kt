package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuleSubRepository @Inject constructor() {

    fun observeAll(): Flow<List<RuleSub>> = appDb.ruleSubDao.flowAll().flowOn(IO)

    suspend fun nextOrder(): Int = appDb.ruleSubDao.maxOrder + 1

    suspend fun findByUrl(url: String): RuleSub? = appDb.ruleSubDao.findByUrl(url)

    suspend fun save(ruleSub: RuleSub) = appDb.ruleSubDao.insert(ruleSub)

    suspend fun delete(ruleSub: RuleSub) = appDb.ruleSubDao.delete(ruleSub)
}
