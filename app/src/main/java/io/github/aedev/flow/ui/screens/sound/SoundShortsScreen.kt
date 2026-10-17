package io.github.aedev.flow.ui.screens.sound

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import io.github.aedev.flow.utils.formatViewCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SoundShortsState(
    val loading: Boolean = true,
    val shorts: List<ShortVideo> = emptyList(),
    val debug: String = "",
)

/** One line per attempt, saying plainly whether YouTube rejected it, the network failed, or it came back empty. */
internal fun describeSoundAttempt(
    name: String,
    error: Throwable?,
    count: Int,
): String =
    when {
        error is io.ktor.client.plugins.ResponseException -> {
            val code = error.response.status.value
            val kind = if (code in 500..599) "YOUTUBE SERVER ERROR" else "REJECTED BY YOUTUBE"
            "$name: $kind (HTTP $code ${error.response.status.description})"
        }

        error is java.io.IOException -> {
            "$name: NETWORK FAILED (${error.javaClass.simpleName}: ${error.message?.take(80)})"
        }

        error != null -> {
            "$name: FAILED (${error.javaClass.simpleName}: ${error.message?.take(80)})"
        }

        count == 0 -> {
            "$name: OK but YouTube returned 0 shorts"
        }

        else -> {
            "$name: OK, $count shorts"
        }
    }

internal fun soundLogHeader(
    videoId: String,
    browseId: String,
    params: String,
): String {
    val locale = YouTube.locale
    return buildString {
        appendLine("FLOW SOUND SCREEN")
        appendLine("Video: $videoId")
        appendLine(
            "Sound link in short: ${if (browseId.isBlank()) "none" else browseId}, params ${if (params.isBlank()) "none" else "present"}",
        )
        appendLine("Signed in: ${YouTube.cookie != null}")
        appendLine("Locale: hl=${locale.hl}, gl=${locale.gl}")
        appendLine("Visitor data present: ${!YouTube.visitorData.isNullOrBlank()}")
        appendLine()
    }
}

@HiltViewModel
class SoundShortsViewModel
    @Inject
    constructor(
        private val handoff: ShortsQueueHandoff,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SoundShortsState())
        val state: StateFlow<SoundShortsState> = _state
        private var loadedKey: String? = null
        var debug: String = ""
            private set

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
                        suspend fun fetch(
                            id: String,
                            p: String?,
                        ) = if (id.isBlank()) {
                            emptyList()
                        } else {
                            YouTube
                                .soundShorts(id, p)
                                .getOrNull()
                                .orEmpty()
                                .map { it.toShortVideo() }
                        }
                        // Try each source in turn and keep the largest result; a lone reel usually means the
                        // source only returned the seed, so the next one gets a chance too.
                        val log = StringBuilder(soundLogHeader(videoId, browseId, params))

                        fun Result<List<io.github.aedev.flow.innertube.pages.reel.ReelLockup>>.mapped(name: String): List<ShortVideo> {
                            val list = getOrNull().orEmpty().map { it.toShortVideo() }
                            log.appendLine(describeSoundAttempt(name, exceptionOrNull(), list.size))
                            YouTube.takeSoundShape().takeIf { it.isNotBlank() }?.let { log.append(it) }
                            return list
                        }
                        val sourceIds = if (videoId.isBlank()) emptyList() else YouTube.soundSourceIds(videoId)
                        log.append(YouTube.takeSoundShape())
                        log.appendLine("Audio source ids: ${sourceIds.ifEmpty { listOf("none, using the short itself") }}")
                        val candidateIds = (sourceIds + listOfNotNull(videoId.takeIf { it.isNotBlank() })).distinct()
                        var best = emptyList<ShortVideo>()
                        val sources: List<suspend () -> List<ShortVideo>> =
                            buildList {
                                if (videoId.isNotBlank()) add { YouTube.soundShortsFromPanel(videoId).mapped("panel") }
                                for (id in candidateIds) {
                                    add { YouTube.soundShortsFromPage(id, feed = true).mapped("feed page $id") }
                                    add { YouTube.soundShortsViaAudioPivot(id).mapped("pivot $id") }
                                    add {
                                        val source = YouTube.soundEndpointForVideo(id).getOrNull()
                                        if (source == null) {
                                            log.appendLine("resolve $id: FAILED (YouTube gave no sound page)")
                                            emptyList()
                                        } else {
                                            YouTube.soundShorts(source.first, source.second).mapped("resolve $id")
                                        }
                                    }
                                    add { YouTube.soundShortsFromPage(id).mapped("page $id") }
                                }
                                if (browseId.isNotBlank()) add { YouTube.soundShorts(browseId, params).mapped("link") }
                                if (videoId.isNotBlank()) {
                                    add {
                                        val overlay = YouTube.reelOverlay(videoId).getOrNull()
                                        val id = overlay?.soundBrowseId.orEmpty()
                                        if (id.isBlank()) {
                                            log.appendLine("overlay: FAILED (short details contain no sound link)")
                                            emptyList()
                                        } else {
                                            YouTube.soundShorts(id, overlay?.soundParams).mapped("overlay")
                                        }
                                    }
                                }
                            }
                        for (source in sources) {
                            val result = source()
                            if (result.size > best.size) best = result
                            if (best.size > 1) break
                        }
                        debug = log.toString()
                        android.util.Log.w("SoundShorts", debug)
                        val found = best
                        found
                    }
                _state.value = SoundShortsState(loading = false, shorts = reels, debug = debug)
            }
        }

        fun sourceFor(startId: String): ShortsQueueSource = ShortsQueueSource.Snapshot(handoff.offer(_state.value.shorts), startId)
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
                actions = {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    androidx.compose.material3.TextButton(
                        onClick = {
                            clipboard.setText(
                                androidx.compose.ui.text
                                    .AnnotatedString(state.debug),
                            )
                        },
                    ) { Text("Copy log") }
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
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.sound_empty))
                        Text(
                            state.debug,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        androidx.compose.material3.TextButton(
                            onClick = {
                                clipboard.setText(
                                    androidx.compose.ui.text
                                        .AnnotatedString(state.debug),
                                )
                            },
                        ) { Text("Copy log") }
                    }
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
                                        Text(
                                            formatViewCount(short.viewCount),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
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
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (count ==
                    1
                ) {
                    stringResource(R.string.sound_only_one)
                } else {
                    pluralStringResource(R.plurals.sound_shorts_count, count, count)
                },
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
