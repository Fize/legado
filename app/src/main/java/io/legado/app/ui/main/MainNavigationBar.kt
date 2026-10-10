package io.legado.app.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme

data class MainNavTab(
    val tabId: Int,
    @DrawableRes val selectedIconRes: Int,
    @DrawableRes val unselectedIconRes: Int,
    @StringRes val titleRes: Int,
    val badgeCount: Int = 0,
)

@Composable
fun MainNavigationBar(
    selectedTabId: Int,
    showDiscovery: Boolean,
    upBooksCount: Int,
    onTabSelected: (Int) -> Unit,
    onTabReselected: (Int) -> Unit,
) {
    val eInkMode = AppConfig.isEInkMode
    val tabs = buildList {
        add(
            MainNavTab(
                tabId = 0,
                selectedIconRes = R.drawable.ic_bottom_books_s,
                unselectedIconRes = R.drawable.ic_bottom_books_e,
                titleRes = R.string.bookshelf,
                badgeCount = upBooksCount,
            )
        )
        if (showDiscovery) {
            add(
                MainNavTab(
                    tabId = 1,
                    selectedIconRes = R.drawable.ic_bottom_explore_s,
                    unselectedIconRes = R.drawable.ic_bottom_explore_e,
                    titleRes = R.string.discovery,
                )
            )
        }
        add(
            MainNavTab(
                tabId = 2,
                selectedIconRes = R.drawable.ic_bottom_person_s,
                unselectedIconRes = R.drawable.ic_bottom_person_e,
                titleRes = R.string.my,
            )
        )
    }

    LegadoTheme(eInkMode = eInkMode) {
        val colorScheme = MaterialTheme.colorScheme
        Column(modifier = Modifier.fillMaxWidth()) {
            if (eInkMode) {
                HorizontalDivider(thickness = 1.dp, color = Color.Black)
            } else {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = if (eInkMode) Color.White else colorScheme.surfaceContainer,
                contentColor = colorScheme.onSurface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets.navigationBars,
            ) {
                tabs.forEach { tab ->
                    val selected = selectedTabId == tab.tabId
                    val iconRes = if (selected) tab.selectedIconRes else tab.unselectedIconRes
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (selected) {
                                onTabReselected(tab.tabId)
                            } else {
                                onTabSelected(tab.tabId)
                            }
                        },
                        icon = {
                            if (tab.badgeCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = if (eInkMode) Color.Black else colorScheme.primary,
                                            contentColor = if (eInkMode) Color.White else colorScheme.onPrimary,
                                        ) {
                                            Text(text = tab.badgeCount.coerceAtMost(999).toString())
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(id = iconRes),
                                        contentDescription = stringResource(id = tab.titleRes),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else {
                                Icon(
                                    painter = painterResource(id = iconRes),
                                    contentDescription = stringResource(id = tab.titleRes),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(id = tab.titleRes),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = if (eInkMode) Color.Black else colorScheme.onSecondaryContainer,
                            selectedTextColor = if (eInkMode) Color.Black else colorScheme.onSurface,
                            indicatorColor = if (eInkMode) Color.Transparent else colorScheme.secondaryContainer,
                            unselectedIconColor = if (eInkMode) Color.DarkGray else colorScheme.onSurfaceVariant,
                            unselectedTextColor = if (eInkMode) Color.DarkGray else colorScheme.onSurfaceVariant,
                        )
                    )
                }
            }
        }
    }
}

