package io.legado.app.model

import com.google.gson.JsonParser
import io.legado.app.help.HighlightStyle
import org.junit.Assert.*
import org.junit.Test

class ReaderHighlightStyleTest {
    @Test fun explicitChangesPreserveTopLevelExtensionFields() {
        val result=JsonParser.parseString(mergeReaderHighlightStyle("{\"future\":{\"version\":7},\"bold\":false}",HighlightStyle(bold=true))).asJsonObject
        assertEquals(7,result["future"].asJsonObject["version"].asInt); assertTrue(result["bold"].asBoolean)
    }
    @Test fun nestedExtensionsSurviveAChangeToKnownDecorationValues() {
        val result=JsonParser.parseString(mergeReaderHighlightStyle("{\"underline\":{\"future\":9,\"width\":2}}",HighlightStyle(underline=HighlightStyle.Underline(width=7f)))).asJsonObject
        assertEquals(9,result["underline"].asJsonObject["future"].asInt); assertEquals(7f,result["underline"].asJsonObject["width"].asFloat)
    }
    @Test fun disablingAChannelRemovesTheOldNullableObject() {
        val result=JsonParser.parseString(mergeReaderHighlightStyle("{\"underline\":{\"width\":2},\"fontSize\":40}",HighlightStyle())).asJsonObject
        assertFalse(result.has("underline")); assertFalse(result.has("fontSize"))
    }
    @Test fun malformedOldJsonDoesNotPreventAValidStyleChange() {
        val result=JsonParser.parseString(mergeReaderHighlightStyle("invalid",HighlightStyle(fill=123))).asJsonObject
        assertEquals(123,result["fill"].asInt)
    }
    @Test fun unsafeWidthsAndShadowValuesAreNormalizedBeforePersistence() {
        val result=JsonParser.parseString(mergeReaderHighlightStyle("{}",HighlightStyle(underline=HighlightStyle.Underline(width=100f,distance=-1f),shadow=HighlightStyle.Shadow(radius=100f)))).asJsonObject
        assertEquals(10f,result["underline"].asJsonObject["width"].asFloat); assertEquals(0f,result["underline"].asJsonObject["distance"].asFloat)
        assertEquals(10f,result["shadow"].asJsonObject["radius"].asFloat)
    }
}
