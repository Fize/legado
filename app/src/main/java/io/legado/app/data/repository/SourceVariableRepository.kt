package io.legado.app.data.repository

import io.legado.app.help.CacheManager
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The same durable namespace exposed to source JavaScript through BaseSource. */
class SourceVariableRepository @Inject constructor() {
    suspend fun read(key: String): String = withContext(IO) {
        CacheManager.get("sourceVariable_$key").orEmpty()
    }

    suspend fun write(key: String, value: String?) = withContext(IO) {
        if (value == null) CacheManager.delete("sourceVariable_$key")
        else CacheManager.put("sourceVariable_$key", value)
    }
}
