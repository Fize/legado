package io.legado.app.ui.book.info

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookInfoRoute
import io.legado.app.ui.navigation.BookshelfRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookInfoEntryHiltTest {
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(10)
        assertTrue("Detail entry did not reach the expected state", condition())
    }

    @Test fun theActivityFactoryInjectsAndRetainsTheEntryOwnerAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BookInfoRoute("entry-hilt://missing")
        val scenario = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfRoute))
        lateinit var first: BookInfoEntryViewModel
        try {
            scenario.onActivity {
                first = ViewModelProvider(it)[BookInfoEntryViewModel::class.java]
                first.initialize(route)
            }
            await { first.state.value.request != null && !first.state.value.loading }
            val claimed = checkNotNull(first.state.value.request)
            val lease = checkNotNull(first.state.value.bootstrap).lease
            assertEquals(route.requestId, claimed.id)
            assertEquals(route.previewKey, claimed.previewKey)
            assertEquals(claimed, appDb.bookInfoRequestDao.get(route.requestId))
            scenario.recreate()
            scenario.onActivity {
                val retained = ViewModelProvider(it)[BookInfoEntryViewModel::class.java]
                assertSame(first, retained)
                assertSame(lease, retained.state.value.bootstrap!!.lease)
                assertEquals(claimed, retained.state.value.request)
            }
        } finally { scenario.close() }
        await { appDb.bookInfoRequestDao.get(route.requestId) == null }
    }
}
