package io.legado.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.readText
import io.legado.app.help.http.newCallResponse
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

enum class HighlightRuleImportStatus {
    NEW,
    UPDATE,
    EXISTING,
}

data class HighlightRuleImportItem(
    val rule: HighlightRule,
    val status: HighlightRuleImportStatus,
)

internal fun parseHighlightRuleFile(text: String): List<HighlightRule> {
    val parsed = GSONStrict.fromJsonObject<JsonElement>(text).getOrThrow()
    val root = if (parsed.isJsonArray) {
        parsed.asJsonArray.forEach { element ->
            require(element.isJsonObject)
            val rule = element.asJsonObject
            require(rule.has("pattern") && rule.has("style") && rule.has("uuid"))
            require(!rule.has("replacement"))
        }
        JsonObject().apply {
            addProperty("type", HighlightRuleFile.TYPE)
            add("rules", parsed)
        }
    } else {
        require(parsed.isJsonObject)
        parsed.asJsonObject
    }
    val rules = root.get("rules")
    require(rules != null && rules.isJsonArray)
    rules.asJsonArray.forEach { element ->
        require(element.isJsonObject)
        val rule = element.asJsonObject
        val uuid = rule.get("uuid")
        require(uuid != null && !uuid.isJsonNull && uuid.isJsonPrimitive && uuid.asJsonPrimitive.isString)
        listOf("name", "pattern", "style").forEach { field ->
            rule.get(field)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
        }
        rule.get("scope")?.let { value ->
            require(value.isJsonNull || value.isJsonPrimitive && value.asJsonPrimitive.isString)
        }
        listOf("isRegex", "isEnabled", "applyToTitle", "applyToBody").forEach { field ->
            rule.get(field)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }
        }
        listOf("id", "timeoutMillisecond").forEach { field ->
            rule.get(field)?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber &&
                    runCatching { it.asBigDecimal.longValueExact() }.isSuccess)
            }
        }
        rule.get("order")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber &&
                runCatching { it.asBigDecimal.intValueExact() }.isSuccess)
        }
    }
    return validateHighlightRuleFile(GSONStrict.fromJson(root, HighlightRuleFile::class.java))
}

internal fun validateHighlightRuleFile(file: HighlightRuleFile): List<HighlightRule> {
    require(file.type == HighlightRuleFile.TYPE)
    val rules = file.rules ?: error("Missing rules")
    val uuids = hashSetOf<String>()
    return rules.map { nullableRule ->
        val rule = nullableRule ?: error("Invalid rule")
        @Suppress("USELESS_CAST")
        val rawUuid = (rule.uuid as String?).orEmpty()
        val uuid = UUID.fromString(rawUuid).toString()
        require(uuid.equals(rawUuid, ignoreCase = true))
        require(uuids.add(uuid))
        rule.uuid = uuid
        rule.scope = rule.scope?.ifBlank { null }
        rule.normalizeForRestore()
        require(rule.isValid())
        rule
    }
}

private fun highlightImportComparison(rule: HighlightRule)=GSON.toJsonTree(rule.copy(id=0L,order=0).also {
    // A blank style renders as the default. Other normalization can change actual rule behavior.
    if(it.style.isBlank()) it.applyStyle(io.legado.app.help.HighlightStyle())
})

internal fun compareImportedHighlightRules(
    imported: List<HighlightRule>,
    local: List<HighlightRule>,
): List<HighlightRuleImportItem> {
    val localByUuid = local.associateBy { it.uuid.lowercase() }
    return imported.map { rule ->
        val existing = localByUuid[rule.uuid.lowercase()]
        val status = when {
            existing == null -> HighlightRuleImportStatus.NEW
            highlightImportComparison(existing) == highlightImportComparison(rule) -> HighlightRuleImportStatus.EXISTING
            else -> HighlightRuleImportStatus.UPDATE
        }
        HighlightRuleImportItem(rule, status)
    }
}

internal fun highlightImportSourceKey(source: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }

internal fun newHighlightImportRequest(id: String,source: String,parent: String?=null): io.legado.app.data.entities.HighlightRuleImportSession {
    require(UUID.fromString(id).toString()==id)
    parent?.let { require(UUID.fromString(it).toString()==it) }
    require(source.isNotBlank() && source.length<=16_384)
    return io.legado.app.data.entities.HighlightRuleImportSession(id,source,highlightImportSourceKey(source),parent=parent)
}

data class HighlightImportOwned(val session: io.legado.app.data.entities.HighlightRuleImportSession,
    val choices: List<io.legado.app.data.dao.HighlightImportChoice>)

@Singleton
class HighlightRuleImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    private val dao get()=database.highlightRuleImportDao
    private fun owned(id: String,owner: String)=checkNotNull(dao.get(id)).also {
        check(it.owner==owner) { "Import entry changed" }
        if(it.phase !in listOf("completed","cancelled")) it.parent?.let { parent -> check(database.highlightRuleManagerDao.get(parent)?.closed==false) { "Import parent closed" } }
    }
    private fun previewName(rule: HighlightRule): String {
        val name=rule.getDisplayName()
        val end=name.offsetByCodePoints(0,minOf(512,name.codePointCount(0,name.length)))
        return name.substring(0,end)
    }
    private fun output(session: io.legado.app.data.entities.HighlightRuleImportSession): HighlightImportOwned {
        require(session.phase in listOf("loading","ready","failed","completed","cancelled"))
        val choices=dao.choices(session.id)
        choices.forEach { require(it.status in HighlightRuleImportStatus.entries.map { status -> status.name }) }
        return HighlightImportOwned(session,java.util.Collections.unmodifiableList(choices))
    }
    private fun claim(id: String,source: String,owner: String,epoch: Long,parent: String?): io.legado.app.data.entities.HighlightRuleImportSession {
        val input=newHighlightImportRequest(id,source,parent)
        require(UUID.fromString(owner).toString()==owner && epoch>0)
        return database.runInTransaction<io.legado.app.data.entities.HighlightRuleImportSession> {
            val old=dao.get(id) ?: input.also {
                parent?.let { check(database.highlightRuleManagerDao.get(it)?.closed==false) { "Import parent no longer exists" } }
                dao.insert(it)
            }
            check(old.parent==parent) { "Import parent changed" }
            check(old.sourceKey==input.sourceKey) { "Import source changed" }
            check(old.process!=HighlightManagerOwnership.process || old.epoch<epoch || old.epoch==epoch && old.owner==owner) { "Stale import claim" }
            old.copy(owner=owner,process=HighlightManagerOwnership.process,epoch=epoch).also(dao::update)
        }
    }
    suspend fun open(id: String,source: String,owner: String,epoch: Long,parent: String?=null): HighlightImportOwned=withContext(Dispatchers.IO) {
        val claimed=claim(id,source,owner,epoch,parent)
        if(claimed.phase=="loading") load(id,owner) else database.runInTransaction<HighlightImportOwned> { output(owned(id,owner)) }
    }
    suspend fun cancel(id: String,source: String,owner: String,epoch: Long,parent: String?=null): HighlightImportOwned=withContext(Dispatchers.IO) {
        claim(id,source,owner,epoch,parent)
        finish(id,owner,false)
    }
    private suspend fun load(id: String,owner: String): HighlightImportOwned {
        val request=owned(id,owner)
        try {
            val text=if(request.source.startsWith("http://") || request.source.startsWith("https://")) {
                io.legado.app.help.http.okHttpClient.newCallResponse {
                    if(request.source.endsWith("#requestWithoutUA")) {
                        url(request.source.substringBeforeLast("#requestWithoutUA")); header(io.legado.app.constant.AppConst.UA_NAME,"null")
                    } else url(request.source)
                }.use { check(it.isSuccessful) { "Import HTTP request failed" }; it.body.string() }
            } else request.source.toUri().readText(context)
            val rules=parseHighlightRuleFile(text)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return database.runInTransaction<HighlightImportOwned> {
                val current=owned(id,owner); check(current.phase=="loading" && current.revision==request.revision)
                val local=database.highlightRuleDao.all.associateBy { it.uuid.lowercase() }
                val rows=compareImportedHighlightRules(rules,local.values.toList()).mapIndexed { index,item ->
                    io.legado.app.data.entities.HighlightRuleImportRow(id,item.rule.uuid,index,previewName(item.rule),item.status.name,
                        local[item.rule.uuid]?.let(::highlightRuleIdentity),GSON.toJson(item.rule),item.status!=HighlightRuleImportStatus.EXISTING)
                }
                dao.clear(id); dao.insertRows(rows)
                current.copy(phase="ready",revision=current.revision+1).also(dao::update).let(::output)
            }
        } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(error: Exception) {
            database.runInTransaction {
                val current=owned(id,owner)
                if(current.phase=="loading" && current.revision==request.revision) dao.update(current.copy(phase="failed",revision=current.revision+1))
            }
            throw error
        }
    }
    suspend fun select(id: String,owner: String,revision: Long,uuid: String?,selected: Boolean): HighlightImportOwned=withContext(Dispatchers.IO) {
        database.runInTransaction<HighlightImportOwned> {
            val old=owned(id,owner); check(old.phase=="ready" && revision>old.revision)
            if(uuid==null) dao.selectAll(id,selected) else check(dao.select(id,uuid,selected)==1)
            old.copy(revision=revision).also(dao::update).let(::output)
        }
    }
    suspend fun retry(id: String,owner: String): HighlightImportOwned=withContext(Dispatchers.IO) {
        val loading=database.runInTransaction<Boolean> {
            val old=owned(id,owner)
            if(old.phase=="failed") { dao.update(old.copy(phase="loading",revision=old.revision+1)); true }
            else { check(old.phase=="ready"); false }
        }
        if(loading) load(id,owner)
        else database.runInTransaction<HighlightImportOwned> {
            val current=owned(id,owner); check(current.phase=="ready")
            // Review conflicts against the durable candidates, never reread a changed provider file.
            val rows=dao.rows(id); val local=database.highlightRuleDao.all.associateBy { it.uuid.lowercase() }
            val refreshed=rows.map { row ->
                val rule=GSON.fromJson(row.value,HighlightRule::class.java)
                val status=compareImportedHighlightRules(listOf(rule),listOfNotNull(local[row.uuid])).single().status
                row.copy(original=local[row.uuid]?.let(::highlightRuleIdentity),status=status.name)
            }
            dao.clear(id); dao.insertRows(refreshed)
            current.copy(revision=current.revision+1).also(dao::update).let(::output)
        }
    }
    suspend fun finish(id: String,owner: String,save: Boolean): HighlightImportOwned=withContext(Dispatchers.IO) {
        database.runInTransaction<HighlightImportOwned> {
            val current=owned(id,owner)
            if(current.phase in listOf("completed","cancelled")) return@runInTransaction output(current)
            if(save) {
                check(current.phase=="ready")
                val rows=dao.selected(id); check(rows.isNotEmpty())
                val local=database.highlightRuleDao.all.associateBy { it.uuid.lowercase() }
                val rules=rows.map { row ->
                    check(local[row.uuid]?.let(::highlightRuleIdentity)==row.original) { "A previewed rule changed" }
                    parseHighlightRuleFile(GSON.toJson(HighlightRuleFile(type=HighlightRuleFile.TYPE,
                        rules=listOf(GSON.fromJson(row.value,HighlightRule::class.java))))).single()
                }
                database.highlightRuleDao.importRules(rules)
            }
            dao.clear(id)
            current.copy(phase=if(save) "completed" else "cancelled",source="",revision=current.revision+1).also(dao::update).let(::output)
        }
    }
}
