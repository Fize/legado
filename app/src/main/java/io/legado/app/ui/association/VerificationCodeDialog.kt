package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ImageProvider
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

/**
 * 图片验证码对话框
 * 验证结果通过本次请求的 verificationResultKey 返回给等待线程。
 */
class VerificationCodeDialog() : BaseComposeDialogFragment() {

    constructor(
        imageUrl: String,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int,
        verificationResultKey: String? = null,
    ) : this() {
        arguments = Bundle().apply {
            putString("imageUrl", imageUrl)
            putString("sourceOrigin", sourceOrigin)
            putString("sourceName", sourceName)
            putInt("sourceType", sourceType)
            putString("verificationResultKey", verificationResultKey)
        }
    }

    val viewModel by viewModels<VerificationCodeViewModel>()

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private var sourceOrigin: String? = null
    private var subtitleState by mutableStateOf<String?>(null)
    private var imageUrlState by mutableStateOf<String?>(null)
    private var verificationCodeState by mutableStateOf("")
    private var loadedBitmap by mutableStateOf<Bitmap?>(null)
    private var loadFailed by mutableStateOf(false)

    private val verificationResultKey: String?
        get() = arguments?.getString("verificationResultKey")

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val arguments = arguments ?: return
        viewModel.initData(arguments)
        subtitleState = arguments.getString("sourceName")
        sourceOrigin = arguments.getString("sourceOrigin")
        val imageUrl = arguments.getString("imageUrl") ?: return
        imageUrlState = imageUrl
        loadImage(imageUrl, sourceOrigin)
    }

    @Composable
    override fun DialogContent() {
        var showMenu by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LegadoTopAppBar(
                    title = stringResource(R.string.input_verification_code),
                    subtitle = subtitleState,
                    actions = {
                        IconButton(
                            onClick = {
                                SourceVerificationHelp.setResult(
                                    verificationResultKey,
                                    verificationCodeState
                                )
                                dismiss()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.ok)
                            )
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.disable_source)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.disableSource {
                                            dismiss()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_source)) },
                                    onClick = {
                                        showMenu = false
                                        alert(R.string.draw) {
                                            setMessage(getString(R.string.sure_del) + "\n" + viewModel.sourceName)
                                            noButton()
                                            yesButton {
                                                viewModel.deleteSource {
                                                    dismiss()
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clickable {
                            imageUrlState?.let { url ->
                                showDialogFragment(PhotoDialog(url, sourceOrigin))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = loadedBitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.verification_code),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (loadFailed) {
                        Image(
                            painter = painterResource(R.drawable.image_loading_error),
                            contentDescription = stringResource(R.string.verification_code),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                OutlinedTextField(
                    value = verificationCodeState,
                    onValueChange = { verificationCodeState = it },
                    label = { Text(stringResource(R.string.verification_code)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun loadImage(url: String, sourceUrl: String?) {
        ImageProvider.remove(url)
        loadedBitmap = null
        loadFailed = false
        ImageLoader.loadBitmap(requireContext(), url).apply {
            sourceUrl?.let {
                apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it))
            }
        }.error(R.drawable.image_loading_error)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap?>,
                    isFirstResource: Boolean
                ): Boolean {
                    loadFailed = true
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    val bitmap = resource.copy(resource.config!!, true)
                    ImageProvider.put(url, bitmap)
                    loadedBitmap = bitmap
                    return false
                }
            })
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (loadedBitmap == null) {
                        loadedBitmap = resource
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
    }

    override fun onDestroy() {
        SourceVerificationHelp.checkResult(verificationResultKey)
        super.onDestroy()
        activity?.finish()
    }

}
