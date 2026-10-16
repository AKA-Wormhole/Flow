package io.github.aedev.flow.ui.screens.sound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.aedev.flow.utils.formatViewCount
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.toShortVideo
import io.github.aedev.flow.data.shorts.queue.ShortsQueueHandoff
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SoundShortsState(
    val loading: Boolean = true,
    val shorts: List<ShortVideo> = emptyList(),
)

@HiltViewModel
class SoundShortsViewModel
    @Inject
    constructor(
        private val handoff: ShortsQueueHandoff,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SoundShortsState())
        val state: StateFlow<SoundShortsState> = _state
        private var loadedKey: String? = null

        fun load(
            browseId: String,
            params: String,
            videoId: String,
        ) {
            val key = "$browseId|$params|$videoId"
            if (key == loadedKey) return
            loadedKey = key
            viewModelScope.launch {
                val reels =
                    withContext(Dispatchers.IO) {
                        var id = browseId
                        var p = params
                        if (id.isBlank() && videoId.isNotBlank()) {
                            val overlay = YouTube.reelOverlay(videoId).getOrNull()
                            id = overlay?.soundBrowseId.orEmpty()
                            p = overlay?.soundParams.orEmpty()
                        }
                        if (id.isBlank()) emptyList() else YouTube.soundShorts(id, p).getOrNull().orEmpty().map { it.toShortVideo() }
                    }
                _state.value = SoundShortsState(loading = false, shorts = reels)
            }
        }

        fun sourceFor(startId: String): ShortsQueueSource =
            ShortsQueueSource.Snapshot(handoff.offer(_state.value.shorts), startId)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundShortsScreen(
    browseId: String,
    params: String,
    title: String,
    videoId: String = "",
    onBack: () -> Unit,
    onOpen: (ShortsQueueSource) -> Unit,
    viewModel: SoundShortsViewModel = hiltViewModel(),
) {
    LaunchedEffect(browseId, params, videoId) { viewModel.load(browseId, params, videoId) }
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title.ifBlank { stringResource(R.string.sound_original) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.shorts.isEmpty() -> {
                    Text(stringResource(R.string.sound_empty), modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SoundHeader(
                                title = title.ifBlank { stringResource(R.string.sound_original) },
                                coverUrl = state.shorts.first().thumbnailUrl,
                                count = state.shorts.size,
                                onPlayAll = { onOpen(viewModel.sourceFor(state.shorts.first().id)) },
                            )
                        }
                        items(state.shorts, key = { it.id }) { short ->
                            Box(
                                Modifier
                                    .aspectRatio(9f / 16f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpen(viewModel.sourceFor(short.id)) },
                            ) {
                                AsyncImage(
                                    model = short.thumbnailUrl,
                                    contentDescription = short.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (short.viewCount > 0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        Text(formatViewCount(short.viewCount), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundHeader(
    title: String,
    coverUrl: String,
    count: Int,
    onPlayAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.sound_shorts_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPlayAll, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.sound_play_all), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
