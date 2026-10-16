package io.github.aedev.flow.ai

import io.github.aedev.flow.data.ai.GeminiClient
import io.github.aedev.flow.data.ai.GeminiException
import io.github.aedev.flow.data.ai.GeminiSettingsStore
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

enum class AiRole { USER, ASSISTANT }

/** One bubble in the chat. [videos] are the tappable cards under an assistant answer. */
data class AiMessage(
    val id: Long,
    val role: AiRole,
    val text: String,
    val videos: List<Video> = emptyList(),
    val isError: Boolean = false,
)

data class AiChatState(
    val messages: List<AiMessage> = emptyList(),
    val isSending: Boolean = false,
    /** What the assistant is doing right now, such as "Reading the transcript". */
    val activity: String? = null,
    val hasApiKey: Boolean = false,
)

private const val MAX_TOOL_ROUNDS = 8
private const val MAX_CARDS = 10
private const val MAX_CONVERSATIONS = 30
private const val HOME_KEY = "home"

private val SYSTEM_PROMPT =
    """
    You are Flow AI, the assistant built into Flow, an Android YouTube client. You help the user with the video they are watching, with finding videos, and with their own watch and search history.

    Use your tools instead of guessing:
    - "this video", "the video" -> get_current_video, then get_transcript to read it. Summarise or answer only from the transcript; if there are no captions, say so.
    - Finding something to watch -> search_youtube. Something the user watched before -> search_watch_history. What they searched for -> get_search_history.
    - "play/open it" -> open_video. "go to the part about X" -> find it in the transcript, then seek_current_video.
    - "chapters" -> read the whole transcript (for a long video call get_transcript again with start_seconds/end_seconds), then answer with one line per chapter in the form "- mm:ss Title", starting at 00:00, 5 to 12 chapters, titles under six words, in the language of the video.
    - Search and history results are already shown to the user as video cards, so do not repeat links or ids; just say briefly what you found and why it fits.

    Style: reply in the language the user writes in. Keep answers short and scannable. Quote timestamps as mm:ss (the app makes them tappable). For summaries give a one-line gist followed by key points.
    Format with the small markdown subset the app renders: **bold** for key terms, *italics*, `code`, "- " bullets, "1." numbered lists, and a short "## " heading only when an answer has distinct sections. No tables and no HTML. Use a few fitting emoji to make answers friendlier and easier to scan, for example one at the start of a section or key point, never more than one every few lines, and none in error messages.
    Never invent video content, titles or history. If a tool returns an error, tell the user plainly.
    """.trimIndent()

/** One chat: the model-facing history, the bubbles, and the state of its running turn. Each video has its own. */
private class Conversation {
    /** The model-facing history. The chat bubbles are kept apart: they hold cards and errors the model never sees. */
    val history = mutableListOf<JsonObject>()
    var messages: List<AiMessage> = emptyList()
    var isSending = false
    var activity: String? = null

    /** Where [history] stood before the latest question, so that question can be asked again cleanly. */
    var lastTurnStart = 0
    var job: Job? = null
}

/**
 * The conversations with Flow AI, kept for the life of the app so the sheet can be closed and opened
 * again, or moved between the player and Home, without losing the thread.
 *
 * Every video has its own conversation: its history is never sent to the model while another video is
 * open, and another video's questions never show up in it. When no video is open there is one general
 * conversation. The sheet always shows the conversation of the video that is open right now.
 *
 * A turn sends that conversation's history to Gemini; when the model asks for tools they run here, their
 * answers go back, and the loop repeats until the model answers in words.
 */
@Singleton
class FlowAiController
    @Inject
    internal constructor(
        private val client: GeminiClient,
        private val settings: GeminiSettingsStore,
        private val tools: FlowAiTools,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var nextId = 0L

        private val lock = Any()
        private val conversations = LinkedHashMap<String, Conversation>()
        private var activeKey = HOME_KEY

        private val _state = MutableStateFlow(AiChatState())
        val state: StateFlow<AiChatState> = _state.asStateFlow()

        /** Whether the assistant is switched on in settings; the shell hides every entry point when it is not. */
        val enabled: Flow<Boolean> = settings.enabled

        private val _sheetOpen = MutableStateFlow(false)
        val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

        private val _openVideoRequests = MutableSharedFlow<Video>(extraBufferCapacity = 1)

        /** A video the assistant opened; the app shell plays it. */
        val openVideoRequests: SharedFlow<Video> = _openVideoRequests.asSharedFlow()

        init {
            scope.launch { settings.apiKey.collect { key -> _state.update { it.copy(hasApiKey = key.isNotBlank()) } } }
            // Follow the player: a different video means a different conversation.
            scope.launch { GlobalPlayerState.currentVideo.collect { switchTo(resolveKey()) } }
        }

        fun openSheet() {
            switchTo(resolveKey())
            _sheetOpen.value = true
        }

        fun closeSheet() {
            _sheetOpen.value = false
        }

        /** Starts a fresh chat for the video that is showing; other videos' conversations are left alone. */
        fun newChat() {
            val conv = active()
            conv.job?.cancel()
            conv.history.clear()
            conv.lastTurnStart = 0
            conv.messages = emptyList()
            conv.isSending = false
            conv.activity = null
            publish(conv)
        }

        /** Empties every conversation, for when the assistant is switched off. */
        fun clearAll() {
            synchronized(lock) {
                conversations.values.forEach { it.job?.cancel() }
                conversations.clear()
                activeKey = HOME_KEY
            }
            tools.forget()
            publishActive()
        }

        fun stop() {
            active().job?.cancel()
        }

        fun send(text: String) {
            switchTo(resolveKey())
            val conv = active()
            val prompt = text.trim()
            if (prompt.isEmpty() || conv.isSending) return
            append(conv, AiMessage(nextId++, AiRole.USER, prompt))
            launchTurn(conv, prompt)
        }

        /** Asks the latest question again, dropping the answer or error that followed it. */
        fun regenerate() {
            val conv = active()
            if (conv.isSending) return
            val lastUser = conv.messages.indexOfLast { it.role == AiRole.USER }
            if (lastUser < 0) return
            val prompt = conv.messages[lastUser].text
            while (conv.history.size > conv.lastTurnStart) conv.history.removeAt(conv.history.lastIndex)
            conv.messages = conv.messages.take(lastUser + 1)
            publish(conv)
            launchTurn(conv, prompt)
        }

        // ── which conversation is showing ───────────────────────────────────────

        /** The conversation key for what the player has open right now: one per video, or the general one. */
        private fun resolveKey(): String {
            val id =
                GlobalPlayerState.currentVideo.value?.id
                    ?: runCatching {
                        EnhancedPlayerManager
                            .getInstance()
                            .playerState.value.currentVideoId
                    }.getOrNull()
            return if (id.isNullOrBlank()) HOME_KEY else "video:$id"
        }

        private fun active(): Conversation = synchronized(lock) { conversations.getOrPut(activeKey) { Conversation() } }

        private fun switchTo(key: String) {
            synchronized(lock) {
                if (key == activeKey && conversations.containsKey(key)) return
                activeKey = key
                // Touch the key so the most recently used conversations sit at the end.
                val conv = conversations.remove(key) ?: Conversation()
                conversations[key] = conv
                trim()
            }
            publishActive()
        }

        /** Keeps memory bounded: drops the oldest idle conversations, never the showing or a running one. */
        private fun trim() {
            if (conversations.size <= MAX_CONVERSATIONS) return
            val entries = conversations.entries.iterator()
            while (entries.hasNext() && conversations.size > MAX_CONVERSATIONS) {
                val entry = entries.next()
                if (entry.key != activeKey && !entry.value.isSending) entries.remove()
            }
        }

        private fun isActive(conv: Conversation): Boolean = synchronized(lock) { conversations[activeKey] === conv }

        /** Pushes [conv] to the UI, but only while it is the conversation being shown. */
        private fun publish(conv: Conversation) {
            if (!isActive(conv)) return
            _state.update { it.copy(messages = conv.messages, isSending = conv.isSending, activity = conv.activity) }
        }

        private fun publishActive() = publish(active())

        // ── a turn ──────────────────────────────────────────────────────────────

        private fun launchTurn(
            conv: Conversation,
            prompt: String,
        ) {
            conv.isSending = true
            conv.activity = null
            publish(conv)
            conv.job =
                scope.launch {
                    try {
                        converse(conv, prompt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GeminiException) {
                        fail(conv, friendly(e))
                    } catch (e: Exception) {
                        fail(conv, e.message ?: "Something went wrong.")
                    } finally {
                        conv.isSending = false
                        conv.activity = null
                        publish(conv)
                    }
                }
        }

        private suspend fun converse(
            conv: Conversation,
            prompt: String,
        ) {
            val history = conv.history
            conv.lastTurnStart = history.size
            if (!settings.isEnabled()) {
                fail(conv, "Flow AI is turned off in Settings > Integrations.")
                return
            }
            val apiKey = settings.currentApiKey()
            if (apiKey.isBlank()) {
                fail(conv, "Add your Gemini API key in Settings > Integrations first.")
                return
            }
            val model = settings.currentModel()
            val checkpoint = history.size
            history += GeminiClient.userContent(prompt)

            val cards = LinkedHashMap<String, Video>()
            try {
                repeat(MAX_TOOL_ROUNDS) {
                    val turn = client.generate(apiKey, model, SYSTEM_PROMPT, JsonArray(history), tools.declarations)
                    history += turn.content
                    if (turn.calls.isEmpty()) {
                        append(
                            conv,
                            AiMessage(
                                id = nextId++,
                                role = AiRole.ASSISTANT,
                                text = turn.text.ifBlank { "I don't have an answer for that." },
                                videos = cards.values.take(MAX_CARDS),
                            ),
                        )
                        return
                    }
                    val answers = mutableListOf<Pair<String, JsonObject>>()
                    for (call in turn.calls) {
                        conv.activity = activityFor(call.name)
                        publish(conv)
                        val result = tools.run(call.name, call.args)
                        result.videos.forEach { cards.putIfAbsent(it.id, it) }
                        result.openVideo?.let { video ->
                            cards.putIfAbsent(video.id, video)
                            // Only open it while this is still the chat the user is looking at.
                            if (isActive(conv)) _openVideoRequests.tryEmit(video)
                        }
                        answers += call.name to result.response
                    }
                    history += GeminiClient.functionResponses(answers)
                }
                append(
                    conv,
                    AiMessage(
                        nextId++,
                        AiRole.ASSISTANT,
                        "That took more steps than I can do in one go. Try asking for a smaller piece.",
                        cards.values.take(MAX_CARDS),
                    ),
                )
            } catch (e: Exception) {
                // A half-finished turn would leave a call without its answer, which Gemini rejects next time.
                while (history.size > checkpoint) history.removeAt(history.lastIndex)
                throw e
            }
        }

        private fun append(
            conv: Conversation,
            message: AiMessage,
        ) {
            conv.messages = conv.messages + message
            publish(conv)
        }

        private fun fail(
            conv: Conversation,
            message: String,
        ) = append(conv, AiMessage(nextId++, AiRole.ASSISTANT, message, isError = true))

        private fun friendly(e: GeminiException): String =
            when (e.httpCode) {
                400 -> "Gemini rejected the request: ${e.message}"
                401, 403 -> "Gemini did not accept the API key. Check it in Settings > Integrations."
                404 -> "That Gemini model was not found. Check the model name in Settings > Integrations."
                429 -> "Gemini says you are over the rate limit. Try again in a moment."
                else -> e.message ?: "Gemini could not be reached."
            }

        private fun activityFor(tool: String): String =
            when (tool) {
                "get_transcript" -> "Reading the transcript…"
                "search_youtube" -> "Searching YouTube…"
                "search_watch_history" -> "Looking through your watch history…"
                "get_search_history" -> "Looking through your searches…"
                "open_video" -> "Opening the video…"
                "seek_current_video" -> "Jumping to that moment…"
                else -> "Thinking…"
            }
    }
