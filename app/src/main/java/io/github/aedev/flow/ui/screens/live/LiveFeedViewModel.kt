package io.github.aedev.flow.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.YouTubeSearchParams
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LiveFeedState(
    val streams: List<Video> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
)

/** What the player needs to open one stream: YouTube's HLS manifest and the user agent its client signed it for. */
data class LiveSource(
    val hlsUrl: String,
    val userAgent: String,
)

/**
 * The vertical Live feed: streams that are on air right now, found with YouTube's own "Live" search
 * filter, one query per topic so the feed is not one genre. More topics load as the viewer nears the end.
 */
@HiltViewModel
class LiveFeedViewModel
    @Inject
    constructor(
        private val repository: YouTubeRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(LiveFeedState())
        val state: StateFlow<LiveFeedState> = _state.asStateFlow()

        private var nextTopic = 0

        init {
            loadMore()
        }

        fun loadMore() {
            if (_state.value.isLoadingMore || nextTopic >= TOPICS.size) {
                _state.update { it.copy(isLoading = false) }
                return
            }
            val batch = TOPICS.drop(nextTopic).take(BATCH)
            nextTopic += batch.size
            _state.update { it.copy(isLoadingMore = true) }
            viewModelScope.launch {
                val found =
                    coroutineScope {
                        batch
                            .map { topic ->
                                async {
                                    runCatching {
                                        repository
                                            .searchVideos(
                                                topic,
                                                params = LIVE_PARAMS,
                                            ).first
                                    }.getOrDefault(emptyList())
                                }
                            }.awaitAll()
                            .flatten()
                    }
                _state.update { current ->
                    val known = current.streams.mapTo(HashSet()) { it.id }
                    val fresh =
                        found
                            .filter { !it.isShort && !it.isUpcoming && known.add(it.id) }
                            .map { it.copy(isLive = true) }
                    current.copy(streams = current.streams + fresh, isLoading = false, isLoadingMore = false)
                }
            }
        }

        @OptIn(UnstableApi::class)
        suspend fun resolve(videoId: String): LiveSource? =
            withContext(Dispatchers.IO) {
                val result = runCatching { InnerTubeVideoStreamExtractor.extract(videoId) }.getOrNull() ?: return@withContext null
                val url = result.liveHlsUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
                LiveSource(url, result.usedClient.userAgent)
            }

        private companion object {
            const val BATCH = 3
            val TOPICS =
                listOf("live now", "live stream", "live music", "live gaming", "live news", "live sports", "live podcast", "live talk")
            val LIVE_PARAMS: String? =
                YouTubeSearchParams.build(
                    contentType = YouTubeSearchParams.ContentType.VIDEO,
                    features = setOf(YouTubeSearchParams.Feature.LIVE),
                )
        }
    }
