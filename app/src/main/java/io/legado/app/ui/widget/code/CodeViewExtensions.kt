@file:Suppress("unused")

package io.legado.app.ui.widget.code

import android.content.Context
import android.widget.ArrayAdapter
import io.legado.app.R
import splitties.init.appCtx
import splitties.resources.color
import java.util.regex.Pattern

val legadoPattern: Pattern = Pattern.compile("\\|\\||&&|%%|@js:|@Json:|@css:|@@|@XPath:|@webjs:")
val jsonPattern: Pattern = Pattern.compile("\"[A-Za-z0-9]*?\"\\:|\"|\\{|\\}|\\[|\\]")
val wrapPattern: Pattern = Pattern.compile("\\\\n")
val operationPattern: Pattern =
    Pattern.compile(":|==|>|<|!=|>=|<=|->|=|%|-|-=|%=|\\+|\\-|\\-=|\\+=|\\^|\\&|\\|::|\\?|\\*")
val jsPattern: Pattern = Pattern.compile(
    "\\b(?:var|let|const|function|return|if|else|for|while|do|break|continue|switch|case|default|" +
        "try|catch|finally|throw|new|delete|typeof|instanceof|in|of|void|this|true|false|null|undefined)\\b"
)

fun CodeView.addLegadoPattern() {
    addSyntaxPattern(legadoPattern, appCtx.color(R.color.md_orange_900))
}

fun CodeView.addJsonPattern() {
    addSyntaxPattern(jsonPattern, appCtx.color(R.color.md_blue_800))
}

fun CodeView.addJsPattern() {
    addSyntaxPattern(wrapPattern, appCtx.color(R.color.md_blue_grey_500))
    addSyntaxPattern(operationPattern, appCtx.color(R.color.md_orange_900))
    addSyntaxPattern(jsPattern, appCtx.color(R.color.md_light_blue_600))
}

fun Context.arrayAdapter(keywords: Array<String>): ArrayAdapter<String> {
    return object : ArrayAdapter<String>(this, 0, keywords) {
        override fun getView(
            position: Int,
            convertView: android.view.View?,
            parent: android.view.ViewGroup
        ): android.view.View {
            val view = convertView ?: io.legado.app.ui.widget.text.create1LineTextAndDelView(context)
            view.findViewById<android.widget.TextView>(R.id.text_view).text = getItem(position)
            return view
        }
    }
}
