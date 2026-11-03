package io.legado.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep
import androidx.datastore.core.*
import androidx.datastore.dataStoreFile
import com.google.protobuf.InvalidProtocolBufferException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.proto.CacheExportSettingsProto.CacheExportSettings
import io.legado.app.utils.ACache
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class CacheExportOptions(val format: Int=0,val charset: String="UTF-8",val useReplace: Boolean=true,
    val webDav: Boolean=false,val omitNames: Boolean=false,val pictures: Boolean=false,val parallel: Boolean=false,
    val customSections: Boolean=false,val fileName: String="",val episodeName: String="",val directory: String="") {
    val type get()=listOf("txt","epub","pdf").getOrElse(format) { "txt" }
    fun validate() { require(format in 0..2 && Charset.isSupported(charset)); require(fileName.length<=500_000 && episodeName.length<=500_000) }
}

internal val CACHE_EXPORT_KEYS=setOf(PreferKey.exportType,PreferKey.exportCharset,PreferKey.exportUseReplace,
    PreferKey.exportToWebDav,PreferKey.exportNoChapterName,PreferKey.exportPictureFile,PreferKey.parallelExportBook,
    PreferKey.enableCustomExport,PreferKey.bookExportFileName,PreferKey.episodeExportFileName)

internal fun CacheExportSettings.options()=CacheExportOptions(format,charset.ifBlank { "UTF-8" },if(hasUseReplace()) useReplace else true,
    toWebDav,omitChapterNames,pictureFiles,parallel,customSections,fileNameScript,episodeNameScript,directory)
internal fun CacheExportSettings.withOptions(value: CacheExportOptions)=toBuilder().setSchemaVersion(1).setFormat(value.format)
    .setCharset(value.charset).setUseReplace(value.useReplace).setToWebDav(value.webDav).setOmitChapterNames(value.omitNames)
    .setPictureFiles(value.pictures).setParallel(value.parallel).setCustomSections(value.customSections)
    .setFileNameScript(value.fileName).setEpisodeNameScript(value.episodeName).setDirectory(value.directory).build()
fun CacheExportOptions.toLegacyPreferenceMap(): Map<String,Any> = mapOf(PreferKey.exportType to format,PreferKey.exportCharset to charset,
    PreferKey.exportUseReplace to useReplace,PreferKey.exportToWebDav to webDav,PreferKey.exportNoChapterName to omitNames,
    PreferKey.exportPictureFile to pictures,PreferKey.parallelExportBook to parallel,PreferKey.enableCustomExport to customSections,
    PreferKey.bookExportFileName to fileName,PreferKey.episodeExportFileName to episodeName)
fun CacheExportOptions.restoreLegacyPreferenceMap(values: Map<String,Any?>)=copy(
    format=(values[PreferKey.exportType] as? Int)?.takeIf { it in 0..2 } ?: format,
    charset=(values[PreferKey.exportCharset] as? String)?.ifBlank { "UTF-8" } ?: charset,
    useReplace=values[PreferKey.exportUseReplace] as? Boolean ?: useReplace,webDav=values[PreferKey.exportToWebDav] as? Boolean ?: webDav,
    omitNames=values[PreferKey.exportNoChapterName] as? Boolean ?: omitNames,pictures=values[PreferKey.exportPictureFile] as? Boolean ?: pictures,
    parallel=values[PreferKey.parallelExportBook] as? Boolean ?: parallel,customSections=values[PreferKey.enableCustomExport] as? Boolean ?: customSections,
    fileName=values[PreferKey.bookExportFileName] as? String ?: fileName,episodeName=values[PreferKey.episodeExportFileName] as? String ?: episodeName)

internal object CacheExportSerializer: Serializer<CacheExportSettings> {
    override val defaultValue: CacheExportSettings=CacheExportSettings.getDefaultInstance()
    override suspend fun readFrom(input: InputStream): CacheExportSettings=try { CacheExportSettings.parseFrom(input) }
        catch(error: InvalidProtocolBufferException) { throw CorruptionException("Cannot read export preferences",error) }
    override suspend fun writeTo(t: CacheExportSettings,output: OutputStream) { t.writeTo(output) }
}
internal fun cacheExportStore(file: File,prefs: SharedPreferences,legacyDirectory: () -> String,
    scope: CoroutineScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)): DataStore<CacheExportSettings> = DataStoreFactory.create(
    serializer=CacheExportSerializer,scope=scope,migrations=listOf(object: DataMigration<CacheExportSettings> {
        override suspend fun shouldMigrate(currentData: CacheExportSettings)=currentData.schemaVersion<1 || CACHE_EXPORT_KEYS.any(prefs::contains)
        override suspend fun migrate(currentData: CacheExportSettings)=if(currentData.schemaVersion>=1) currentData else currentData.withOptions(
            CacheExportOptions(directory=legacyDirectory()).restoreLegacyPreferenceMap(prefs.all))
        override suspend fun cleanUp() {
            // KTX edit returns Unit; initialization must observe a failed synchronous commit.
            val editor=prefs.edit()
            CACHE_EXPORT_KEYS.forEach { editor.remove(it) }
            check(editor.commit()) { "Cannot remove migrated export preferences" }
        }
    }),produceFile={ file })

@Singleton
class CacheExportPreferencesRepository internal constructor(private val store: DataStore<CacheExportSettings>) {
    @Inject constructor(@ApplicationContext context: Context): this(cacheExportStore(context.dataStoreFile("cache_export.pb"),
        context.defaultSharedPreferences,{ ACache.get().getAsString("exportBookPath").orEmpty() }))
    val preferences: Flow<CacheExportOptions> = store.data.map { it.options() }.distinctUntilChanged()
    suspend fun current()=preferences.first()
    internal suspend fun snapshotForRestore(): ByteArray = store.data.first().toByteArray()
    internal suspend fun restoreSnapshot(bytes: ByteArray) {
        val original = CacheExportSettings.parseFrom(bytes)
        store.updateData { original }
    }
    suspend fun update(change: (CacheExportOptions) -> CacheExportOptions) {
        store.updateData { old -> val value=change(old.options()); value.validate(); old.withOptions(value) }
    }
}
@EntryPoint @InstallIn(SingletonComponent::class)
interface CacheExportPreferencesEntryPoint { fun cacheExportPreferences(): CacheExportPreferencesRepository }
