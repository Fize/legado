package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.LifecycleHelp
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.model.BookCover
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent

@Keep
object ThemeConfig {

    const val DEFAULT_LIGHT_SURFACE_CONTAINER = 0xFFF1F3F5.toInt()
    const val DEFAULT_LIGHT_ACCENT = 0xFF3B5BA9.toInt()
    const val DEFAULT_LIGHT_BACKGROUND = 0xFFF8F9FA.toInt()

    const val DEFAULT_DARK_SURFACE_CONTAINER = 0xFF1A1C20.toInt()
    const val DEFAULT_DARK_ACCENT = 0xFF8FB0FF.toInt()
    const val DEFAULT_DARK_BACKGROUND = 0xFF121316.toInt()

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    fun isDarkTheme(): Boolean {
        return getTheme() == Theme.Dark
    }

    fun applyDayNight(context: Context, recreateAllActivities: Boolean = false) {
        applyTheme(context)
        initNightMode()
        BookCover.upDefaultCover()
        if (recreateAllActivities) {
            LifecycleHelp.recreateActivities()
        } else {
            postEvent(EventBus.RECREATE, "")
        }
    }

    fun applyDayNightInit(context: Context) {
        applyTheme(context)
        initNightMode()
    }

    private fun initNightMode() {
        val targetMode =
            if (AppConfig.isNightTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }

    @Suppress("UNUSED_PARAMETER")
    fun getBgImage(context: Context, metrics: DisplayMetrics): Drawable? = null

    fun getDurConfig(context: Context): Config {
        val isNight = AppConfig.isNightTheme
        return if (isNight) {
            getNightTheme(context)
        } else {
            getDayTheme(context)
        }
    }

    private fun getDayTheme(context: Context): Config {
        val followWallpaper = WallpaperTheme.isFollowEnabled(context)
        val primary = if (followWallpaper) {
            context.getPrefInt(PreferKey.cPrimary, DEFAULT_LIGHT_SURFACE_CONTAINER)
                .takeIf { ColorUtils.isColorLight(it) } ?: DEFAULT_LIGHT_SURFACE_CONTAINER
        } else {
            DEFAULT_LIGHT_SURFACE_CONTAINER
        }
        val accent = if (followWallpaper) {
            context.getPrefInt(PreferKey.cAccent, DEFAULT_LIGHT_ACCENT)
        } else {
            DEFAULT_LIGHT_ACCENT
        }
        val background = if (followWallpaper) {
            context.getPrefInt(PreferKey.cBackground, DEFAULT_LIGHT_BACKGROUND)
                .takeIf { ColorUtils.isColorLight(it) } ?: DEFAULT_LIGHT_BACKGROUND
        } else {
            DEFAULT_LIGHT_BACKGROUND
        }
        val bBackground = if (followWallpaper) {
            context.getPrefInt(PreferKey.cBBackground, DEFAULT_LIGHT_SURFACE_CONTAINER)
                .takeIf { ColorUtils.isColorLight(it) } ?: DEFAULT_LIGHT_SURFACE_CONTAINER
        } else {
            DEFAULT_LIGHT_SURFACE_CONTAINER
        }
        return Config(
            themeName = if (followWallpaper) "MaterialYouLight" else "ModernLight",
            isNightTheme = false,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bBackground.hexString}",
            transparentNavBar = false,
            backgroundImgPath = null,
            backgroundImgBlur = 0
        )
    }

    private fun getNightTheme(context: Context): Config {
        val followWallpaper = WallpaperTheme.isFollowEnabled(context)
        val primary = if (followWallpaper) {
            context.getPrefInt(PreferKey.cNPrimary, DEFAULT_DARK_SURFACE_CONTAINER)
                .takeIf { !ColorUtils.isColorLight(it) } ?: DEFAULT_DARK_SURFACE_CONTAINER
        } else {
            DEFAULT_DARK_SURFACE_CONTAINER
        }
        val accent = if (followWallpaper) {
            context.getPrefInt(PreferKey.cNAccent, DEFAULT_DARK_ACCENT)
        } else {
            DEFAULT_DARK_ACCENT
        }
        val background = if (followWallpaper) {
            context.getPrefInt(PreferKey.cNBackground, DEFAULT_DARK_BACKGROUND)
                .takeIf { !ColorUtils.isColorLight(it) } ?: DEFAULT_DARK_BACKGROUND
        } else {
            DEFAULT_DARK_BACKGROUND
        }
        val bBackground = if (followWallpaper) {
            context.getPrefInt(PreferKey.cNBBackground, DEFAULT_DARK_SURFACE_CONTAINER)
                .takeIf { !ColorUtils.isColorLight(it) } ?: DEFAULT_DARK_SURFACE_CONTAINER
        } else {
            DEFAULT_DARK_SURFACE_CONTAINER
        }
        return Config(
            themeName = if (followWallpaper) "MaterialYouDark" else "ModernDark",
            isNightTheme = true,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bBackground.hexString}",
            transparentNavBar = false,
            backgroundImgPath = null,
            backgroundImgBlur = 0
        )
    }

    /**
     * 更新主题
     */
    fun applyTheme(context: Context) = with(context) {
        when {
            AppConfig.isEInkMode -> {
                ThemeStore.editTheme(this)
                    .primaryColor(Color.WHITE)
                    .accentColor(Color.BLACK)
                    .backgroundColor(Color.WHITE)
                    .bottomBackground(Color.WHITE)
                    .transparentNavBar(false)
                    .apply()
            }

            AppConfig.isNightTheme -> {
                val followWallpaper = WallpaperTheme.isFollowEnabled(this)
                val primary = if (followWallpaper) {
                    getPrefInt(PreferKey.cNPrimary, DEFAULT_DARK_SURFACE_CONTAINER)
                        .takeIf { !ColorUtils.isColorLight(it) } ?: DEFAULT_DARK_SURFACE_CONTAINER
                } else {
                    DEFAULT_DARK_SURFACE_CONTAINER
                }
                val accent = if (followWallpaper) {
                    getPrefInt(PreferKey.cNAccent, DEFAULT_DARK_ACCENT)
                } else {
                    DEFAULT_DARK_ACCENT
                }
                val background = if (followWallpaper) {
                    getPrefInt(PreferKey.cNBackground, DEFAULT_DARK_BACKGROUND)
                        .takeIf { !ColorUtils.isColorLight(it) } ?: DEFAULT_DARK_BACKGROUND
                } else {
                    DEFAULT_DARK_BACKGROUND
                }
                val bBackground = if (followWallpaper) {
                    getPrefInt(PreferKey.cNBBackground, DEFAULT_DARK_SURFACE_CONTAINER)
                        .takeIf { !ColorUtils.isColorLight(it) } ?: DEFAULT_DARK_SURFACE_CONTAINER
                } else {
                    DEFAULT_DARK_SURFACE_CONTAINER
                }
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(false)
                    .apply()
            }

            else -> {
                val followWallpaper = WallpaperTheme.isFollowEnabled(this)
                val primary = if (followWallpaper) {
                    getPrefInt(PreferKey.cPrimary, DEFAULT_LIGHT_SURFACE_CONTAINER)
                        .takeIf { ColorUtils.isColorLight(it) } ?: DEFAULT_LIGHT_SURFACE_CONTAINER
                } else {
                    DEFAULT_LIGHT_SURFACE_CONTAINER
                }
                val accent = if (followWallpaper) {
                    getPrefInt(PreferKey.cAccent, DEFAULT_LIGHT_ACCENT)
                } else {
                    DEFAULT_LIGHT_ACCENT
                }
                val background = if (followWallpaper) {
                    getPrefInt(PreferKey.cBackground, DEFAULT_LIGHT_BACKGROUND)
                        .takeIf { ColorUtils.isColorLight(it) } ?: DEFAULT_LIGHT_BACKGROUND
                } else {
                    DEFAULT_LIGHT_BACKGROUND
                }
                val bBackground = if (followWallpaper) {
                    getPrefInt(PreferKey.cBBackground, DEFAULT_LIGHT_SURFACE_CONTAINER)
                        .takeIf { ColorUtils.isColorLight(it) } ?: DEFAULT_LIGHT_SURFACE_CONTAINER
                } else {
                    DEFAULT_LIGHT_SURFACE_CONTAINER
                }
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(false)
                    .apply()
            }
        }
    }

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String,
        var transparentNavBar: Boolean,
        var backgroundImgPath: String?,
        var backgroundImgBlur: Int
    ) {

        override fun hashCode(): Int {
            return GSON.toJson(this).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName
                        && other.isNightTheme == isNightTheme
                        && other.primaryColor == primaryColor
                        && other.accentColor == accentColor
                        && other.backgroundColor == backgroundColor
                        && other.bottomBackground == bottomBackground
                        && other.transparentNavBar == transparentNavBar
                        && other.backgroundImgPath == backgroundImgPath
                        && other.backgroundImgBlur == backgroundImgBlur
            }
            return false
        }

        fun toMap() = mapOf(
            "themeName" to themeName,
            "isNightTheme" to isNightTheme,
            "primaryColor" to primaryColor,
            "accentColor" to accentColor,
            "backgroundColor" to backgroundColor,
            "bottomBackground" to bottomBackground,
            "transparentNavBar" to transparentNavBar,
            "backgroundImgPath" to backgroundImgPath,
            "backgroundImgBlur" to backgroundImgBlur
        )

    }

}
