package io.legado.app.utils

import kotlinx.coroutines.ThreadContextElement
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A scoped adapter for synchronous FileDoc callers inside suspending operations. */
internal interface FileDocWriteInterceptor {
    fun create(parent: FileDoc, name: String, subDirs: Array<out String>, execution: CoroutineContext): FileDoc?
    fun write(destination: FileDoc, source: File, execution: CoroutineContext): Boolean
}

internal class FileDocWriteContext(private val interceptor: FileDocWriteInterceptor) :
    ThreadContextElement<FileDocWriteContext.Binding?>, AbstractCoroutineContextElement(Key) {
    internal data class Binding(val interceptor: FileDocWriteInterceptor, val execution: CoroutineContext)
    companion object Key : CoroutineContext.Key<FileDocWriteContext> {
        private val local = ThreadLocal<Binding?>()
        fun create(parent: FileDoc, name: String, subDirs: Array<out String>): FileDoc? =
            local.get()?.let { it.interceptor.create(parent, name, subDirs, it.execution) }
        fun write(destination: FileDoc, source: File): Boolean =
            local.get()?.let { it.interceptor.write(destination, source, it.execution) } ?: false
    }
    override fun updateThreadContext(context: CoroutineContext): Binding? =
        local.get().also { local.set(Binding(interceptor, context)) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: Binding?) { local.set(oldState) }
}
