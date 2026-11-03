package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleDraft
import io.legado.app.help.HighlightStyles
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal fun highlightRuleIdentity(rule: HighlightRule): String {
    val json=GSON.toJson(rule)
    val bytes=ByteArray(Math.multiplyExact(json.length,2))
    json.forEachIndexed { i,c -> bytes[i*2]=(c.code ushr 8).toByte(); bytes[i*2+1]=c.code.toByte() }
    return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
}

internal object HighlightRuleEditorOwnership {
    val process: String=UUID.randomUUID().toString()
    private val counter=java.util.concurrent.atomic.AtomicLong()
    fun next(): Long=counter.incrementAndGet()
}

@Singleton
class HighlightRuleEditorRepository @Inject constructor(private val db: AppDatabase) {
    private fun key(id: String) { require(UUID.fromString(id).toString()==id) }
    fun decode(draft: HighlightRuleDraft): HighlightRule=GSON.fromJson(draft.value,HighlightRule::class.java)
    suspend fun prepare(pattern: String,isRegex: Boolean=false,scope: String?=null,style: String?=null): String=withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString()
        val rule=HighlightRule(name=pattern,pattern=pattern,isRegex=isRegex,scope=scope,style=style ?: GSON.toJson(HighlightStyles.presets.first()))
        db.highlightRuleDraftDao.insert(HighlightRuleDraft(id,null,"",0,null,GSON.toJson(rule)))
        id
    }
    suspend fun open(id: String,ruleId: Long?,owner: String,epoch: Long=HighlightRuleEditorOwnership.next()): HighlightRuleDraft=withContext(Dispatchers.IO) {
        key(id); key(owner); require(epoch>0); require(ruleId==null || ruleId>0)
        db.runInTransaction<HighlightRuleDraft> {
            val existing=db.highlightRuleDraftDao.get(id)
            if(existing!=null) {
                check(existing.ruleId==ruleId) { "Rule editor request changed" }
                check(existing.process!=HighlightRuleEditorOwnership.process || existing.epoch<epoch || existing.epoch==epoch && existing.owner==owner) { "Stale editor claim" }
                existing.copy(owner=owner,process=HighlightRuleEditorOwnership.process,epoch=epoch,updatedAt=System.currentTimeMillis()).also(db.highlightRuleDraftDao::update)
            } else {
                val rule=if(ruleId==null) HighlightRule(style=GSON.toJson(HighlightStyles.presets.first()))
                    else checkNotNull(db.highlightRuleDao.findById(ruleId)) { "Rule no longer exists" }
                HighlightRuleDraft(id,ruleId,owner,0,ruleId?.let { highlightRuleIdentity(rule) },GSON.toJson(rule),process=HighlightRuleEditorOwnership.process,epoch=epoch).also(db.highlightRuleDraftDao::insert)
            }
        }
    }
    suspend fun update(id: String,owner: String,revision: Long,value: HighlightRule): HighlightRuleDraft = mutate(id,owner,revision) { value }
    suspend fun mutate(id: String,owner: String,revision: Long,change: (HighlightRule) -> HighlightRule): HighlightRuleDraft=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightRuleDraft> {
            val draft=checkNotNull(db.highlightRuleDraftDao.get(id))
            check(draft.owner==owner && draft.phase=="editing") { "Rule editor is no longer active" }
            check(revision>draft.revision) { "Stale rule editor input" }
            val previous=decode(draft)
            val value=change(previous)
            check(value.id==previous.id && value.uuid==previous.uuid) { "Rule identity changed" }
            draft.copy(revision=revision,value=GSON.toJson(value),updatedAt=System.currentTimeMillis()).also(db.highlightRuleDraftDao::update)
        }
    }
    suspend fun finish(id: String,owner: String,save: Boolean): HighlightRuleDraft=withContext(Dispatchers.IO) {
        db.runInTransaction<HighlightRuleDraft> {
            val draft=checkNotNull(db.highlightRuleDraftDao.get(id))
            check(draft.owner==owner) { "Rule editor is no longer active" }
            if(draft.phase!="editing") return@runInTransaction draft
            val saved=if(!save) null else {
                val value=decode(draft)
                require(value.isValid()) { "Invalid highlight expression" }
                if(draft.ruleId!=null) {
                    val current=checkNotNull(db.highlightRuleDao.findById(draft.ruleId)) { "Rule no longer exists" }
                    check(highlightRuleIdentity(current)==draft.originalKey) { "Rule changed in another window" }
                    check(value.id==current.id && value.uuid==current.uuid)
                    db.highlightRuleDao.update(value)
                    value.id
                } else {
                    check(value.id==0L)
                    check(db.highlightRuleDao.all.none { it.uuid.equals(value.uuid,ignoreCase=true) }) { "A rule with this identity already exists" }
                    // Avoid overflow when imported rules already occupy Int.MAX_VALUE.
                    val order=if(value.order==Int.MIN_VALUE) db.highlightRuleDao.maxOrder.toLong().plus(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else value.order
                    db.highlightRuleDao.insert(value.copy(order=order)).single()
                }
            }
            draft.copy(phase=if(save) "saved" else "cancelled",savedId=saved,value="",updatedAt=System.currentTimeMillis()).also(db.highlightRuleDraftDao::update)
        }
    }
}
