package io.legado.app.ui.qrcode

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.Result
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.readBytes

class QrCodeActivity : BaseComposeActivity(), ScanResultCallback {

    private val containerId = View.generateViewId()

    private val selectQrImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.readBytes(this)?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            onScanResultCallback(QRCodeUtils.parseCodeResult(bitmap))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) = Unit

    @Composable
    override fun Content() {
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.scan_qr_code),
                    onNavigationClick = { finish() },
                    actions = {
                        TextButton(
                            onClick = {
                                selectQrImage.launch {
                                    mode = HandleFileContract.IMAGE
                                }
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.gallery),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        id = containerId
                        val fTag = "qrCodeFragment"
                        if (supportFragmentManager.findFragmentByTag(fTag) == null) {
                            supportFragmentManager.beginTransaction()
                                .replace(id, QrCodeFragment(), fTag)
                                .commitAllowingStateLoss()
                        }
                    }
                }
            )
        }
    }

    override fun onScanResultCallback(result: Result?) {
        val intent = Intent()
        intent.putExtra("result", result?.text)
        setResult(RESULT_OK, intent)
        finish()
    }

}
