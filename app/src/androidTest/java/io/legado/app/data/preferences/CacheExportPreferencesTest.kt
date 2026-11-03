package io.legado.app.data.preferences

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.proto.CacheExportSettingsProto.CacheExportSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CacheExportPreferencesTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val id="cache-export-${UUID.randomUUID()}"
    private val prefs=context.getSharedPreferences(id,0)
    private val file=File(context.cacheDir,"$id.pb")
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private fun repository(directory: String="")=CacheExportPreferencesRepository(cacheExportStore(file,prefs,{ directory },scope))
    @After fun cleanup() { scope.cancel(); file.delete(); context.deleteSharedPreferences(id) }

    @Test fun migrationCopiesEveryLegacyOptionBeforeRemovingOnlyItsOwnKeys()=runBlocking {
        val expected=CacheExportOptions(2,"GB18030",false,true,true,true,true,true,"name-script","episode-script","content://provider/tree/export")
        prefs.edit().putString("other-setting","keep").apply {
            expected.toLegacyPreferenceMap().forEach { (key,value) -> when(value) {
                is String -> putString(key,value)
                is Int -> putInt(key,value)
                is Boolean -> putBoolean(key,value)
            } }
        }.commit()
        val repo=repository(expected.directory)
        assertEquals(expected,repo.current())
        assertTrue(CACHE_EXPORT_KEYS.none(prefs::contains)); assertEquals("keep",prefs.getString("other-setting",null))
        assertEquals(expected,CacheExportSettings.parseFrom(file.readBytes()).options())
    }
    @Test fun aCommittedSchemaNeverReimportsStaleLegacyValues()=runBlocking {
        file.writeBytes(CacheExportSettings.getDefaultInstance().withOptions(CacheExportOptions(format=1,directory="saved")).toByteArray())
        prefs.edit().putInt(PreferKey.exportType,2).commit()
        assertEquals(1,repository("old").current().format)
        assertFalse(prefs.contains(PreferKey.exportType))
        assertEquals("saved",CacheExportSettings.parseFrom(file.readBytes()).directory)
    }
    @Test fun failedLegacyCleanupCanRetryWithoutImportingNewStaleValues()=runBlocking {
        prefs.edit().putInt(PreferKey.exportType,1).commit()
        val fail=java.util.concurrent.atomic.AtomicBoolean(true)
        val failing=object: SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor=prefs.edit()
                return object: SharedPreferences.Editor by editor {
                    override fun commit(): Boolean=if(fail.getAndSet(false)) false else editor.commit()
                }
            }
        }
        val repo=CacheExportPreferencesRepository(cacheExportStore(file,failing,{ "original" },scope))
        assertTrue(runCatching { repo.current() }.isFailure)
        assertEquals(1,CacheExportSettings.parseFrom(file.readBytes()).format)
        prefs.edit().putInt(PreferKey.exportType,2).commit()
        val restored=repo.current()
        assertEquals(1,restored.format); assertEquals("original",restored.directory)
        assertFalse(prefs.contains(PreferKey.exportType))
    }
    @Test fun absentOptionalBooleanUsesTheHistoricalTrueDefault()=runBlocking {
        file.writeBytes(CacheExportSettings.newBuilder().setSchemaVersion(1).build().toByteArray())
        val options=repository().current()
        assertTrue(options.useReplace); assertEquals("UTF-8",options.charset)
    }
    @Test fun changesAreSerializedWithoutDroppingAnotherFieldsUpdate()=runBlocking {
        val repo=repository(); repo.current()
        coroutineScope {
            launch { repo.update { it.copy(format=2) } }
            launch { repo.update { it.copy(pictures=true) } }
            launch { repo.update { it.copy(fileName="script") } }
        }
        assertEquals(CacheExportOptions(format=2,pictures=true,fileName="script"),repo.current())
    }
    @Test fun invalidUpdatesFailWithoutReplacingThePersistedValue()=runBlocking {
        val repo=repository(); val before=repo.current()
        assertTrue(runCatching { repo.update { it.copy(format=8) } }.isFailure)
        assertTrue(runCatching { repo.update { it.copy(charset="not a charset") } }.isFailure)
        assertEquals(before,repo.current())
    }
    @Test fun legacyBackupRoundTripKeepsScriptsAndDoesNotReplaceTheLocalDirectory()=runBlocking {
        val expected=CacheExportOptions(format=1,useReplace=false,episodeName="章节",directory="local-granted-uri")
        val restored=CacheExportOptions(directory=expected.directory).restoreLegacyPreferenceMap(expected.toLegacyPreferenceMap())
        assertEquals(expected,restored)
        assertFalse(restored.toLegacyPreferenceMap().containsValue(expected.directory))
    }
    @Test fun unknownProtoFieldsSurviveASettingsWrite()=runBlocking {
        val known=CacheExportSettings.getDefaultInstance().withOptions(CacheExportOptions()).toByteArray()
        // Unknown field 99 (varint) = 7.
        file.writeBytes(known+byteArrayOf(0x98.toByte(),0x06,0x07))
        val repo=repository(); repo.update { it.copy(format=1) }
        assertTrue(file.readBytes().toList().windowed(3).contains(listOf(0x98.toByte(),0x06.toByte(),0x07.toByte())))
        assertEquals(1,repo.current().format)
    }
    @Test fun corruptionIsReportedAndDoesNotEraseTheOriginalFileOrLegacyValues()=runBlocking {
        val corrupt=byteArrayOf(0x0a,0x7f)
        file.writeBytes(corrupt); prefs.edit().putInt(PreferKey.exportType,2).commit()
        assertTrue(runCatching { repository().preferences.first() }.isFailure)
        assertArrayEquals(corrupt,file.readBytes()); assertTrue(prefs.contains(PreferKey.exportType))
    }
}
