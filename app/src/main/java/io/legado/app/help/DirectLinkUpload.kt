package io.legado.app.help

import io.legado.app.exception.NoStackTraceException

object DirectLinkUpload {
    const val ruleFileName = "directLinkUploadRule.json"

    @Throws(NoStackTraceException::class)
    suspend fun upLoad(
        fileName: String,
        file: Any,
        contentType: String,
        rule: Rule? = null
    ): String {
        throw NoStackTraceException("上传功能已被精简移除")
    }

    fun getRule(): Rule {
        return Rule("", "", "")
    }

    fun getConfig(): String? = null

    fun putConfig(rule: Rule) {}

    fun delConfig() {}

    fun getSummary(): String = "上传功能已被精简移除"

    fun getExpiryDate(): Int = 0

    data class Rule(
        var uploadUrl: String,
        var downloadUrlRule: String,
        var summary: String
    )
}
