package io.legado.app.data.repository

import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookIntroRepository @Inject constructor() {
    internal suspend fun parse(content: String, baseUrl: String) = withContext(Default) { BookIntroParser.parse(content, baseUrl) }
}
