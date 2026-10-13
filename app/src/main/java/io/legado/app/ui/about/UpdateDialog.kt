package io.legado.app.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.model.Download
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UpdateDialog() : BaseDialogFragment() {

    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
            putString("backupUrl", updateInfo.backupDownloadUrl)
            putString("mirrorUrl", updateInfo.mirrorDownloadUrl)
            putString("alternateMirrorUrl", updateInfo.alternateMirrorDownloadUrl)
            putLong("size", updateInfo.size)
            putLong("createdAt", updateInfo.createdAt)
            putBoolean("isBeta", updateInfo.isBeta)
        }
    }

    private val isBetaUpdate: Boolean
        get() = arguments?.getBoolean("isBeta") == true

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val updateBody = arguments?.getString("updateBody")
                    if (updateBody != null) {
                        UpdateDialogScreen(updateBody)
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val updateBody = arguments?.getString("updateBody")
        if (updateBody == null) {
            toastOnUi("没有数据")
            dismiss()
            return
        }
    }

    @Composable
    private fun UpdateDialogScreen(updateBody: String) {
        val context = requireContext()
        val bgColor = Color(context.backgroundColor)
        val topBarColor = Color(context.primaryColor)
        val primaryText = Color(context.primaryTextColor)
        val secondaryText = Color(context.secondaryTextColor)
        val newVersion = arguments?.getString("newVersion").orEmpty()
        val subtitle = formatUpdateMetadata(
            size = arguments?.getLong("size") ?: 0L,
            createdAt = arguments?.getLong("createdAt") ?: 0L
        ).takeIf(String::isNotBlank)
        var showMenu by remember { mutableStateOf(false) }

        val showPrimaryDownload = false
        val showBackupDownload = !isBetaUpdate && !arguments?.getString("backupUrl").isNullOrBlank()
        val showMirrorDownload = !isBetaUpdate && !arguments?.getString("mirrorUrl").isNullOrBlank()
        val showAlternateMirrorDownload = !isBetaUpdate && !arguments?.getString("alternateMirrorUrl").isNullOrBlank()
        val showOpenInBrowser = true
        val showIgnoreVersion = !isBetaUpdate

        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            color = bgColor
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topBarColor)
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = newVersion,
                            color = primaryText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                color = secondaryText,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more),
                                contentDescription = stringResource(R.string.more_menu),
                                tint = primaryText
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (showPrimaryDownload) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_download)) },
                                    onClick = {
                                        showMenu = false
                                        startDownload(arguments?.getString("url"))
                                    }
                                )
                            }
                            if (showOpenInBrowser) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_in_browser)) },
                                    onClick = {
                                        showMenu = false
                                        if (!isBetaUpdate) {
                                            arguments?.getString("backupUrl").orEmpty()
                                                .ifBlank { arguments?.getString("url").orEmpty() }
                                                .takeIf(String::isNotBlank)
                                                ?.let { url -> requireContext().openUrl(url) }
                                        } else {
                                            arguments?.getString("url").orEmpty()
                                                .takeIf(String::isNotBlank)
                                                ?.let { url -> requireContext().openUrl(url) }
                                        }
                                    }
                                )
                            }
                            if (showBackupDownload) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download_from_github)) },
                                    onClick = {
                                        showMenu = false
                                        startDownload(arguments?.getString("backupUrl"))
                                    }
                                )
                            }
                            if (showMirrorDownload) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download_from_backup_cdn)) },
                                    onClick = {
                                        showMenu = false
                                        startDownload(arguments?.getString("mirrorUrl"))
                                    }
                                )
                            }
                            if (showAlternateMirrorDownload) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download_from_second_cdn)) },
                                    onClick = {
                                        showMenu = false
                                        startDownload(arguments?.getString("alternateMirrorUrl"))
                                    }
                                )
                            }
                            if (showIgnoreVersion) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ignore_this_version)) },
                                    onClick = {
                                        showMenu = false
                                        LocalConfig.ignoreUpdateVersion = arguments?.getString("newVersion")
                                        toastOnUi(R.string.ignore_this_version)
                                        dismiss()
                                    }
                                )
                            }
                        }
                    }
                }

                // Markdown content
                AndroidView(
                    factory = { ctx ->
                        ScrollTextView(ctx).apply {
                            val pad = 12.dpToPx()
                            setPadding(pad, pad, pad, pad)
                            setTextColor(ctx.secondaryTextColor)
                            setTextIsSelectable(true)
                            post {
                                Markwon.builder(ctx)
                                    .usePlugin(GlideImagesPlugin.create(ctx))
                                    .usePlugin(HtmlPlugin.create())
                                    .usePlugin(TablePlugin.create(ctx))
                                    .build()
                                    .setMarkdown(this, updateBody)
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                // Bottom action buttons (betaActions)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = { dismiss() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.text_return),
                            color = primaryText
                        )
                    }
                    // btnBetaUpdate
                    TextButton(
                        onClick = {
                            startDownload(arguments?.getString("url"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.beta_update_now),
                            color = primaryText
                        )
                    }
                }
            }
        }
    }

    private fun formatUpdateMetadata(size: Long, createdAt: Long): String {
        val metadata = mutableListOf<String>()
        if (size > 0) metadata += ConvertUtils.formatFileSize(size)
        if (createdAt > 0) {
            metadata += Instant.ofEpochMilli(createdAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
        return metadata.joinToString(" · ")
    }

    private fun startDownload(url: String?) {
        val name = arguments?.getString("name")
        if (url.isNullOrBlank() || name.isNullOrBlank()) return
        Download.start(requireContext(), url, name, isAppUpdate = true)
        toastOnUi(R.string.download_start)
        dismiss()
    }

}
