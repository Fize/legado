package io.legado.app.help.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import io.legado.app.utils.getSharedPreferences
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID

internal fun writePreferenceSnapshot(
    context: Context,
    path: String,
    fileName: String,
    populate: SharedPreferences.Editor.() -> Unit,
) {
    val directory = File(path).apply {
        check(mkdirs() || isDirectory) { "无法创建配置备份目录" }
    }
    val temporaryName = "${fileName}_${UUID.randomUUID()}"
    val temporaryFile = File(directory, "${temporaryName}.xml")
    var preferences: SharedPreferences? = null
    try {
        preferences = checkNotNull(context.getSharedPreferences(path, temporaryName)) {
            "无法创建配置备份"
        }
        val editor = preferences.edit().clear()
        editor.populate()
        check(editor.commit()) { "写入配置备份失败" }
        check(temporaryFile.isFile) { "配置备份文件不存在" }
        temporaryFile.copyTo(File(directory, "${fileName}.xml"), overwrite = true)
    } finally {
        clearTemporaryPreferences(preferences, temporaryFile)
    }
}

internal fun readPreferenceSnapshot(
    context: Context,
    path: String,
    fileName: String,
): Map<String, *>? {
    val directory = File(path)
    val source = File(directory, "${fileName}.xml").takeIf { it.isFile } ?: return null
    // SharedPreferences silently substitutes an empty map for malformed XML.
    // Validate first so a failed import cannot be reported as a successful restore.
    validatePreferenceSnapshot(source)
    val temporaryName = "${fileName}_${UUID.randomUUID()}"
    val temporaryFile = File(directory, "${temporaryName}.xml")
    var preferences: SharedPreferences? = null
    try {
        source.copyTo(temporaryFile, overwrite = true)
        preferences = checkNotNull(context.getSharedPreferences(path, temporaryName)) {
            "无法读取配置备份"
        }
        return HashMap(preferences.all)
    } finally {
        clearTemporaryPreferences(preferences, temporaryFile)
    }
}

private fun validatePreferenceSnapshot(source: File) {
    try {
        source.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, null) }
            require(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "map")
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                val type = parser.name
                require(parser.getAttributeValue(null, "name") != null)
                when (type) {
                    "string" -> parser.nextText()
                    "set" -> {
                        while (parser.nextTag() == XmlPullParser.START_TAG) {
                            require(parser.name == "string")
                            parser.nextText()
                        }
                        require(parser.name == "set")
                    }
                    else -> {
                        val value = parser.getAttributeValue(null, "value")
                        when (type) {
                            "int" -> requireNotNull(value).toInt()
                            "long" -> requireNotNull(value).toLong()
                            "float" -> requireNotNull(value).toFloat()
                            "boolean" -> require(value == "true" || value == "false")
                            "null" -> Unit
                            else -> error("未知配置类型 $type")
                        }
                        require(parser.nextTag() == XmlPullParser.END_TAG && parser.name == type)
                    }
                }
            }
            require(parser.name == "map")
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                require(parser.eventType != XmlPullParser.START_TAG)
            }
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("${source.name} 配置备份无法解析", e)
    }
}

private fun clearTemporaryPreferences(
    preferences: SharedPreferences?,
    file: File,
) {
    runCatching { preferences?.edit()?.clear()?.commit() }
    file.delete()
    File("${file.absolutePath}.bak").delete()
}
