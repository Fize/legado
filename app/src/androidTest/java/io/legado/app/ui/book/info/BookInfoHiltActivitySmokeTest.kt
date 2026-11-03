package io.legado.app.ui.book.info

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookInfoHiltActivitySmokeTest {

    @Test
    fun activityCreatesInjectedViewModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<BookInfoActivity>(
            Intent(context, BookInfoActivity::class.java)
                .putExtra("bookUrl", "book-info-hilt-smoke://missing"),
        ).use { scenario ->
            scenario.onActivity { activity -> assertFalse(activity.isFinishing) }
        }
    }
}
