package io.github.aedev.flow.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.ai.AiChatState
import io.github.aedev.flow.ai.AiMessage
import io.github.aedev.flow.ai.AiRole
import io.github.aedev.flow.ai.FlowAiController
import io.github.aedev.flow.ai.timestamp
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState

/** How much of the screen the chat sheet covers; the rest stays visible behind it. */
private const val SHEET_HEIGHT_FRACTION = 0.62f

/**
 * Opens the assistant sheet. Provided by the app shell around everything it draws, so the player and
 * Home can offer the button without knowing how the sheet is hosted; null in previews and tests.
 */
val LocalFlowAiLauncher = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The sparkle mark as an action: the entry point to the assistant from the player and from Home. */
@Composable
fun FlowAiButton(
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 26.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val launch = LocalFlowAiLauncher.current ?: return
    val label = stringResource(R.string.flow_ai_button)
    IconButton(
        onClick = launch,
        modifier = modifier.size(buttonSize).semantics { contentDescription = label },
    ) {
        FlowAiIcon(
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}

/**
 * The assistant as a bottom sheet that covers about two thirds of the screen. State and actions come from [controller]; the shell decides
 * what opening a video or the settings page means, so the sheet works the same over any screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowAiChatSheet(
    controller: FlowAiController,
    onOpenVideo: (Video) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val playing by GlobalPlayerState.currentVideo.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = controller::closeSheet,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier =
                Modifier
                    .height(LocalConfiguration.current.screenHeightDp.dp * SHEET_HEIGHT_FRACTION)
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            ChatHeader(
                canStartNew = state.messages.isNotEmpty(),
                onNewChat = controller::newChat,
                onClose = controller::closeSheet,
            )
            playing?.takeIf { it.title.isNotBlank() }?.let { video ->
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = stringResource(R.string.flow_ai_watching, video.title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.hasApiKey -> {
                        MissingKey(onOpenSettings = {
                            controller.closeSheet()
                            onOpenSettings()
                        })
                    }

                    state.messages.isEmpty() -> {
                        EmptyChat(
                            hasVideo = playing != null,
                            onSuggestion = controller::send,
                        )
                    }

                    else -> {
                        Messages(
                            state = state,
                            canSeek = playing != null,
                            onOpenVideo = onOpenVideo,
                            onRegenerate = controller::regenerate,
                            onFollowUp = controller::send,
                        )
                    }
                }
            }
            if (state.isSending) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(
                    text = state.activity ?: stringResource(R.string.flow_ai_thinking),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.hasApiKey) {
                InputBar(
                    isSending = state.isSending,
                    onSend = controller::send,
                    onStop = controller::stop,
                )
            }
        }
    }
}

@Composable
private fun ChatHeader(
    canStartNew: Boolean,
    onNewChat: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowAiIcon(modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.flow_ai_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        if (canStartNew) {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.flow_ai_new_chat))
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
        }
    }
}

@Composable
private fun MissingKey(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlowAiIcon(modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.flow_ai_no_key_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.flow_ai_no_key_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.flow_ai_open_settings)) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(
    hasVideo: Boolean,
    onSuggestion: (String) -> Unit,
) {
    val suggestions =
        buildList {
            if (hasVideo) {
                add(stringResource(R.string.flow_ai_suggest_summarise))
                add(stringResource(R.string.flow_ai_suggest_key_points))
                add(stringResource(R.string.flow_ai_suggest_chapters))
            }
            add(stringResource(R.string.flow_ai_suggest_recent))
            add(stringResource(R.string.flow_ai_suggest_searches))
            add(stringResource(R.string.flow_ai_suggest_find))
        }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.flow_ai_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.flow_ai_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            suggestions.forEach { text ->
                AssistChip(onClick = { onSuggestion(text) }, label = { Text(text) })
            }
        }
    }
}

@Composable
private fun Messages(
    state: AiChatState,
    canSeek: Boolean,
    onOpenVideo: (Video) -> Unit,
    onRegenerate: () -> Unit,
    onFollowUp: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.isSending) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    val lastId = state.messages.lastOrNull()?.id
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.messages, key = { it.id }) { message ->
            MessageItem(
                message = message,
                isLast = message.id == lastId && !state.isSending,
                canSeek = canSeek,
                onOpenVideo = onOpenVideo,
                onRegenerate = onRegenerate,
                onFollowUp = onFollowUp,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessageItem(
    message: AiMessage,
    isLast: Boolean,
    canSeek: Boolean,
    onOpenVideo: (Video) -> Unit,
    onRegenerate: () -> Unit,
    onFollowUp: (String) -> Unit,
) {
    val isUser = message.role == AiRole.USER
    val seekTo: ((Long) -> Unit)? =
        if (canSeek) {
            { ms -> EnhancedPlayerManager.getInstance().seekTo(ms) }
        } else {
            null
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color =
                when {
                    message.isError -> MaterialTheme.colorScheme.errorContainer
                    isUser -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
            contentColor =
                when {
                    message.isError -> MaterialTheme.colorScheme.onErrorContainer
                    isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            val padding = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            if (isUser || message.isError) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text = message.text, style = MaterialTheme.typography.bodyLarge, modifier = padding)
                }
            } else {
                AiMarkdownText(text = message.text, onSeek = seekTo, modifier = padding)
            }
        }
        message.videos.forEach { video ->
            VideoCard(video = video, onClick = { onOpenVideo(video) })
        }
        if (!isUser && !message.isError) {
            AnswerActions(text = message.text, showRegenerate = isLast, onRegenerate = onRegenerate)
            if (isLast) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val more = stringResource(R.string.flow_ai_follow_up_more)
                    val shorter = stringResource(R.string.flow_ai_follow_up_shorter)
                    AssistChip(onClick = { onFollowUp(more) }, label = { Text(more) })
                    AssistChip(onClick = { onFollowUp(shorter) }, label = { Text(shorter) })
                }
            }
        } else if (message.isError && isLast) {
            FilledTonalButton(onClick = onRegenerate) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.flow_ai_retry))
            }
        } else if (isUser && isLast) {
            // The turn was stopped before an answer arrived.
            FilledTonalButton(onClick = onRegenerate) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.flow_ai_retry))
            }
        }
    }
}

@Composable
private fun AnswerActions(
    text: String,
    showRegenerate: Boolean,
    onRegenerate: () -> Unit,
) {
    val context = LocalContext.current
    val currentText by rememberUpdatedState(text)
    val copiedMessage = stringResource(R.string.flow_ai_copied)
    val shareTitle = stringResource(R.string.flow_ai_share)
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("flow_ai_answer", currentText))
                android.widget.Toast
                    .makeText(context, copiedMessage, android.widget.Toast.LENGTH_SHORT)
                    .show()
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.flow_ai_copy), modifier = Modifier.size(18.dp))
        }
        IconButton(
            onClick = {
                val send =
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, currentText)
                    }
                val chooser =
                    android.content.Intent
                        .createChooser(send, shareTitle)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Outlined.Share, contentDescription = shareTitle, modifier = Modifier.size(18.dp))
        }
        if (showRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.flow_ai_regenerate),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    video: Video,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .width(120.dp)
                        .height(68.dp)
                        .clip(MaterialTheme.shapes.small),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta =
                    listOfNotNull(
                        video.channelName.takeIf { it.isNotBlank() },
                        video.duration.takeIf { it > 0 }?.let { timestamp(it * 1000L) },
                    ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    isSending: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank() && !isSending) {
            onSend(text)
            text = ""
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.flow_ai_input_hint)) },
            shape = MaterialTheme.shapes.extraLarge,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        if (isSending) {
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.flow_ai_stop))
            }
        } else {
            IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.flow_ai_send))
            }
        }
    }
}
