package io.legado.app.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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

private fun buildMainNavTabs(
    showDiscovery: Boolean,
    upBooksCount: Int,
): List<MainNavTab> = buildList {
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

@Composable
private fun MainNavTabIcon(
    tab: MainNavTab,
    selected: Boolean,
    eInkMode: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val iconRes = if (selected) tab.selectedIconRes else tab.unselectedIconRes
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
}

@Composable
fun MainNavigationBar(
    selectedTabId: Int,
    showDiscovery: Boolean,
    upBooksCount: Int,
    onTabSelected: (Int) -> Unit,
    onTabReselected: (Int) -> Unit,
) {
    val eInkMode = AppConfig.isEInkMode
    val tabs = buildMainNavTabs(showDiscovery, upBooksCount)

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
                            MainNavTabIcon(
                                tab = tab,
                                selected = selected,
                                eInkMode = eInkMode,
                            )
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

@Composable
fun MainNavigationRail(
    selectedTabId: Int,
    showDiscovery: Boolean,
    upBooksCount: Int,
    onTabSelected: (Int) -> Unit,
    onTabReselected: (Int) -> Unit,
) {
    val eInkMode = AppConfig.isEInkMode
    val tabs = buildMainNavTabs(showDiscovery, upBooksCount)

    LegadoTheme(eInkMode = eInkMode) {
        val colorScheme = MaterialTheme.colorScheme
        Row(modifier = Modifier.fillMaxHeight()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = if (eInkMode) Color.White else colorScheme.surfaceContainer,
                contentColor = colorScheme.onSurface,
                windowInsets = WindowInsets.systemBars.only(
                    WindowInsetsSides.Vertical + WindowInsetsSides.Start
                ),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    tabs.forEach { tab ->
                        val selected = selectedTabId == tab.tabId
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    onTabReselected(tab.tabId)
                                } else {
                                    onTabSelected(tab.tabId)
                                }
                            },
                            icon = {
                                MainNavTabIcon(
                                    tab = tab,
                                    selected = selected,
                                    eInkMode = eInkMode,
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(id = tab.titleRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            alwaysShowLabel = true,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = if (eInkMode) Color.Black else colorScheme.onSecondaryContainer,
                                selectedTextColor = if (eInkMode) Color.Black else colorScheme.onSurface,
                                indicatorColor = if (eInkMode) Color.Transparent else colorScheme.secondaryContainer,
                                unselectedIconColor = if (eInkMode) Color.DarkGray else colorScheme.onSurfaceVariant,
                                unselectedTextColor = if (eInkMode) Color.DarkGray else colorScheme.onSurfaceVariant,
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            if (eInkMode) {
                VerticalDivider(thickness = 1.dp, color = Color.Black)
            } else {
                VerticalDivider(
                    thickness = 0.5.dp,
                    color = colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

