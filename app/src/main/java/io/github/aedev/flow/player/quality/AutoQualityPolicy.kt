package io.github.aedev.flow.player.quality

import io.github.aedev.flow.player.stream.VideoCodecUtils
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Auto's rules for picking a stream. The height comes from [AutoQualityLadder], which maps the
 * connection speed to a quality; this picks the stream at that height (or the nearest below it) in
 * the best-ranked codec.
 */
internal object AutoQualityPolicy {
    /** The stream at the height the ladder names for [estimateBps]. */
    fun initialPick(
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        if (streams.isEmpty()) return null
        return pickAtHeight(streams, bestHeight(streams, estimateBps), null, preferredCodec)
    }

    /** Straight to the ladder's height, when the estimate has risen above the current one. */
    fun stepUp(
        current: VideoStream,
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        val target = bestHeight(streams, estimateBps)
        if (target <= height(current)) return null
        return pickAtHeight(streams, target, current, preferredCodec)
    }

    /** Straight to the ladder's height, when the estimate has fallen below the current one. */
    fun stepDownForBandwidth(
        current: VideoStream,
        streams: List<VideoStream>,
        estimateBps: Long,
        preferredCodec: String?,
    ): VideoStream? {
        val target = bestHeight(streams, estimateBps)
        if (target >= height(current)) return null
        return pickAtHeight(streams, target, current, preferredCodec)
    }

    /** The tallest height at or under the ladder's target; the shortest there is when none fits. */
    private fun bestHeight(
        streams: List<VideoStream>,
        estimateBps: Long,
    ): Int {
        val target = AutoQualityLadder.targetHeight(estimateBps)
        return streams.map(::height).filter { it <= target }.maxOrNull() ?: streams.minOf(::height)
    }

    private fun pickAtHeight(
        streams: List<VideoStream>,
        height: Int,
        current: VideoStream?,
        preferredCodec: String?,
    ): VideoStream? {
        val codec = current?.let { VideoCodecUtils.codecKeyFromStream(it) }
        return streams
            .filter { height(it) == height }
            .minWithOrNull(
                compareBy<VideoStream> { if (codec != null && VideoCodecUtils.codecKeyFromStream(it) == codec) 0 else 1 }
                    .thenBy { VideoCodecUtils.codecRankWithPreference(it, preferredCodec) }
                    .thenByDescending { it.bitrate },
            )
    }

    fun stepDown(
        current: VideoStream,
        streams: List<VideoStream>,
        preferredCodec: String?,
    ): VideoStream? = neighbour(current, streams, preferredCodec, nearestFirst = false) { height(it) < height(current) }

    private fun neighbour(
        current: VideoStream,
        streams: List<VideoStream>,
        preferredCodec: String?,
        nearestFirst: Boolean = true,
        side: (VideoStream) -> Boolean,
    ): VideoStream? {
        val candidates = streams.filter(side).ifEmpty { return null }
        val height = if (nearestFirst) candidates.minOf(::height) else candidates.maxOf(::height)
        val codec = VideoCodecUtils.codecKeyFromStream(current)
        return candidates
            .filter { height(it) == height }
            .minWithOrNull(
                compareBy<VideoStream> { if (VideoCodecUtils.codecKeyFromStream(it) == codec) 0 else 1 }
                    .thenBy { VideoCodecUtils.codecRankWithPreference(it, preferredCodec) }
                    .thenByDescending { it.bitrate },
            )
    }

    private fun height(stream: VideoStream): Int = VideoCodecUtils.normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(stream))
}
