package io.legado.app.ui.replace.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplacePreviewMessageTest {
    @Test fun executionFailuresHaveStableSemanticKeys() {
        assertEquals(ReplacePreviewMessage.Error,ReplacePreviewMessage.fromReason(null))
        assertEquals(ReplacePreviewMessage.Timeout,ReplacePreviewMessage.fromReason(ReplacePreviewException.Reason.TIMEOUT))
        assertEquals(ReplacePreviewMessage.ContextUnavailable,ReplacePreviewMessage.fromReason(ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE))
        assertEquals(ReplacePreviewMessage.Javascript,ReplacePreviewMessage.fromReason(ReplacePreviewException.Reason.JS_EVALUATION))
    }
    @Test fun keysRoundTripAndPreviouslySavedTranslatedMessagesHaveASafeFallback() {
        ReplacePreviewMessage.entries.forEach { assertEquals(it,ReplacePreviewMessage.fromKey(it.name)) }
        assertEquals(ReplacePreviewMessage.Error,ReplacePreviewMessage.fromKey("旧语言预览错误"))
    }
}
