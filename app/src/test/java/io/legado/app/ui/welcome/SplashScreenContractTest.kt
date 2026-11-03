package io.legado.app.ui.welcome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplashScreenContractTest {

    @Test
    fun `launcher uses AndroidX splash screen on the compose app activity`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val styles = projectFile("src/main/res/values/styles.xml").readText()
        val activity = projectFile("src/main/java/io/legado/app/ui/AppActivity.kt").readText()
        val versions = projectFile("../gradle/libs.versions.toml").readText()

        assertTrue(manifest.contains("android:name=\".ui.AppActivity\""))
        assertTrue(manifest.contains("android:theme=\"@style/AppTheme.Starting\""))
        assertTrue(manifest.contains("android:targetActivity=\".ui.AppActivity\""))
        assertTrue(manifest.contains("android:name=\".ui.welcome.WelcomeActivity\""))
        assertFalse(
            manifest.contains(
                "<activity\n            android:name=\".ui.welcome.WelcomeActivity\"",
            ),
        )
        assertTrue(styles.contains("parent=\"Theme.SplashScreen\""))
        assertTrue(styles.contains("postSplashScreenTheme"))
        assertTrue(activity.contains("installSplashScreen()"))
        assertTrue(versions.contains("coreSplashscreen = \"1.2.0\""))
        assertFalse(projectFile("src/main/java/io/legado/app/ui/welcome/WelcomeActivity.kt").exists())
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::exists)
            ?: File("app/$pathInApp")
}
