package io.legado.app.help.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseInfoTest {

    @Test
    fun releaseParserKeepsOnlyUploadedApkAssetsAndUsesReleaseTagVersion() {
        val release = GithubRelease(
            assets = listOf(
                asset("legado_official_vc123.apk"),
                asset("notes.txt", contentType = "text/plain"),
                asset("legado_uploading.apk", state = "new"),
            ),
            body = "release notes",
            isPreRelease = false,
            tagName = "v3.26.1",
        )

        val parsed = release.gitReleaseToAppReleaseInfo()

        assertEquals(1, parsed.size)
        assertEquals(AppVariant.OFFICIAL, parsed.single().appVariant)
        assertEquals("3.26.1", parsed.single().versionName)
        assertEquals(123L, parsed.single().versionCode)
        assertEquals("release notes", parsed.single().note)
        assertTrue(parsed.single().downloadUrl.isNotBlank())
    }

    @Test
    fun prereleaseAssetsMapReleaseAndReleaseAChannels() {
        val release = GithubRelease(
            assets = listOf(
                asset("legado_release_vc200.apk"),
                asset("legado_releasea_vc201.apk"),
            ),
            body = "beta notes",
            isPreRelease = true,
            tagName = "3.27.0",
        )

        val parsed = release.gitReleaseToAppReleaseInfo().associateBy { it.name }

        assertEquals(AppVariant.BETA_RELEASE, parsed.getValue("legado_release_vc200.apk").appVariant)
        assertEquals(AppVariant.BETA_RELEASEA, parsed.getValue("legado_releasea_vc201.apk").appVariant)
        assertTrue(parsed.values.all { it.versionName == "3.27.0" && it.downloadUrl.isNotBlank() })
    }

    private fun asset(
        name: String,
        contentType: String = "application/vnd.android.package-archive",
        state: String = "uploaded",
    ) = Asset(
        apkUrl = "https://downloads.example.test/$name",
        contentType = contentType,
        createdAt = "2026-10-16T00:00:00Z",
        downloadCount = 0,
        id = name.hashCode(),
        name = name,
        state = state,
        url = "https://api.example.test/assets/${name.hashCode()}",
        size = 1024,
    )
}
