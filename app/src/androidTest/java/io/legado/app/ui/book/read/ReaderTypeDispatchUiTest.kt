package io.legado.app.ui.book.read

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.navigation.AppRoute
import io.legado.app.ui.navigation.MangaReaderRoute
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.bookReaderIntent
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderTypeDispatchUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val id = UUID.randomUUID().toString()

    @Test
    fun productionDispatchKeepsEveryReaderTypeOnItsOwnedHost() {
        val originalMangaPreference = preferences.all[PreferKey.showMangaUi]
        try {
            assertTrue(preferences.edit().putBoolean(PreferKey.showMangaUi, true).commit())

            for (extension in listOf("txt", "epub", "pdf")) {
                val fileName = "dispatch-$id.$extension"
                val url = "content://fixture/$fileName"
                localText(fileName).assertTextHost(url)
            }
            val onlineTextUrl = "https://source.invalid/$id"
            context.bookReaderIntent(
                book("online text", BookType.text, onlineTextUrl),
            ).assertTextHost(onlineTextUrl)

            val mangaUrl = "https://manga.invalid/$id"
            val mangaIntent = context.bookReaderIntent(
                book("manga", BookType.image, mangaUrl),
            )
            assertEquals(AppActivity::class.java.name, mangaIntent.component?.className)
            val serializedRoute = requireNotNull(mangaIntent.getStringExtra("initialDestination"))
            val mangaRoute = Json.decodeFromString<AppRoute>(serializedRoute)
            assertTrue("Expected the Compose manga destination, got $mangaRoute", mangaRoute is MangaReaderRoute)
            mangaRoute as MangaReaderRoute
            assertEquals(mangaUrl, mangaRoute.bookUrl)

            val audioUrl = "https://audio.invalid/$id"
            val audioIntent = context.bookReaderIntent(book("audio", BookType.audio, audioUrl))
            assertEquals(AudioPlayActivity::class.java.name, audioIntent.component?.className)
            assertEquals(audioUrl, audioIntent.getStringExtra("bookUrl"))

            val videoUrl = "https://video.invalid/$id"
            val videoIntent = context.bookReaderIntent(book("video", BookType.video, videoUrl))
            assertEquals(VideoPlayerActivity::class.java.name, videoIntent.component?.className)
            assertEquals(videoUrl, videoIntent.getStringExtra("bookUrl"))
        } finally {
            preferences.edit().apply {
                if (originalMangaPreference is Boolean) {
                    putBoolean(PreferKey.showMangaUi, originalMangaPreference)
                } else {
                    remove(PreferKey.showMangaUi)
                }
            }.commit()
        }
    }

    @Test
    fun productionDispatchCarriesRequestedTocAndPositionWithoutCreatingAnotherHost() {
        val originalMangaPreference = preferences.all[PreferKey.showMangaUi]
        try {
            assertTrue(preferences.edit().putBoolean(PreferKey.showMangaUi, true).commit())

            val textUrl = "https://text.invalid/$id"
            val textIntent = context.bookReaderIntent(book("text", BookType.text, textUrl)) {
                putExtra("index", 7)
                putExtra("chapterPos", 321)
            }
            assertEquals(ReadBookActivity::class.java.name, textIntent.component?.className)
            assertEquals(textUrl, textIntent.getStringExtra("bookUrl"))
            assertEquals(7, textIntent.getIntExtra("index", -1))
            assertEquals(321, textIntent.getIntExtra("chapterPos", -1))

            val mangaUrl = "https://manga-position.invalid/$id"
            val mangaIntent = context.bookReaderIntent(book("manga position", BookType.image, mangaUrl)) {
                putExtra("index", 4)
                putExtra("chapterPos", 19)
            }
            val route = Json.decodeFromString<AppRoute>(
                requireNotNull(mangaIntent.getStringExtra("initialDestination")),
            ) as MangaReaderRoute
            assertEquals(mangaUrl, route.bookUrl)
            assertEquals(4, route.chapterIndex)
            assertEquals(19, route.pageIndex)
        } finally {
            preferences.edit().apply {
                if (originalMangaPreference is Boolean) {
                    putBoolean(PreferKey.showMangaUi, originalMangaPreference)
                } else {
                    remove(PreferKey.showMangaUi)
                }
            }.commit()
        }
    }

    private fun localText(fileName: String): Intent {
        return context.bookReaderIntent(
            Book(
                bookUrl = "content://fixture/$fileName",
                origin = BookType.localTag,
                originName = fileName,
                name = fileName,
                type = BookType.local or BookType.text,
            ),
        )
    }

    private fun book(name: String, type: Int, url: String) = Book(
        bookUrl = url,
        origin = "https://source.invalid/$id",
        originName = "Fixture source",
        name = "$name $id",
        type = type,
    )

    private fun Intent.assertTextHost(expectedUrl: String) {
        assertEquals(ReadBookActivity::class.java.name, component?.className)
        assertEquals(expectedUrl, getStringExtra("bookUrl"))
    }
}
