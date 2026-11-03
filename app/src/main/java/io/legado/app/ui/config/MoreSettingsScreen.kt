package io.legado.app.ui.config

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.main.my.MyAction
import io.legado.app.ui.main.my.MyPreferenceScreen

@Composable
fun MoreSettingsScreen(
    onBack: () -> Unit,
    onAction: (MyAction) -> Unit,
    onThemeModeChanged: () -> Unit,
) {
    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.reader_menu_more),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        MyPreferenceScreen(
            isMore = true,
            showCustomizeDialog = false,
            onDismissCustomization = {},
            onAction = onAction,
            onThemeModeChanged = onThemeModeChanged,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}
