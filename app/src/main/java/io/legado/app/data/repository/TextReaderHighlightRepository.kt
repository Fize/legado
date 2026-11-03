package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.HighlightRuleMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.model.TextReaderDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** Entity equality only compares ids. Every mutable rule field must enter this immutable snapshot. */
data class TextReaderHighlightRule(val id: Long,val uuid: String,val name: String,val pattern: String,
    val regex: Boolean,val style: HighlightStyle,val timeout: Long,val title: Boolean,val body: Boolean,val fingerprint: String)
data class TextReaderRuleMatch(val start: Int,val end: Int,val rule: TextReaderHighlightRule)
data class TextReaderRuleHighlights(val matches: List<TextReaderRuleMatch> = emptyList(),val completed: Boolean=true,val validBook: Boolean=true)

internal fun interface TextReaderRuleEngine {
    fun match(text: String,rules: List<HighlightRuleMatcher.Rule>,active: () -> Boolean,titleLength: Int): HighlightRuleMatcher.MatchResult
}

@Singleton
class TextReaderHighlightRepository internal constructor(private val db: AppDatabase,private val engine: TextReaderRuleEngine) {
    @Inject constructor(db: AppDatabase): this(db,TextReaderRuleEngine { text,rules,active,title ->
        HighlightRuleMatcher.matchDetailed(text,rules,active,titleLength=title)
    })
    private data class Snapshot(val name: String?,val valid: Boolean,val rules: List<TextReaderHighlightRule>)
    private fun snapshot(book: Book?,origin: String,rows: List<HighlightRule>,active: () -> Unit = {}): Snapshot {
        if(book==null || book.origin!=origin) return Snapshot(null,false,emptyList())
        val rules=rows.filter { rule -> active(); rule.isEnabled && (rule.scope.isNullOrEmpty() ||
            book.name.isNotEmpty() && rule.scope!!.contains(book.name) || book.origin.isNotEmpty() && rule.scope!!.contains(book.origin)) }
            .sortedWith(compareBy({it.order},{it.id})).map { rule ->
                active()
                TextReaderHighlightRule(rule.id,rule.uuid,rule.getDisplayName(),rule.pattern,rule.isRegex,rule.styleObj(),
                    rule.timeoutMillisecond,rule.applyToTitle,rule.applyToBody,highlightRuleIdentity(rule))
            }
        return Snapshot(book.name,true,java.util.Collections.unmodifiableList(rules))
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(bookUrl: String,origin: String,document: TextReaderDocument): Flow<TextReaderRuleHighlights> {
        val snapshots=combine(db.bookDao.observeAnnotationBook(bookUrl),db.highlightRuleDao.flowAll()) { book,rules ->
            val context=currentCoroutineContext()
            snapshot(book,origin,rules) { context.ensureActive() }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
        return snapshots.transformLatest { requested ->
            val result=withContext(Dispatchers.Default) {
                val context=currentCoroutineContext()
                if(!requested.valid) TextReaderRuleHighlights(validBook=false)
                else {
                    val matched=engine.match(document.text,requested.rules.map {
                        HighlightRuleMatcher.Rule(it.id,it.pattern,it.regex,it.style,it.timeout,it.title,it.body)
                    },{ context.isActive },document.titleLength)
                    context.ensureActive()
                    val byId=requested.rules.associateBy { it.id }
                    fun boundary(offset: Int)=offset in 0..document.text.length && !(offset in 1 until document.text.length &&
                        document.text[offset].isLowSurrogate() && document.text[offset-1].isHighSurrogate())
                    val hits=if(!matched.completed) emptyList() else matched.matches.filter { boundary(it.start) && boundary(it.end) }
                        .map { TextReaderRuleMatch(it.start,it.end,checkNotNull(byId[it.ruleId])) }
                    TextReaderRuleHighlights(java.util.Collections.unmodifiableList(hits),matched.completed)
                }
            }
            currentCoroutineContext().ensureActive()
            val latest=withContext(Dispatchers.IO) {
                val context=currentCoroutineContext()
                db.runInTransaction<Snapshot> { snapshot(db.bookDao.getBook(bookUrl),origin,db.highlightRuleDao.all) { context.ensureActive() } }
            }
            if(withContext(Dispatchers.Default) { latest==requested }) emit(result)
        }
    }
    /** The popup carries a full snapshot, so an imported/replaced rule cannot be disabled by an old action. */
    suspend fun disable(expected: TextReaderHighlightRule)=withContext(Dispatchers.IO) {
        db.runInTransaction {
            val actual=checkNotNull(db.highlightRuleDao.findById(expected.id)) { "Highlight rule changed" }
            check(actual.uuid==expected.uuid && highlightRuleIdentity(actual)==expected.fingerprint) { "Highlight rule changed" }
            db.highlightRuleDao.update(actual.copy(isEnabled=false))
        }
    }
}
