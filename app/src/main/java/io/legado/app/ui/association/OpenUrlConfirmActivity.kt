package io.legado.app.ui.association

import android.os.Bundle
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.SourceType
import io.legado.app.constant.Theme
import io.legado.app.utils.showDialogFragment

class OpenUrlConfirmActivity :
    BaseComposeActivity(
        theme = Theme.Transparent,
        imageBg = false
    ) {

    @Composable
    override fun Content() {
        TranslucenceLoadingScreen(isLoading = false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        intent.getStringExtra("uri")?.let {
            val mimeType = intent.getStringExtra("mimeType")
            val sourceOrigin = intent.getStringExtra("sourceOrigin")
            val sourceName = intent.getStringExtra("sourceName")
            val sourceType = intent.getIntExtra("sourceType", SourceType.book)
            showDialogFragment(OpenUrlConfirmDialog(it, mimeType, sourceOrigin, sourceName, sourceType))
        } ?: finish()
    }

}
