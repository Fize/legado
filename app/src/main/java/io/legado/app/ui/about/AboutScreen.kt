package io.legado.app.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem

enum class AboutAction {
    Contributors,
    CheckUpdate,
    CheckBetaUpdate,
    CrashLogs,
    SaveLog,
    CreateHeapDump,
    License,
    Disclaimer,
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onShare: () -> Unit,
    onRate: () -> Unit,
    onAction: (AboutAction) -> Unit,
) {
    val appSummary = stringResource(R.string.about_description)
    val gzh = stringResource(R.string.legado_gzh)
    val primaryColor = MaterialTheme.colorScheme.primary
    val annotatedSummary = remember(appSummary, gzh, primaryColor) {
        buildAnnotatedString {
            append(appSummary)
            val start = appSummary.indexOf(gzh)
            if (start >= 0) {
                addStyle(SpanStyle(color = primaryColor), start, start + gzh.length)
            }
        }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.about),
                onNavigationClick = onBack,
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.share),
                        )
                    }
                    IconButton(onClick = onRate) {
                        Icon(
                            painter = painterResource(R.drawable.ic_scoring),
                            contentDescription = stringResource(R.string.scoring),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(
                        text = annotatedSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            AboutOptions(onAction, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun AboutOptions(onAction: (AboutAction) -> Unit, modifier: Modifier = Modifier) {
    val versionSummary = "${stringResource(R.string.version)} ${appInfo.versionName}"
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        item("contributors") {
            PreferenceItem(
                title = stringResource(R.string.contributors),
                summary = stringResource(R.string.contributors_summary),
                onClick = { onAction(AboutAction.Contributors) },
            )
        }
        item("update_log") {
            PreferenceItem(
                title = stringResource(R.string.update_log),
                summary = versionSummary,
                onClick = { onAction(AboutAction.CheckUpdate) },
            )
        }
        item("check_update") {
            PreferenceItem(stringResource(R.string.check_update)) { onAction(AboutAction.CheckUpdate) }
        }
        item("check_beta_update") {
            PreferenceItem(stringResource(R.string.check_beta_update)) { onAction(AboutAction.CheckBetaUpdate) }
        }
        item("otherCategory") { PreferenceCategoryHeader(title = stringResource(R.string.other)) }
        item("crashLog") {
            PreferenceItem(stringResource(R.string.crash_log)) { onAction(AboutAction.CrashLogs) }
        }
        item("saveLog") {
            PreferenceItem(stringResource(R.string.save_log)) { onAction(AboutAction.SaveLog) }
        }
        item("createHeapDump") {
            PreferenceItem(stringResource(R.string.create_heap_dump)) { onAction(AboutAction.CreateHeapDump) }
        }
        item("license") {
            PreferenceItem(stringResource(R.string.license)) { onAction(AboutAction.License) }
        }
        item("disclaimer") {
            PreferenceItem(stringResource(R.string.disclaimer)) { onAction(AboutAction.Disclaimer) }
        }
    }
}
