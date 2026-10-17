package io.github.aedev.flow.ui.screens.live

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.videoplayer.controls.PlayerPillIconButton
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.theme.PlayerScrimAffordance
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimContentSecondary
import io.github.aedev.flow.utils.formatViewCount

/**
 * Live streams as a vertical feed: swipe up for the next stream, as in Shorts. One player is shared
 * by the pages; it plays the stream the pager has settled on and every other page shows its thumbnail,
 * so scrolling never opens more than one live connection.
 */
@OptIn(ExperimentalFoundationApi::class, UnstableApi::class)
@Composable
fun LiveFeedScreen(
    onBack: () -> Unit,
    onChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveFeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { createLivePlayer(context) }
    var failed by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    failed = true
                    buffering = false
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) player.pause()
                if (event == Lifecycle.Event.ON_START && !failed) player.play()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize().background(PlayerScrim)) {
        if (state.streams.isEmpty()) {
            if (state.isLoading || state.isLoadingMore) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Text(
                    text = stringResource(R.string.live_feed_empty),
                    color = PlayerScrimContentSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { state.streams.size })

            // Plays whichever stream the pager settles on; a failed resolve leaves the thumbnail and a message.
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    val stream = state.streams.getOrNull(page) ?: return@collect
                    failed = false
                    buffering = true
                    player.stop()
                    player.clearMediaItems()
                    val source = viewModel.resolve(stream.id)
                    if (source == null) {
                        failed = true
                        buffering = false
                        return@collect
                    }
                    player.setMediaSource(
                        DefaultMediaSourceFactory(
                            DefaultDataSource.Factory(
                                context,
                                DefaultHttpDataSource.Factory().setUserAgent(source.userAgent),
                            ),
                        ).createMediaSource(
                            MediaItem
                                .Builder()
                                .setUri(source.hlsUrl)
                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                .build(),
                        ),
                    )
                    player.prepare()
                    player.playWhenReady = true
                    if (page >= state.streams.size - 2) viewModel.loadMore()
                }
            }

            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { state.streams[it].id },
            ) { page ->
                LiveFeedPage(
                    stream = state.streams[page],
                    player = player.takeIf { page == pagerState.settledPage && !failed },
                    isActive = page == pagerState.settledPage,
                    failed = failed && page == pagerState.settledPage,
                    buffering = buffering && page == pagerState.settledPage,
                    onChannelClick = onChannelClick,
                    onTogglePlayback = { if (player.isPlaying) player.pause() else player.play() },
                )
            }
        }

        PlayerPillIconButton(
            onClick = onBack,
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.btn_back),
            buttonSize = 40.dp,
            iconSize = 24.dp,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp),
        )
    }
}

@Composable
private fun LiveFeedPage(
    stream: Video,
    player: Player?,
    isActive: Boolean,
    failed: Boolean,
    buffering: Boolean,
    onChannelClick: (String) -> Unit,
    onTogglePlayback: () -> Unit,
) {
    Box(Modifier.fillMaxSize().clickable(enabled = isActive, onClick = onTogglePlayback)) {
        // The thumbnail stays underneath until the first frame, and is all a neighbouring page shows.
        AsyncImage(
            model = stream.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (failed) {
            Text(
                text = stringResource(R.string.live_stream_unavailable),
                color = PlayerScrimContent,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .clip(MaterialTheme.shapes.medium)
                        .background(PlayerScrimAffordance)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.error).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(
                        text = stringResource(R.string.player_live_label),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
                if (stream.viewCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatViewCount(stream.viewCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = PlayerScrimContent,
                    )
                }
            }
            Text(
                text = stream.channelName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = PlayerScrimContent,
                modifier = Modifier.clickable(enabled = stream.channelId.isNotBlank()) { onChannelClick(stream.channelId) },
            )
            Text(
                text = stream.title,
                style = MaterialTheme.typography.bodyMedium,
                color = PlayerScrimContent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(UnstableApi::class)
private fun createLivePlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context).build().apply { playWhenReady = true }
