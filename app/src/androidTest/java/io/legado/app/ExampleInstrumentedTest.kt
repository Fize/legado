package io.legado.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun readerProviderDoesNotExposeRemovedRssRoutes() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${appContext.packageName}.readerProvider"
        val sourceUrl = "https://example.invalid/rss/${System.nanoTime()}"
        val queryUri = Uri.parse("content://$authority/rssSource/query")
            .buildUpon()
            .appendQueryParameter("url", sourceUrl)
            .build()
        assertNull(appContext.contentResolver.query(queryUri, null, null, null, null))
    }
}
