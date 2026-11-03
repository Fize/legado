package io.legado.app.ui.highlight.edit

import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.repository.highlightRuleIdentity
import io.legado.app.help.HighlightStyle
import io.legado.app.model.mergeReaderHighlightStyle
import io.legado.app.ui.navigation.HighlightRuleEditRoute
import org.junit.Assert.*
import org.junit.Test

class HighlightRuleEditorContractTest {
    @Test fun everyEditableFieldChangesTheConcurrencyIdentity() {
        val rule=HighlightRule(name="name",pattern="amber")
        val original=highlightRuleIdentity(rule)
        listOf(rule.copy(name="other"),rule.copy(pattern="violet"),rule.copy(isRegex=true),rule.copy(scope="book"),
            rule.copy(group="group"),rule.copy(isEnabled=false),rule.copy(order=9),rule.copy(timeoutMillisecond=1),
            rule.copy(applyToBody=false),rule.copy(applyToTitle=true),rule.copy(style="{\"bold\":true}"),rule.copy(uuid="replacement"))
            .forEach { assertNotEquals(original,highlightRuleIdentity(it)) }
    }
    @Test fun immutableEditorSnapshotsObserveSameIdFieldChanges() {
        val rule=HighlightRule(id=1,pattern="amber")
        assertEquals(rule,rule.copy(pattern="violet"))
        assertNotEquals(HighlightRuleEditorState(value=HighlightRuleEditorValue.from(rule),revision=1),HighlightRuleEditorState(value=HighlightRuleEditorValue.from(rule.copy(pattern="violet")),revision=1))
    }
    @Test fun malformedSurrogatesRemainDistinctInConcurrencyIdentity() {
        val rule=HighlightRule(pattern="\uD800")
        assertNotEquals(highlightRuleIdentity(rule),highlightRuleIdentity(rule.copy(pattern="\uD801")))
    }
    @Test fun originalStyleExtensionsSurviveExplicitStyleEdits() {
        val json=mergeReaderHighlightStyle("{\"extension\":7,\"underline\":{\"future\":true}}",HighlightStyle(bold=true,underline=HighlightStyle.Underline()))
        val objectValue=com.google.gson.JsonParser.parseString(json).asJsonObject
        assertEquals(7,objectValue["extension"].asInt)
        assertTrue(objectValue["underline"].asJsonObject["future"].asBoolean)
    }
    @Test fun turningAChannelOffDoesNotRestoreItsOldExtensions() {
        val off=mergeReaderHighlightStyle("{\"underline\":{\"future\":true}}",HighlightStyle())
        val on=mergeReaderHighlightStyle(off,HighlightStyle(underline=HighlightStyle.Underline()))
        assertFalse(on.contains("future"))
    }
    @Test fun navigationHoldsOnlyAnIdentityAndOptionalRuleKey() {
        val route=HighlightRuleEditRoute(7)
        assertEquals(7L,route.ruleId); assertEquals(36,route.requestId.length)
        assertNotEquals(route.requestId,HighlightRuleEditRoute(7).requestId)
        assertEquals(route,route.copy())
    }
    @Test fun regexValidationRejectsInvalidExpressions() {
        assertFalse(HighlightRule(pattern="[",isRegex=true).isValid())
        assertTrue(HighlightRule(pattern="[",isRegex=false).isValid())
    }
    @Test fun emptyExpressionsCannotBeCommitted() { assertFalse(HighlightRule(pattern="").isValid()) }
}
