package io.legado.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.migrations.SharedPreferencesMigration
import dagger.Binds
import dagger.Module
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MangaReaderPreferences(
    @ProtoNumber(1) val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @ProtoNumber(2) val scaleDisabled: Boolean = true,
    @ProtoNumber(3) val longClickSaveEnabled: Boolean = true,
    @ProtoNumber(4) val pageAnimationDisabled: Boolean = false,
    @ProtoNumber(5) val preDownloadCount: Int = 10,
    @ProtoNumber(6) val clickScrollDisabled: Boolean = false,
    @ProtoNumber(7) val autoPageSpeed: Int = 3,
    @ProtoNumber(8) val footerConfigJson: String = "",
    @ProtoNumber(9) val horizontalScrollEnabled: Boolean = false,
    @ProtoNumber(10) val doublePageEnabled: Boolean = true,
    @ProtoNumber(11) val rightToLeftEnabled: Boolean = false,
    @ProtoNumber(12) val colorFilterJson: String = "",
    @ProtoNumber(13) val titleHidden: Boolean = false,
    @ProtoNumber(14) val eInkEnabled: Boolean = false,
    @ProtoNumber(15) val eInkThreshold: Int = 150,
    @ProtoNumber(16) val horizontalPageSnapDisabled: Boolean = false,
    @ProtoNumber(17) val grayEnabled: Boolean = false,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

val MANGA_READER_LEGACY_KEYS = setOf(
    PreferKey.disableMangaScale,
    PreferKey.mangaLongClickSaveImage,
    PreferKey.disableMangaPageAnim,
    PreferKey.mangaPreDownloadNum,
    PreferKey.disableClickScroll,
    PreferKey.mangaAutoPageSpeed,
    PreferKey.mangaFooterConfig,
    PreferKey.enableMangaHorizontalScroll,
    PreferKey.enableMangaDoublePage,
    PreferKey.mangaRightToLeft,
    PreferKey.mangaColorFilter,
    PreferKey.hideMangaTitle,
    PreferKey.enableMangaEInk,
    PreferKey.mangaEInkThreshold,
    PreferKey.disableHorizontalPageSnap,
    PreferKey.enableMangaGray,
)

fun MangaReaderPreferences.toLegacyPreferenceMap(): Map<String, Any> = mapOf(
    PreferKey.disableMangaScale to scaleDisabled,
    PreferKey.mangaLongClickSaveImage to longClickSaveEnabled,
    PreferKey.disableMangaPageAnim to pageAnimationDisabled,
    PreferKey.mangaPreDownloadNum to preDownloadCount,
    PreferKey.disableClickScroll to clickScrollDisabled,
    PreferKey.mangaAutoPageSpeed to autoPageSpeed,
    PreferKey.mangaFooterConfig to footerConfigJson,
    PreferKey.enableMangaHorizontalScroll to horizontalScrollEnabled,
    PreferKey.enableMangaDoublePage to doublePageEnabled,
    PreferKey.mangaRightToLeft to rightToLeftEnabled,
    PreferKey.mangaColorFilter to colorFilterJson,
    PreferKey.hideMangaTitle to titleHidden,
    PreferKey.enableMangaEInk to eInkEnabled,
    PreferKey.mangaEInkThreshold to eInkThreshold,
    PreferKey.disableHorizontalPageSnap to horizontalPageSnapDisabled,
    PreferKey.enableMangaGray to grayEnabled,
)

fun MangaReaderPreferences.restoreLegacyPreferenceMap(values: Map<String, Any?>) = copy(
    scaleDisabled = values[PreferKey.disableMangaScale] as? Boolean ?: scaleDisabled,
    longClickSaveEnabled = values[PreferKey.mangaLongClickSaveImage] as? Boolean
        ?: longClickSaveEnabled,
    pageAnimationDisabled = values[PreferKey.disableMangaPageAnim] as? Boolean
        ?: pageAnimationDisabled,
    preDownloadCount = values[PreferKey.mangaPreDownloadNum] as? Int ?: preDownloadCount,
    clickScrollDisabled = values[PreferKey.disableClickScroll] as? Boolean ?: clickScrollDisabled,
    autoPageSpeed = values[PreferKey.mangaAutoPageSpeed] as? Int ?: autoPageSpeed,
    footerConfigJson = values[PreferKey.mangaFooterConfig] as? String ?: footerConfigJson,
    horizontalScrollEnabled = values[PreferKey.enableMangaHorizontalScroll] as? Boolean
        ?: horizontalScrollEnabled,
    doublePageEnabled = values[PreferKey.enableMangaDoublePage] as? Boolean ?: doublePageEnabled,
    rightToLeftEnabled = values[PreferKey.mangaRightToLeft] as? Boolean ?: rightToLeftEnabled,
    colorFilterJson = values[PreferKey.mangaColorFilter] as? String ?: colorFilterJson,
    titleHidden = values[PreferKey.hideMangaTitle] as? Boolean ?: titleHidden,
    eInkEnabled = values[PreferKey.enableMangaEInk] as? Boolean ?: eInkEnabled,
    eInkThreshold = values[PreferKey.mangaEInkThreshold] as? Int ?: eInkThreshold,
    horizontalPageSnapDisabled = values[PreferKey.disableHorizontalPageSnap] as? Boolean
        ?: horizontalPageSnapDisabled,
    grayEnabled = values[PreferKey.enableMangaGray] as? Boolean ?: grayEnabled,
)

@OptIn(ExperimentalSerializationApi::class)
private object MangaReaderPreferencesSerializer : Serializer<MangaReaderPreferences> {
    override val defaultValue = MangaReaderPreferences(schemaVersion = 0)

    override suspend fun readFrom(input: InputStream): MangaReaderPreferences =
        runCatching {
            ProtoBuf.decodeFromByteArray(MangaReaderPreferences.serializer(), input.readBytes())
        }
            .getOrElse { error ->
                if (error is IOException) throw error
                throw IOException("Unable to read manga reader preferences", error)
            }

    override suspend fun writeTo(t: MangaReaderPreferences, output: OutputStream) {
        output.write(ProtoBuf.encodeToByteArray(MangaReaderPreferences.serializer(), t))
    }
}

interface MangaReaderPreferencesStore {
    val preferences: Flow<MangaReaderPreferences>
    suspend fun current(): MangaReaderPreferences
    /** Restore must distinguish an unreadable store from valid default settings. */
    suspend fun requireCurrent(): MangaReaderPreferences = current()
    suspend fun update(transform: (MangaReaderPreferences) -> MangaReaderPreferences)
}

@Singleton
class MangaReaderPreferencesRepository @Inject constructor(
    @ApplicationContext context: Context,
) : MangaReaderPreferencesStore {
    private val dataStore: DataStore<MangaReaderPreferences> = DataStoreFactory.create(
        serializer = MangaReaderPreferencesSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { MangaReaderPreferences() },
        migrations = listOf(
            SharedPreferencesMigration(
                produceSharedPreferences = { context.defaultSharedPreferences },
                keysToMigrate = MANGA_READER_LEGACY_KEYS,
                shouldRunMigration = { current -> current.schemaVersion < MangaReaderPreferences.CURRENT_SCHEMA_VERSION },
                migrate = { legacy, _ ->
                    MangaReaderPreferences(
                        scaleDisabled = legacy.getBoolean(PreferKey.disableMangaScale, true),
                        longClickSaveEnabled = legacy.getBoolean(PreferKey.mangaLongClickSaveImage, true),
                        pageAnimationDisabled = legacy.getBoolean(PreferKey.disableMangaPageAnim, false),
                        preDownloadCount = legacy.getInt(PreferKey.mangaPreDownloadNum, 10),
                        clickScrollDisabled = legacy.getBoolean(PreferKey.disableClickScroll, false),
                        autoPageSpeed = legacy.getInt(PreferKey.mangaAutoPageSpeed, 3),
                        footerConfigJson = legacy.getString(PreferKey.mangaFooterConfig, "") ?: "",
                        horizontalScrollEnabled = legacy.getBoolean(PreferKey.enableMangaHorizontalScroll, false),
                        doublePageEnabled = legacy.getBoolean(PreferKey.enableMangaDoublePage, true),
                        rightToLeftEnabled = legacy.getBoolean(PreferKey.mangaRightToLeft, false),
                        colorFilterJson = legacy.getString(PreferKey.mangaColorFilter, "") ?: "",
                        titleHidden = legacy.getBoolean(PreferKey.hideMangaTitle, false),
                        eInkEnabled = legacy.getBoolean(PreferKey.enableMangaEInk, false),
                        eInkThreshold = legacy.getInt(PreferKey.mangaEInkThreshold, 150),
                        horizontalPageSnapDisabled = legacy.getBoolean(PreferKey.disableHorizontalPageSnap, false),
                        grayEnabled = legacy.getBoolean(PreferKey.enableMangaGray, false),
                    )
                },
            ),
        ),
        produceFile = { context.dataStoreFile(FILE_NAME) },
    )

    override val preferences: Flow<MangaReaderPreferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(MangaReaderPreferences()) else throw error
    }

    override suspend fun update(transform: (MangaReaderPreferences) -> MangaReaderPreferences) {
        dataStore.updateData(transform)
    }

    override suspend fun current(): MangaReaderPreferences = preferences.first()
    override suspend fun requireCurrent(): MangaReaderPreferences = dataStore.data.first()

    private companion object {
        const val FILE_NAME = "manga_reader_preferences.pb"

    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MangaReaderPreferencesModule {
    @Binds
    @Singleton
    abstract fun bindMangaReaderPreferencesStore(
        repository: MangaReaderPreferencesRepository,
    ): MangaReaderPreferencesStore
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MangaReaderPreferencesEntryPoint {
    fun mangaReaderPreferencesStore(): MangaReaderPreferencesStore
}
