package io.legado.app.help.storage

import io.legado.app.help.config.ReadBookConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Before images stay on the media volume; the small journal stays in private storage. */
internal class RestoreFileTransaction private constructor(
    private val journal: File,
    private val external: File,
    val id: String,
    private val entries: List<Entry>,
    private var phase: Int,
) {
    val isResolved get() = phase == ROLLED_BACK || phase == COMMITTED

    private data class Entry(val name: String, val privateFile: Boolean, val existed: Boolean, var fingerprint: String = "")
    private fun work(entry: Entry) = File(if (entry.privateFile) journal.parentFile!! else external, ".restore-files-$id")
    private fun staged(entry: Entry) = File(work(entry), "new/${entry.name}")
    private fun previous(entry: Entry) = File(work(entry), "old/${entry.name}")
    private fun discarded(entry: Entry) = File(work(entry), "discarded/${entry.name}")
    private fun target(entry: Entry) = File(if (entry.privateFile) journal.parentFile!! else external, entry.name)
    private fun available(entry: Entry) = if (entry.privateFile) target(entry).isFile else target(entry).isDirectory

    fun activate(checkActive: () -> Unit = {}) {
        check(phase == PREPARED) { "Media restore is not prepared" }
        entries.forEach { entry ->
            checkActive()
            val target = target(entry)
            check(target.exists() == entry.existed) { "${entry.name} changed during restore preparation" }
            if (entry.existed) {
                val old = previous(entry)
                check(old.parentFile!!.mkdirs() || old.parentFile!!.isDirectory)
                check(target.renameTo(old)) { "Unable to preserve ${entry.name}" }
                check(fingerprint(old, checkActive) == entry.fingerprint) {
                    "${entry.name} changed during restore preparation"
                }
            }
            check(staged(entry).renameTo(target)) { "Unable to activate ${entry.name}" }
        }
    }

    /** The caller decides from a marker committed in the SAME database transaction. */
    fun resolve(databaseCommitted: Boolean) {
        if (phase == ROLLED_BACK || phase == COMMITTED) return
        if (databaseCommitted) {
            check(phase == PREPARED && entries.all { available(it) && !staged(it).exists() }) {
                "Committed media is not available; retain the recovery journal"
            }
            phase = COMMITTED
        } else {
            if (phase == PREPARED) entries.asReversed().forEach { entry ->
                val old = previous(entry)
                val target = target(entry)
                if (old.exists()) {
                    // If the staged tree still exists, activation never installed it.
                    // Preserve writes that recreated the target during that gap.
                    if (target.exists() && staged(entry).exists()) copyTree(target, old) {}
                    if (target.exists()) {
                        val discarded = discarded(entry)
                        check(discarded.parentFile!!.mkdirs() || discarded.parentFile!!.isDirectory)
                        check(!discarded.exists() && target.renameTo(discarded)) {
                            "Unable to detach incomplete ${entry.name}"
                        }
                    }
                    check(old.renameTo(target)) { "Unable to recover ${entry.name}" }
                } else if (!entry.existed && !staged(entry).exists() && target.exists()) {
                    val discarded = discarded(entry)
                    check(discarded.parentFile!!.mkdirs() || discarded.parentFile!!.isDirectory)
                    check(!discarded.exists() && target.renameTo(discarded)) {
                        "Unable to recover absent ${entry.name}"
                    }
                } else if (entry.existed) {
                    check(available(entry)) { "Original ${entry.name} is unavailable; retain the recovery journal" }
                }
            }
            phase = ROLLED_BACK
        }
        save()
    }

    /** Separate from resolve: recursive deletion must not delay the first startup frame. */
    fun cleanup() {
        check(phase == ROLLED_BACK || phase == COMMITTED)
        entries.map(::work).distinct().forEach(::deleteTree)
        check(!File(journal.path + ".new").exists() || File(journal.path + ".new").delete())
        check(!journal.exists() || journal.delete()) { "Unable to remove media restore journal" }
    }

    private fun save() {
        check(journal.parentFile!!.mkdirs() || journal.parentFile!!.isDirectory)
        val pending = File(journal.path + ".new")
        FileOutputStream(pending).use { stream ->
            val out = DataOutputStream(stream)
            out.writeInt(VERSION); out.writeUTF(id); out.writeUTF(external.path); out.writeInt(phase)
            out.writeInt(entries.size)
            entries.forEach { out.writeUTF(it.name); out.writeBoolean(it.privateFile); out.writeBoolean(it.existed); out.writeUTF(it.fingerprint) }
            out.flush(); stream.fd.sync()
        }
        check(pending.renameTo(journal)) { "Unable to save media restore journal" }
    }

    companion object {
        private val privateNames = setOf(ReadBookConfig.configFileName, ReadBookConfig.shareConfigFileName)
        private val externalNames = backupMediaDirectoryNames + "font"
        private const val VERSION = 1
        private const val PREPARING = 0
        private const val PREPARED = 1
        private const val ROLLED_BACK = 2
        private const val COMMITTED = 3

        fun prepare(
            backupRoot: File,
            externalRoot: File,
            journal: File,
            names: Collection<String>,
            privateReplacements: Map<String, ByteArray> = emptyMap(),
            additionalDirectories: Map<String, (File) -> Unit> = emptyMap(),
            checkActive: () -> Unit = {},
        ): RestoreFileTransaction? {
            check(!journal.exists()) { "An earlier media restore must be recovered first" }
            require(names.distinct().size == names.size && names.all { it in backupMediaDirectoryNames })
            require(privateReplacements.keys.all { it in privateNames })
            require(additionalDirectories.keys.all { it == "font" })
            val external = externalRoot.canonicalFile
            val selected = names.filter { name ->
                val source = File(backupRoot, name)
                if (!source.isDirectory) false else {
                    require(!Files.isSymbolicLink(source.toPath())) { "Media links cannot be restored" }
                    tree(source).any { it.isFile }
                }
            }
            if (selected.isEmpty() && privateReplacements.isEmpty() && additionalDirectories.isEmpty()) return null
            check(external.mkdirs() || external.isDirectory) { "Media storage is unavailable" }
            val entries = (selected + additionalDirectories.keys).map { name ->
                val target = File(external, name)
                check(!target.exists() || (target.isDirectory && !Files.isSymbolicLink(target.toPath()))) {
                    "$name is not a media directory"
                }
                Entry(name, false, target.exists())
            } + privateReplacements.keys.map { name ->
                val target = File(journal.parentFile, name)
                check(!target.exists() || (target.isFile && !Files.isSymbolicLink(target.toPath()))) {
                    "$name is not a configuration file"
                }
                Entry(name, true, target.exists())
            }
            val transaction = RestoreFileTransaction(journal, external, UUID.randomUUID().toString(), entries, PREPARING)
            transaction.save() // No live media may change before this durable intent exists.
            try {
                entries.forEach { entry ->
                    checkActive()
                    val staged = transaction.staged(entry)
                    if (entry.privateFile) {
                        if (entry.existed) entry.fingerprint = fingerprint(transaction.target(entry), checkActive)
                        check(staged.parentFile!!.mkdirs() || staged.parentFile!!.isDirectory)
                        FileOutputStream(staged).use { stream ->
                            stream.write(privateReplacements.getValue(entry.name)); stream.fd.sync()
                        }
                    } else {
                        check(staged.mkdirs() || staged.isDirectory)
                        if (entry.existed) {
                            copyTree(transaction.target(entry), staged, checkActive)
                            entry.fingerprint = fingerprint(staged, checkActive)
                            check(fingerprint(transaction.target(entry), checkActive) == entry.fingerprint) {
                                "${entry.name} changed while staging"
                            }
                        }
                        val prepareAdditional = additionalDirectories[entry.name]
                        if (prepareAdditional != null) prepareAdditional(staged)
                        else copyTree(File(backupRoot, entry.name), staged, checkActive)
                    }
                    checkActive()
                }
                transaction.phase = PREPARED
                transaction.save()
                return transaction
            } catch (failure: Throwable) {
                // Preparation only writes owned staging, never the live directories.
                transaction.phase = PREPARING
                try { transaction.resolve(false); transaction.cleanup() }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun read(journal: File, externalRoot: File): RestoreFileTransaction? {
            if (!journal.isFile) return null
            return DataInputStream(FileInputStream(journal)).use { input ->
                check(input.readInt() == VERSION) { "Unsupported media journal" }
                val id = input.readUTF()
                check(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
                val external = externalRoot.canonicalFile
                check(input.readUTF() == external.path) { "Original media volume is unavailable" }
                val phase = input.readInt(); check(phase in PREPARING..COMMITTED)
                val count = input.readInt(); check(count in 1..(privateNames.size + externalNames.size))
                val entries = (0 until count).map {
                    val name = input.readUTF()
                    val privateFile = input.readBoolean()
                    check(name in if (privateFile) privateNames else externalNames)
                    val existed = input.readBoolean()
                    val fingerprint = input.readUTF()
                    check(fingerprint.isEmpty() || fingerprint.matches(Regex("[0-9a-f]{64}")))
                    Entry(name, privateFile, existed, fingerprint)
                }
                check(entries.map { it.privateFile to it.name }.distinct().size == count && input.read() == -1)
                RestoreFileTransaction(journal, external, id, entries, phase)
            }
        }

        private fun deleteTree(root: File) {
            if (!root.exists()) return
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }

        private fun tree(root: File): Sequence<File> = root.walkTopDown().onEnter {
            check(!Files.isSymbolicLink(it.toPath())) { "Media links cannot be restored" }
            true
        }.onFail { _, error -> throw error }.onEach {
            check(!Files.isSymbolicLink(it.toPath())) { "Media links cannot be restored" }
        }

        private fun copyTree(source: File, target: File, checkActive: () -> Unit) {
            tree(source).forEach { file ->
                checkActive()
                val output = File(target, file.relativeTo(source).path)
                if (file.isDirectory) {
                    if (output.exists() && !output.isDirectory) check(output.delete())
                    check(output.mkdirs() || output.isDirectory)
                } else if (file.isFile) {
                    if (output.isDirectory) check(output.deleteRecursively())
                    check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                    file.inputStream().use { input -> FileOutputStream(output).use { stream ->
                        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkActive()
                            val size = input.read(bytes)
                            if (size < 0) break
                            stream.write(bytes, 0, size)
                        }
                        stream.fd.sync()
                    } }
                } else throw IOException("Media input is not readable")
            }
        }

        private fun fingerprint(root: File, checkActive: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            tree(root).toList().sortedBy { it.relativeTo(root).path }.forEach { file ->
                checkActive()
                digest.update((if (file.isDirectory) "D" else "F").toByteArray())
                digest.update(file.relativeTo(root).path.toByteArray(Charsets.UTF_8)); digest.update(0)
                if (file.isFile) file.inputStream().use { input ->
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        digest.update(bytes, 0, count)
                    }
                }
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
