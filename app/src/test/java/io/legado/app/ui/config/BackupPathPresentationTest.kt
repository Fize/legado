package io.legado.app.ui.config

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPathPresentationTest {
    @Test fun missingAndBlankPathsShowTheCurrentDefaultLabel() {
        listOf(null,"","  ").forEach { assertEquals("Default\n/private/books",backupPathSummary(it,"/private/books","Default")) }
    }
    @Test fun selectedFileAndContentUrisRemainLiteral() {
        listOf("/books/English name", "content://provider/tree/中文").forEach { assertEquals(it,backupPathSummary(it,"/default","默认")) }
    }
    @Test fun changingLanguageOnlyChangesDefaultPresentation() {
        assertEquals("默认\n/default",backupPathSummary(null,"/default","默认"))
        assertEquals("/selected",backupPathSummary("/selected","/default","Default"))
        assertEquals("/selected",backupPathSummary("/selected","/default","默认"))
    }
}
