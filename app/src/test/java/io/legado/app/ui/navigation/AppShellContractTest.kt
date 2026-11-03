package io.legado.app.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppShellContractTest {

    @Test
    fun `application shell uses Navigation 3 and adaptive navigation`() {
        val source = projectFile("src/main/java/io/legado/app/ui/navigation/LegadoApp.kt").readText()

        assertTrue(source.contains("NavigationSuiteScaffold("))
        assertTrue(source.contains("NavDisplay("))
        assertTrue(source.contains("rememberAppNavigationState(startRoute, initialDestination)"))
        assertTrue(source.contains("BookshelfRootScreen("))
        assertTrue(source.contains("ExploreRootScreen("))
        assertTrue(source.contains("MyRootScreen("))
        assertTrue(source.contains("AboutScreen("))
        assertTrue(source.contains("navigator.navigate(AboutRoute)"))
        assertTrue(source.contains("AllBookmarksScreen("))
        assertTrue(source.contains("navigator.navigate(AllBookmarksRoute)"))
        assertTrue(source.contains("MoreSettingsScreen("))
        assertTrue(source.contains("navigator.navigate(SettingsRoute())"))
        assertTrue(source.contains("navigator.navigate(SettingsRoute(SettingsSection.Theme))"))
        assertTrue(source.contains("ThemeSettingsScreen("))
        assertTrue(source.contains("FileManagerScreen("))
        assertTrue(source.contains("navigator.navigate(FileManagerRoute)"))
        assertTrue(source.contains("ReadRecordScreen("))
        assertTrue(source.contains("navigator.navigate(ReadRecordRoute)"))
        assertTrue(source.contains("TextTocRuleScreen("))
        assertTrue(source.contains("navigator.navigate(TextTocRuleRoute)"))
        assertTrue(source.contains("DictionaryRuleScreen("))
        assertTrue(source.contains("navigator.navigate(DictionaryRuleRoute)"))
        assertTrue(source.contains("ReplaceRuleScreen("))
        assertTrue(source.contains("navigator.navigate(ReplaceRuleRoute)"))
        assertTrue(source.contains("RuleSubScreen("))
        assertTrue(source.contains("navigator.navigate(RuleSubscriptionRoute)"))
    }

    @Test
    fun `my root is rendered directly by the compose app shell`() {
        val app = projectFile("src/main/java/io/legado/app/ui/navigation/LegadoApp.kt").readText()
        val source = projectFile("src/main/java/io/legado/app/ui/main/my/MyScreen.kt").readText()
        val more = projectFile("src/main/java/io/legado/app/ui/config/MoreSettingsScreen.kt").readText()

        assertFalse(source.contains("AndroidView"))
        assertFalse(source.contains("MyPreferenceFragment"))
        assertTrue(source.contains("fun MyRootScreen("))
        assertTrue(app.contains("MyRootScreen("))
        assertTrue(more.contains("MyPreferenceScreen("))
        assertTrue(app.contains("navigator.navigate(SettingsRoute())"))
        assertFalse(File("app/src/main/java/io/legado/app/ui/main/my/MyFragment.kt").exists())
    }

    @Test
    fun `launcher shortcuts and shares enter the compose app activity`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val shortcuts = projectFile("src/main/java/io/legado/app/api/ShortCuts.kt").readText()
        val appActivity = projectFile("src/main/java/io/legado/app/ui/AppActivity.kt").readText()

        assertTrue(manifest.contains("android:targetActivity=\".ui.AppActivity\""))
        assertTrue(shortcuts.contains("buildIntent<AppActivity>(context)"))
        assertTrue(appActivity.contains("dispatchExternalIntent()"))
        assertTrue(appActivity.contains("Intent.ACTION_PROCESS_TEXT"))
        assertTrue(appActivity.contains("MediaButtonReceiver.readAloud"))
        assertFalse(File("app/src/main/java/io/legado/app/receiver/SharedReceiverActivity.kt").exists())
    }

    @Test
    fun `about screen has no separate activity destination`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val appActivity = projectFile("src/main/java/io/legado/app/ui/AppActivity.kt").readText()

        assertFalse(manifest.contains(".ui.about.AboutActivity"))
        assertFalse(projectFile("src/main/java/io/legado/app/ui/about/AboutActions.kt").readText().contains("class AboutActivity"))
        assertTrue(appActivity.contains("initialDestination = initialDestination"))
        assertTrue(appActivity.contains("onExit = ::finish"))
        assertTrue(appActivity.contains("Json.encodeToString<AppRoute>(destination)"))
    }

    @Test
    fun `theme settings has no fragment or config activity destination`() {
        val app = projectFile("src/main/java/io/legado/app/ui/navigation/LegadoApp.kt").readText()

        assertTrue(app.contains("navigator.navigate(SettingsRoute(SettingsSection.Theme))"))
        assertFalse(File("app/src/main/java/io/legado/app/ui/main/my/MyFragment.kt").exists())
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/ConfigActivity.kt").exists())
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/ThemeConfigFragment.kt").exists())
    }

    @Test
    fun `legacy welcome customization is removed with the dedicated splash activity`() {
        val route = projectFile("src/main/java/io/legado/app/ui/navigation/AppRoute.kt").readText()
        val app = projectFile("src/main/java/io/legado/app/ui/navigation/LegadoApp.kt").readText()

        assertFalse(route.contains("Welcome,"))
        assertFalse(app.contains("WelcomeSettingsScreen"))
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/WelcomeSettingsScreen.kt").exists())
    }

    @Test
    fun `cover settings has a navigation 3 destination without fragment host`() {
        val app = projectFile("src/main/java/io/legado/app/ui/navigation/LegadoApp.kt").readText()

        assertTrue(app.contains("SettingsSection.Cover"))
        assertTrue(app.contains("CoverSettingsScreen("))
        assertFalse(app.contains("CoverConfigFragment"))
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/CoverConfigFragment.kt").exists())
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/CoverRuleConfigDialog.kt").exists())
    }

    @Test
    fun `cover font settings has a navigation 3 destination without fragment host`() {
        val app = projectFile("src/main/java/io/legado/app/ui/navigation/LegadoApp.kt").readText()

        assertTrue(app.contains("SettingsSection.CoverFont"))
        assertTrue(app.contains("CoverFontSettingsScreen(onBack = onNavigateBack)"))
        assertFalse(app.contains("CoverFontConfigFragment"))
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/CoverFontConfigFragment.kt").exists())
    }

    @Test
    fun `other settings has a navigation 3 destination without its legacy fragment`() {
        val app = projectFile("src/main/java/io/legado/app/ui/navigation/LegadoApp.kt").readText()
        val otherSettings = projectFile("src/main/java/io/legado/app/ui/config/OtherSettingsScreen.kt").readText()

        assertTrue(app.contains("navigator.navigate(SettingsRoute(SettingsSection.Other))"))
        assertTrue(app.contains("OtherSettingsScreen(onBack = onNavigateBack)"))
        assertFalse(app.contains("OtherConfigFragment"))
        assertFalse(otherSettings.contains("supportFragmentManager"))
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/OtherConfigFragment.kt").exists())
    }

    @Test
    fun `backup settings and logs use typed compose destinations`() {
        val app = projectFile("src/main/java/io/legado/app/ui/navigation/LegadoApp.kt").readText()
        val backup = projectFile("src/main/java/io/legado/app/ui/config/BackupSettingsScreen.kt").readText()

        assertTrue(app.contains("navigator.navigate(SettingsRoute(SettingsSection.Backup))"))
        assertTrue(app.contains("BackupSettingsScreen("))
        assertTrue(app.contains("onNavigate(AppLogRoute)"))
        assertTrue(app.contains("AppLogScreen(onBack = onNavigateBack)"))
        assertFalse(backup.contains("supportFragmentManager"))
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/ConfigActivity.kt").exists())
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/ConfigTag.kt").exists())
        assertFalse(File("app/src/main/java/io/legado/app/ui/config/BackupConfigFragment.kt").exists())
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
}
