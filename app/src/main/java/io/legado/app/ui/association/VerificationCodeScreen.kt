package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.ImageProvider
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.navigation.VerificationCodeRoute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.legado.app.ui.photo.openPhoto

@Composable
fun VerificationCodeScreen(
    route: VerificationCodeRoute,
    onDismiss: () -> Unit,
    viewModel: VerificationCodeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = requireNotNull(context.findActivity()) {
        "VerificationCodeScreen requires an Activity context"
    }
    val sourceOrigin = route.sourceOrigin.orEmpty()
    val sourceName = route.sourceName.orEmpty()
    var verificationCode by rememberSaveable(route.verificationResultKey) { mutableStateOf("") }
    var loadedBitmap by remember(route.imageUrl) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(route.imageUrl) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    DisposableEffect(activity, route.verificationResultKey) {
        val attached = SourceVerificationHelp.attachVerificationUi(
            route.verificationResultKey,
        ) {
            val finished = CountDownLatch(1)
            activity.runOnUiThread {
                try {
                    activity.finish()
                } finally {
                    finished.countDown()
                }
            }
            finished.await(5, TimeUnit.SECONDS)
        }
        if (!attached) activity.finish()
        onDispose {
            SourceVerificationHelp.checkResult(route.verificationResultKey)
        }
    }

    VerificationImageEffect(
        imageUrl = route.imageUrl,
        sourceOrigin = route.sourceOrigin,
        onLoaded = {
            loadedBitmap = it
            loadFailed = false
        },
        onFailed = { loadFailed = true },
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LegadoTopAppBar(
                title = stringResource(R.string.input_verification_code),
                subtitle = sourceName.takeIf { it.isNotBlank() },
                actions = {
                    IconButton(
                        onClick = {
                            SourceVerificationHelp.setResult(
                                route.verificationResultKey,
                                verificationCode,
                            )
                            onDismiss()
                        },
                        modifier = Modifier.testTag("verificationCodeSubmit"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.ok),
                        )
                    }
                    if (sourceOrigin.isNotBlank()) {
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.testTag("verificationCodeMore"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu),
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.disable_source)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.disableSource(
                                            sourceOrigin,
                                            route.sourceType,
                                            onDismiss,
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_source)) },
                                    onClick = {
                                        showMenu = false
                                        showDeleteConfirmation = true
                                    },
                                )
                            }
                        }
                    }
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .testTag("verificationCodeImage")
                    .clickable {
                        (activity as? androidx.activity.ComponentActivity)?.openPhoto(route.imageUrl, route.sourceOrigin)
                    },
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = loadedBitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.verification_code),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (loadFailed) {
                    Image(
                        painter = painterResource(R.drawable.image_loading_error),
                        contentDescription = stringResource(R.string.verification_code),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            OutlinedTextField(
                value = verificationCode,
                onValueChange = { verificationCode = it },
                label = { Text(stringResource(R.string.verification_code)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("verificationCodeInput"),
            )
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.sure_del) + "\n" + sourceName) },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteSource(sourceOrigin, route.sourceType, onDismiss)
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@SuppressLint("CheckResult")
@Composable
private fun VerificationImageEffect(
    imageUrl: String,
    sourceOrigin: String?,
    onLoaded: (Bitmap) -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(context, imageUrl, sourceOrigin) {
        ImageProvider.remove(imageUrl)
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(
                resource: Bitmap,
                transition: Transition<in Bitmap>?,
            ) {
                val bitmap = resource.copy(resource.config ?: Bitmap.Config.ARGB_8888, true)
                ImageProvider.put(imageUrl, bitmap)
                onLoaded(bitmap)
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                onFailed()
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        ImageLoader.loadBitmap(context, imageUrl, sourceOrigin)
            .error(R.drawable.image_loading_error)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(target)
        onDispose {
            Glide.with(context).clear(target)
        }
    }
}
