package io.legado.app.ui.menu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Issue1008MenuContractTest {

    @Test
    fun sourceIconsFollowReportedMenuContract() {
        val sourceLoginDialog = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        )
        assertTrue(sourceLoginDialog.contains("stringResource(R.string.show_login_header)"))
        assertTrue(sourceLoginDialog.contains("painterResource(R.drawable.ic_add_online)"))

        val bookSourceActivity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt"
        )
        assertTrue(bookSourceActivity.contains("stringResource(R.string.group_sources_by_domain)"))
        assertTrue(bookSourceActivity.contains("iconRes = R.drawable.ic_add_online"))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
