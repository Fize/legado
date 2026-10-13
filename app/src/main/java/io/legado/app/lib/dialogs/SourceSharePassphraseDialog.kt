package io.legado.app.lib.dialogs

import android.content.DialogInterface
import io.legado.app.R
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.utils.sendToClip

fun AlertBuilder<DialogInterface>.sourceSharePassphraseButton(
    url: String,
    type: SourceSharePassphrase.Type,
) {
    if (!SourceSharePassphrase.canEncode(url)) return
    neutralButton(R.string.shibboleth) {
        val passphrase = SourceSharePassphrase.encode(
            url = url,
            type = type,
            expiryDays = DirectLinkUpload.getExpiryDate(),
        )
        ctx.alert(R.string.shibboleth) {
            setMessage(passphrase)
            okButton {
                ctx.sendToClip(passphrase)
            }
        }
    }
}
