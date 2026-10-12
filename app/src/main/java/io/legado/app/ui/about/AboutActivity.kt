package io.legado.app.ui.about

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.openUrl
import io.legado.app.utils.share

class AboutActivity : BaseComposeActivity() {

    @Composable
    override fun Content() {
        val containerId = remember { View.generateViewId() }
        val appSummary = stringResource(R.string.about_description)
        val gzh = stringResource(R.string.legado_gzh)
        val primaryColor = MaterialTheme.colorScheme.primary
        val annotatedSummary = remember(appSummary, gzh, primaryColor) {
            buildAnnotatedString {
                append(appSummary)
                val start = appSummary.indexOf(gzh)
                if (start >= 0) {
                    addStyle(
                        style = SpanStyle(color = primaryColor),
                        start = start,
                        end = start + gzh.length
                    )
                }
            }
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.about),
                    onNavigationClick = { finish() },
                    actions = {
                        IconButton(
                            onClick = {
                                share(
                                    getString(R.string.app_share_description),
                                    getString(R.string.app_name)
                                )
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.share)
                            )
                        }
                        IconButton(
                            onClick = { openUrl("market://details?id=$packageName") }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_scoring),
                                contentDescription = stringResource(R.string.scoring)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Text(
                            text = annotatedSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FragmentContainerView(ctx).apply {
                                id = containerId
                                val fTag = "aboutFragment"
                                val existing = supportFragmentManager.findFragmentByTag(fTag)
                                val fragment = existing ?: AboutFragment()
                                supportFragmentManager.beginTransaction()
                                    .replace(containerId, fragment, fTag)
                                    .commitAllowingStateLoss()
                            }
                        }
                    )
                }
            }
        }
    }
}
