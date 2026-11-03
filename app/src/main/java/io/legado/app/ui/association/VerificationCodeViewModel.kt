package io.legado.app.ui.association

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.help.source.SourceHelp

class VerificationCodeViewModel(app: Application): BaseViewModel(app) {

    fun disableSource(sourceOrigin: String, sourceType: Int, block: () -> Unit) {
        execute {
            SourceHelp.enableSource(sourceOrigin, sourceType, false)
        }.onSuccess {
            block.invoke()
        }
    }

    fun deleteSource(sourceOrigin: String, sourceType: Int, block: () -> Unit) {
        execute {
            SourceHelp.deleteSource(sourceOrigin, sourceType)
        }.onSuccess {
            block.invoke()
        }
    }

}
