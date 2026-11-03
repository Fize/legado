package io.legado.app.ui.qrcode

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.readBytes

internal const val QR_SCAN_RESULT_REQUEST = "qrCode.scanResult"
internal const val QR_SCAN_RESULT_TEXT = "result"

@Composable
fun QrCodeScreen(
    onBack: () -> Unit,
    onResult: (Int, Intent?) -> Unit,
) {
    val activity = checkNotNull(LocalActivity.current as? AppCompatActivity)
    val containerId = remember { View.generateViewId() }
    val returnResult: (String?) -> Unit = { text ->
        onResult(Activity.RESULT_OK, Intent().putExtra(QR_SCAN_RESULT_TEXT, text))
        onBack()
    }
    val selectQrImage = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.readBytes(activity)?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            returnResult(QRCodeUtils.parseCodeResult(bitmap)?.text)
        }
    }

    DisposableEffect(activity) {
        activity.supportFragmentManager.setFragmentResultListener(
            QR_SCAN_RESULT_REQUEST,
            activity,
        ) { _, result -> returnResult(result.getString(QR_SCAN_RESULT_TEXT)) }
        onDispose {
            activity.supportFragmentManager.clearFragmentResultListener(QR_SCAN_RESULT_REQUEST)
        }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.scan_qr_code),
                onNavigationClick = onBack,
                actions = {
                    TextButton(
                        onClick = { selectQrImage.launch { mode = HandleFileContract.IMAGE } },
                    ) {
                        Text(
                            text = stringResource(R.string.gallery),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            factory = { context ->
                FrameLayout(context).apply {
                    id = containerId
                    val tag = "qrCodeFragment"
                    if (activity.supportFragmentManager.findFragmentByTag(tag) == null) {
                        activity.supportFragmentManager.beginTransaction()
                            .replace(id, QrCodeFragment(), tag)
                            .commitAllowingStateLoss()
                    }
                }
            },
        )
    }
}
