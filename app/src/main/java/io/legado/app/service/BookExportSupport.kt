package io.legado.app.service

import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import kotlin.math.max

internal fun findPdfPageLineEnd(
    lineTops: IntArray,
    lineBottoms: IntArray,
    startLine: Int,
    availableHeight: Int
): Int {
    require(lineTops.size == lineBottoms.size)
    if (startLine !in lineTops.indices) return lineTops.size
    var endLine = startLine
    while (endLine < lineBottoms.size &&
        lineBottoms[endLine] - lineTops[startLine] <= availableHeight
    ) {
        endLine++
    }
    return maxOf(startLine + 1, endLine).coerceAtMost(lineTops.size)
}

internal sealed interface PdfContentBlock {
    data class Text(val value: String) : PdfContentBlock
    data class Image(val source: String) : PdfContentBlock
}

internal fun splitPdfContentBlocks(content: String): List<PdfContentBlock> {
    val blocks = arrayListOf<PdfContentBlock>()
    val matcher = AppPattern.imgPattern.matcher(content)
    var start = 0
    while (matcher.find()) {
        if (matcher.start() > start) {
            blocks.add(PdfContentBlock.Text(content.substring(start, matcher.start())))
        }
        matcher.group(1)?.let { blocks.add(PdfContentBlock.Image(it)) }
        start = matcher.end()
    }
    if (start < content.length) {
        blocks.add(PdfContentBlock.Text(content.substring(start)))
    }
    return blocks
}

internal fun imagePdfContentBlocks(
    content: String?,
    isVolume: Boolean
): List<PdfContentBlock.Image> {
    if (content == null) {
        if (isVolume) return emptyList()
        throw NoStackTraceException("图片章节正文未缓存")
    }
    val images = splitPdfContentBlocks(content).filterIsInstance<PdfContentBlock.Image>()
    if (images.isEmpty() && !isVolume) {
        throw NoStackTraceException("图片章节没有可导出的图片")
    }
    return images
}

internal fun calculatePdfBitmapSampleSize(
    width: Int,
    height: Int,
    requestedWidth: Int,
    requestedHeight: Int
): Int {
    if (width <= 0 || height <= 0 || requestedWidth <= 0 || requestedHeight <= 0) return 1
    val reduction = max(
        width / requestedWidth.toFloat(),
        height / requestedHeight.toFloat()
    )
    var sampleSize = 1
    while (sampleSize * 2f <= reduction) sampleSize *= 2
    return sampleSize
}

internal fun <T> replaceStagedExport(
    current: T?,
    backupCurrent: (T) -> T?,
    activateStaged: () -> T?,
    restoreCurrent: (T) -> T?,
    deleteBackup: (T) -> Unit
): T {
    val backup = current?.let {
        checkNotNull(backupCurrent(it)) { "无法备份已有导出文件" }
    }
    fun restore() {
        backup?.let {
            checkNotNull(restoreCurrent(it)) { "无法恢复原导出文件" }
        }
    }
    return try {
        val installed = checkNotNull(activateStaged()) { "无法替换导出文件" }
        backup?.let { runCatching { deleteBackup(it) } }
        installed
    } catch (error: Throwable) {
        val restoreError = runCatching { restore() }.exceptionOrNull()
        if (restoreError == null) {
            backup?.let { runCatching { deleteBackup(it) } }
        } else {
            error.addSuppressed(restoreError)
        }
        throw error
    }
}
