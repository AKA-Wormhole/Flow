package io.github.aedev.flow.ai

import android.content.Context
import android.util.LruCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.ai.GeminiClient
import io.github.aedev.flow.data.ai.GeminiSettingsStore
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

/** Videos shorter than this are not worth splitting. */
private const val MIN_VIDEO_SECONDS = 180
private const val MAX_TRANSCRIPT_CHARS = 100_000

private val CHAPTER_PROMPT =
    """
    You split a video's transcript into chapters for its seek bar. The transcript is lines of "[mm:ss] text" (or "[h:mm:ss] text").

    Reply with ONLY a JSON array, no markdown and no commentary: [{"start": <seconds from the start>, "title": "<chapter title>"}, ...]

    Rules:
    - The first chapter starts at 0.
    - Starts are whole seconds, strictly increasing, and inside the video.
    - About one chapter per 2 to 5 minutes: at least 3 and at most 12 chapters, each at least 30 seconds long.
    - Start each chapter where its topic really begins in the transcript. Never invent topics.
    - Titles are ALWAYS written in English, whatever language the transcript is in. Translate the topic into English; never reply in another language or script.
    - Titles are clear, specific and natural, 2 to 6 words in Title Case (e.g. "Setting Up the Project", "Why the Engine Overheats"). Name the actual topic; avoid vague titles like "Introduction Part 2", "Next Topic" or "Continued", and avoid clickbait.
    - No timestamps, no quotes and no numbering in titles.
    """.trimIndent()

/**
 * Makes chapters for a video that has none, from its captions, with the user's own Gemini key. Answers are
 * kept for the life of the app, so each video is asked about once; a failed call is not kept and may be
 * retried the next time the video opens.
 */
@Singleton
class AiChapterGenerator
    @Inject
    internal constructor(
        private val client: GeminiClient,
        private val settings: GeminiSettingsStore,
        private val tools: FlowAiTools,
    ) {
        private val cache = LruCache<String, List<AiChapter>>(40)

        /** Empty when the feature is off, the video is too short, it has no captions, or the reply was unusable. */
        internal suspend fun chaptersFor(
            videoId: String,
            durationSeconds: Int,
        ): List<AiChapter> {
            cache.get(videoId)?.let { return it }
            if (!settings.isEnabled() || !settings.isChaptersEnabled()) return emptyList()
            val apiKey = settings.currentApiKey()
            if (apiKey.isBlank()) return emptyList()

            val cues = tools.cuesFor(videoId)
            if (cues.isNullOrEmpty()) return remember(videoId, emptyList())
            val length = durationSeconds.takeIf { it > 0 } ?: (cues.last().endMs / 1000).toInt()
            if (length < MIN_VIDEO_SECONDS) return remember(videoId, emptyList())

            val prompt = "Video length: $length seconds. Write every chapter title in English.\n\nTranscript:\n${condenseTranscript(
                cues,
                MAX_TRANSCRIPT_CHARS,
            )}"
            val turn =
                client.generate(
                    apiKey = apiKey,
                    model = settings.currentModel(),
                    systemInstruction = CHAPTER_PROMPT,
                    contents = JsonArray(listOf(GeminiClient.userContent(prompt))),
                    functionDeclarations = JsonArray(emptyList()),
                )
            return remember(videoId, parseAiChapters(turn.text, length))
        }

        private fun remember(
            videoId: String,
            chapters: List<AiChapter>,
        ): List<AiChapter> = chapters.also { cache.put(videoId, it) }
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiChapterEntryPoint {
    fun aiChapterGenerator(): AiChapterGenerator
}

/** For code that Hilt does not build, such as the player's session applier. */
internal fun aiChapterGenerator(context: Context): AiChapterGenerator =
    EntryPointAccessors
        .fromApplication(context.applicationContext, AiChapterEntryPoint::class.java)
        .aiChapterGenerator()
