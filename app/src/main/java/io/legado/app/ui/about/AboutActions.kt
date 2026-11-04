package io.legado.app.ui.about

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
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

internal fun AppCompatActivity.shareApplication() {
    share(getString(R.string.app_share_description), getString(R.string.app_name))
}

internal fun AppCompatActivity.rateApplication() {
    openUrl("market://details?id=$packageName")
}

internal fun performAboutAction(activity: AppCompatActivity, action: AboutAction) {
    when (action) {
        AboutAction.Contributors -> activity.openUrl(activity.getString(R.string.contributors_url))
        AboutAction.CrashLogs -> activity.showDialogFragment<CrashLogsDialog>()
        AboutAction.SaveLog -> saveLog()
        AboutAction.CreateHeapDump -> createHeapDump()
        AboutAction.License -> activity.showMdFile(activity.getString(R.string.license), "LICENSE.md")
        AboutAction.Disclaimer -> activity.showMdFile(activity.getString(R.string.disclaimer), "disclaimer.md")
    }
}

private fun AppCompatActivity.showMdFile(title: String, fileName: String) {
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
