package io.legado.app.help.storage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.data.preferences.CacheExportPreferencesEntryPoint
import io.legado.app.data.preferences.proto.CacheExportSettingsProto.CacheExportSettings
import io.legado.app.data.repository.MangaReaderPreferences
import io.legado.app.data.repository.MangaReaderPreferencesEntryPoint
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.help.config.ReplacePreviewConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.io.FileOutputStream
import java.util.UUID

/** Before images are private, typed, and durable before any tracked mutation. */
@OptIn(ExperimentalSerializationApi::class)
internal class RestoreSettingsTransaction private constructor(
    private val context: Context,
    val id: String,
    private val groups: Map<String, Group>,
    private val export: ByteArray?,
    private val manga: ByteArray?,
    private var storesTouched: Boolean = false,
    private var resolved: Boolean = false,
) {
    val isResolved get() = resolved

    private data class Group(val before: Map<String, Any>, val touched: MutableSet<String> = linkedSetOf())
    private val journal get() = journal(context)
    private fun exports() = EntryPointAccessors.fromApplication(context, CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences()
    private fun mangas() = EntryPointAccessors.fromApplication(context, MangaReaderPreferencesEntryPoint::class.java).mangaReaderPreferencesStore()

    fun editDefault(): SharedPreferences.Editor = edit(defaultName(context))
    fun editLocal(): SharedPreferences.Editor = edit("local")
    fun editVideo(): SharedPreferences.Editor = edit(VIDEO_PREF_NAME)
    fun editSamples(): SharedPreferences.Editor = edit(ReplacePreviewConfig.PREFS_NAME)

    private fun edit(name: String): SharedPreferences.Editor {
        check(!resolved)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val delegate = preferences.edit()
        val keys = linkedSetOf<String>()
        return object : SharedPreferences.Editor by delegate {
            override fun putString(key: String, value: String?) = apply { keys += key; delegate.putString(key, value) }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { keys += key; delegate.putStringSet(key, values) }
            override fun putInt(key: String, value: Int) = apply { keys += key; delegate.putInt(key, value) }
            override fun putLong(key: String, value: Long) = apply { keys += key; delegate.putLong(key, value) }
            override fun putFloat(key: String, value: Float) = apply { keys += key; delegate.putFloat(key, value) }
            override fun putBoolean(key: String, value: Boolean) = apply { keys += key; delegate.putBoolean(key, value) }
            override fun remove(key: String) = apply { keys += key; delegate.remove(key) }
            override fun clear() = apply { keys += preferences.all.keys; keys += groups.getValue(name).before.keys; delegate.clear() }
            override fun commit(): Boolean {
                check(!resolved)
                groups.getValue(name).touched += keys
                save() // No memory/disk mutation until its before-image write set is durable.
                check(delegate.commit()) { "恢复设置写入失败：$name" }
                return true
            }
            override fun apply() { commit() }
        }
    }

    /** Call before either DataStore update. Their before images were loaded before the journal. */
    fun markStoresTouched() {
        check(!resolved && export != null && manga != null)
        storesTouched = true
        save()
    }

    suspend fun resolve(databaseCommitted: Boolean) {
        if (resolved) return
        if (!databaseCommitted) {
            // Parse both before changing anything. Do not partially replay a malformed journal.
            val exportBefore = export?.let(CacheExportSettings::parseFrom)
            val mangaBefore = manga?.let { ProtoBuf.decodeFromByteArray(MangaReaderPreferences.serializer(), it) }
            if (storesTouched) {
                checkNotNull(exportBefore); checkNotNull(mangaBefore)
                exports().restoreSnapshot(checkNotNull(export))
                mangas().update { mangaBefore }
            }
            groups.forEach { (name, group) ->
                if (group.touched.isNotEmpty()) {
                    val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    group.touched.forEach { key ->
                        when (val value = group.before[key]) {
                            null -> edit.remove(key)
                            is String -> edit.putString(key, value)
                            is Int -> edit.putInt(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
                            else -> error("Unknown preference before-image")
                        }
                    }
                    check(edit.commit()) { "恢复旧设置失败：$name" }
                }
            }
        }
        resolved = true
        save()
    }

    fun cleanup() {
        check(resolved)
        val temporary = File(journal.path + ".new")
        check(!temporary.exists() || temporary.delete())
        check(!journal.exists() || journal.delete())
    }

    private fun save() {
        val temporary = File(journal.path + ".new")
        FileOutputStream(temporary).use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val out = DataOutputStream(DigestOutputStream(stream, digest))
            out.writeInt(1); out.text(id); out.writeBoolean(resolved); out.writeBoolean(storesTouched)
            out.blob(export); out.blob(manga); out.writeInt(groups.size)
            groups.forEach { (name, group) ->
                out.text(name); out.writeInt(group.before.size)
                group.before.forEach { (key, value) -> out.text(key); out.value(value) }
                out.writeInt(group.touched.size); group.touched.forEach { out.text(it) }
            }
            out.flush(); check(stream.channel.size() <= MAX_BYTES - 32)
            stream.write(digest.digest()); stream.fd.sync()
        }
        check(temporary.renameTo(journal)) { "无法保存恢复设置日志" }
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024 * 1024
        private fun defaultName(context: Context) = "${context.packageName}_preferences"
        private fun names(context: Context) = setOf(defaultName(context), "local", VIDEO_PREF_NAME, ReplacePreviewConfig.PREFS_NAME)
        private fun journal(context: Context) = File(context.filesDir, "restore-settings-v1.bin")
        fun hasPending(context: Context) = journal(context).isFile

        suspend fun prepare(context: Context, id: String, includeDataStores: Boolean): RestoreSettingsTransaction {
            check(!journal(context).exists())
            // Establish the application's migrated DataStore baseline before importing preferences.
            val export = if (includeDataStores) EntryPointAccessors.fromApplication(context,
                CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences().snapshotForRestore() else null
            val manga = if (includeDataStores) EntryPointAccessors.fromApplication(context,
                MangaReaderPreferencesEntryPoint::class.java).mangaReaderPreferencesStore().requireCurrent().let {
                ProtoBuf.encodeToByteArray(MangaReaderPreferences.serializer(), it)
            } else null
            val groups = names(context).associateWith { name ->
                val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
                    checkNotNull(if (value is Set<*>) value.toSet() else value)
                }
                Group(values)
            }
            return RestoreSettingsTransaction(context, id, groups, export, manga).also { it.save() }
        }

        fun read(context: Context): RestoreSettingsTransaction? {
            val file = journal(context)
            if (!file.isFile) return null
            check(file.length() in 33..MAX_BYTES)
            val data = file.readBytes()
            val payload = data.copyOfRange(0, data.size - 32)
            check(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload),
                data.copyOfRange(data.size - 32, data.size))) { "恢复设置日志校验失败" }
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                check(input.readInt() == 1)
                val id = input.text(); check(UUID.fromString(id).toString() == id)
                val resolved = input.readBoolean(); val touched = input.readBoolean()
                val export = input.blob(); val manga = input.blob()
                check((export == null) == (manga == null) && (!touched || export != null))
                val names = names(context); check(input.readInt() == names.size)
                val groups = linkedMapOf<String, Group>()
                repeat(names.size) {
                    val name = input.text(); check(name in names && name !in groups)
                    val before = linkedMapOf<String, Any>()
                    repeat(input.count()) { val key = input.text(); check(key !in before); before[key] = input.value() }
                    val changed = linkedSetOf<String>()
                    repeat(input.count()) { check(changed.add(input.text())) }
                    groups[name] = Group(before, changed)
                }
                check(input.read() == -1)
                RestoreSettingsTransaction(context, id, groups, export, manga, touched, resolved)
            }
        }

        private fun DataOutputStream.text(value: String) { blob(value.toByteArray(Charsets.UTF_8)) }
        private fun DataInputStream.text(): String = checkNotNull(blob()).toString(Charsets.UTF_8)
        private fun DataOutputStream.blob(value: ByteArray?) {
            writeInt(value?.size ?: -1); value?.let { check(it.size <= MAX_BYTES); write(it) }
        }
        private fun DataInputStream.blob(): ByteArray? {
            val size = readInt(); if (size == -1) return null
            check(size in 0..MAX_BYTES && size <= available())
            return ByteArray(size).also(::readFully)
        }
        private fun DataInputStream.count(): Int = readInt().also { check(it in 0..100_000 && it <= available()) }
        private fun DataOutputStream.value(value: Any) {
            when (value) {
                is String -> { writeByte(0); text(value) }
                is Int -> { writeByte(1); writeInt(value) }
                is Long -> { writeByte(2); writeLong(value) }
                is Float -> { writeByte(3); writeFloat(value) }
                is Boolean -> { writeByte(4); writeBoolean(value) }
                is Set<*> -> { writeByte(5); writeInt(value.size); value.forEach { text(it as String) } }
                else -> error("Unsupported preference snapshot type")
            }
        }
        private fun DataInputStream.value(): Any = when (readUnsignedByte()) {
            0 -> text(); 1 -> readInt(); 2 -> readLong(); 3 -> readFloat(); 4 -> readBoolean()
            5 -> (0 until count()).map { text() }.toSet()
            else -> error("Unknown preference snapshot type")
        }
    }
}
