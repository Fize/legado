package io.legado.app.ui.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SourceImmersiveBackgroundTest {

    @Test
    fun sourceEditorsMatchTitleBarTransparency() {
        val titleBar = projectFile("src/main/java/io/legado/app/ui/widget/TitleBar.kt").readText()
        assertTrue(titleBar.contains("if (AppConfig.isEInkMode)"))
        assertTrue(titleBar.contains("else if (!opaque && context.transparentNavBar)"))

        listOf(
            "src/main/java/io/legado/app/ui/book/source/edit/BookSourceEditActivity.kt",
        ).forEach { path ->
            val source = projectFile(path).readText()
            assertTrue(source.contains("transparentNavBar && !AppConfig.isEInkMode"))
            assertTrue(source.contains("listOf(binding.tabLayout, binding.fieldNav)"))
            assertTrue(source.contains("if (transparentBar) Color.TRANSPARENT else backgroundColor"))
            assertTrue(source.contains("if (transparentBar) tabs.elevation = 0f"))
        }
    }

    @Test
    fun transparentBottomBarsUseVisibleBackgroundForContrast() {
        mapOf(
            "src/main/java/io/legado/app/ui/widget/SelectActionBar.kt" to
                "if (context.transparentNavBar) context.backgroundColor else context.bottomBackground",
            "src/main/java/io/legado/app/ui/widget/text/AccentStrokeTextView.kt" to
                "if (context.transparentNavBar) context.backgroundColor else context.bottomBackground",
            "src/main/java/io/legado/app/lib/theme/view/ThemeBottomNavigationVIew.kt" to
                "if (transparentNavBar) context.backgroundColor else context.bottomBackground",
        ).forEach { (path, expression) ->
            assertTrue(projectFile(path).readText().contains(expression))
        }
    }

    @Test
    fun transparentTitleBarsUseVisibleBackgroundForContrast() {
        val theme = projectFile("src/main/java/io/legado/app/lib/theme/MaterialValueHelper.kt")
            .readText()
        assertTrue(
            theme.contains("toolbarBackgroundColor(transparentBar, primaryColor, backgroundColor)")
        )

        val titleBar = projectFile("src/main/java/io/legado/app/ui/widget/TitleBar.kt")
            .readText()
        assertTrue(titleBar.contains("automaticForeground = themeMode == 0 && !opaque"))
        assertTrue(titleBar.contains("!AppConfig.isEInkMode && background?.alpha == 0"))
        assertTrue(titleBar.contains("context.getToolbarTextColor(true)"))
        assertTrue(titleBar.contains("toolbar.navigationIcon?.colorFilter = colorFilter"))
        assertTrue(titleBar.contains("toolbar.overflowIcon?.colorFilter = colorFilter"))
        assertTrue(titleBar.contains("findViewById<SearchView>(R.id.search_view)?.applyTint(color)"))
        assertTrue(titleBar.contains("findViewById<TabLayout>(R.id.tab_layout)"))
        assertTrue(titleBar.contains("setTabTextColors(tabUnselectedColor, color)"))
        assertTrue(titleBar.contains("R.color.md_light_secondary"))
        assertTrue(titleBar.contains("R.color.md_dark_secondary"))
        assertTrue(titleBar.contains("toolbar.menu.forEach { item ->"))

        val menu = projectFile("src/main/java/io/legado/app/utils/MenuExtensions.kt").readText()
        assertTrue(menu.contains("(impl.actionView as? SearchView)?.applyTint(tintColor)"))

        val activity = projectFile("src/main/java/io/legado/app/base/BaseActivity.kt").readText()
        val fragment = projectFile("src/main/java/io/legado/app/base/BaseFragment.kt").readText()
        assertTrue(
            activity.contains("transparentBar = titleBar?.usesTransparentForeground == true")
        )
        assertTrue(fragment.contains("titleBar?.post { titleBar.applyForegroundColor() }"))
    }

    @Test
    fun navigationBarsDisablePlatformContrastScrimOnAndroidQ() {
        val source = projectFile("src/main/java/io/legado/app/utils/ActivityExtensions.kt")
            .readText()
            .substringAfter("fun Activity.setNavigationBarColorAuto")
            .substringBefore("\nfun ")
        assertTrue(source.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)"))
        assertTrue(source.contains("window.isNavigationBarContrastEnforced = false"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
