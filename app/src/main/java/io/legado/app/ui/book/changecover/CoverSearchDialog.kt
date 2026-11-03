package io.legado.app.ui.book.changecover

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.data.repository.BookCoverCandidate
import io.legado.app.data.repository.CoverCandidateKind
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.BookCover
import io.legado.app.ui.compose.component.LegadoImageState
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.component.rememberLegadoImageState

@Composable
fun CoverSearchDialog(state: CoverSearchUiState, controller: CoverSearchController) {
    if (!state.open) return
    Dialog(onDismissRequest = controller::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !state.saving, dismissOnClickOutside = !state.saving)) {
        Scaffold(Modifier.fillMaxSize().testTag("cover_search_dialog"), topBar = {
            LegadoTopAppBar(title = stringResource(R.string.change_cover_source), onNavigationClick = controller::dismiss, actions = {
                IconButton(onClick = controller::toggleSearch, enabled = !state.saving, modifier = Modifier.testTag("cover_search_toggle")) {
                    Icon(if (state.searching) Icons.Default.Stop else Icons.Default.PlayArrow,
                        stringResource(if (state.searching) R.string.stop else R.string.resume))
                }
                IconButton(onClick = controller::refresh, enabled = !state.saving, modifier = Modifier.testTag("cover_search_refresh")) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text("${state.name} · ${state.author}", Modifier.padding(12.dp))
                if (state.searching || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("cover_search_progress"))
                state.error?.let { Text(it, Modifier.padding(12.dp).testTag("cover_search_error"), color = MaterialTheme.colorScheme.error) }
                if (state.failures > 0) Text(stringResource(R.string.cover_search_source_errors, state.failures), Modifier.padding(12.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    itemsIndexed(state.covers, key = { _, cover -> cover.id }) { index, cover ->
                        val label = when (cover.kind) {
                            CoverCandidateKind.Default -> stringResource(R.string.default_cover)
                            CoverCandidateKind.Rule -> stringResource(R.string.cover_rule)
                            CoverCandidateKind.Source -> cover.sourceName
                        }
                        Column(Modifier.clickable(enabled = !state.saving) { controller.select(cover.id) }.padding(4.dp).testTag("cover_result_$index")) {
                            CoverCandidateImage(cover, label, Modifier.fillMaxWidth().aspectRatio(5f / 7f))
                            Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverCandidateImage(cover: BookCoverCandidate, label: String, modifier: Modifier) {
    val image by rememberLegadoImageState(cover.id to cover.url) { context ->
        val request = if (cover.kind == CoverCandidateKind.Default) Glide.with(context).load(BookCover.defaultDrawable)
            else ImageLoader.load(context, cover.url).let { request -> cover.source?.let { request.apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, it)) } ?: request }
        request.override(216, 300).centerCrop()
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        when (val current = image) {
            is LegadoImageState.Success -> Image(current.bitmap, label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            LegadoImageState.Loading -> Box(Modifier.fillMaxSize()) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            LegadoImageState.Error -> Text(stringResource(R.string.error), Modifier.padding(8.dp))
        }
    }
}
