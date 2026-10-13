package io.legado.app.ui.about

import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.AppLog
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File

class AboutActivity : BaseComposeActivity() {

    @Composable
    override fun Content() {
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
                AboutOptions(modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    @Composable
    private fun AboutOptions(modifier: Modifier = Modifier) {
        val versionSummary = "${stringResource(R.string.version)} ${appInfo.versionName}"
        LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
            item("contributors") {
                PreferenceItem(
                    title = stringResource(R.string.contributors),
                    summary = stringResource(R.string.contributors_summary),
                    onClick = { openUrl(getString(R.string.contributors_url)) },
                )
            }
            item("update_log") {
                PreferenceItem(
                    title = stringResource(R.string.update_log),
                    summary = versionSummary,
                    onClick = { checkAppUpdate() },
                )
            }
            item("check_update") {
                PreferenceItem(stringResource(R.string.check_update), onClick = { checkAppUpdate() })
            }
            item("check_beta_update") {
                PreferenceItem(stringResource(R.string.check_beta_update), onClick = { checkAppUpdate(true) })
            }
            item("otherCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.other))
            }
            item("crashLog") {
                PreferenceItem(stringResource(R.string.crash_log), onClick = {
                    showDialogFragment<CrashLogsDialog>()
                })
            }
            item("saveLog") {
                PreferenceItem(stringResource(R.string.save_log), onClick = ::saveLog)
            }
            item("createHeapDump") {
                PreferenceItem(stringResource(R.string.create_heap_dump), onClick = ::createHeapDump)
            }
            item("license") {
                PreferenceItem(stringResource(R.string.license), onClick = {
                    showMdFile(getString(R.string.license), "LICENSE.md")
                })
            }
            item("disclaimer") {
                PreferenceItem(stringResource(R.string.disclaimer), onClick = {
                    showMdFile(getString(R.string.disclaimer), "disclaimer.md")
                })
            }
        }
    }

    private fun showMdFile(title: String, fileName: String) {
        val mdText = String(assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }

    private fun saveLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi("已保存至备份目录")
        }.onError { AppLog.put("保存日志出错\n${it.localizedMessage}", it, true) }
    }

    private fun createHeapDump() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            if (copyHeapDump(doc)) appCtx.toastOnUi("已保存至备份目录")
            else appCtx.toastOnUi("未找到堆转储文件")
        }.onError { AppLog.put("保存堆转储失败\n${it.localizedMessage}", it) }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")
        dumpLogcat(logcatFile)
        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)
        doc.find("logs.zip")?.delete()
        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()?.use(input::copyTo)
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use(input::copyTo)
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        runCatching {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use { process.inputStream.copyTo(it) }
        }.onFailure { AppLog.put("保存Logcat失败\n$it", it) }
    }
}
