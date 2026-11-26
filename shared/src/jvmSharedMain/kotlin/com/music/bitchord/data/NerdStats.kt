package com.music.bitchord.data

import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Architectural provenance of audio pipeline telemetry statistics.
 */
enum class TelemetryProvenance {
    AUTHORITATIVE,
    MEASURED,
    DERIVED,
    UNKNOWN,
}

/**
 * What the decoder itself proves about a Dolby stream.
 *
 * A source saying "eac3" says only which bitstream family it is about to send;
 * the object layer that makes it Atmos is not visible until the decoder is
 * configured for it. These three states keep what was *observed* apart from
 * what was *claimed*, so the badge can stop short of "Dolby Atmos" on a plain
 * 5.1 E-AC-3 bed — which is what a software FFmpeg fallback plays — without
 * refusing to play it or hiding that it is Dolby at all. See
 * [NerdStats.Snapshot.atmosEvidence].
 */
enum class AtmosEvidence {
    /** Not Dolby, or not measured yet. */
    NONE,

    /** The decoder was handed the AC-3/E-AC-3 family but reported no object layer. */
    DOLBY_BED,

    /** The decoder reported a JOC/Atmos input. The only state that claims objects. */
    DOLBY_OBJECTS,
}

/** Which tier a seek was served from — see [NerdStats.beginSeek]. */
enum class SeekTier {
    /** The bytes were already on disk. */
    CACHE,

    /** The target was inside what the player had buffered. */
    BUFFERED,

    /** The target had to be fetched from the network. */
    NETWORK,

    /** A live stream, where a seek is a jump in the live window. */
    LIVE,

    /** No URI was available to classify it. */
    UNKNOWN,
}

/**
 * The stages one track went through on its way to sound, in elapsed-time
 * milliseconds from a monotonic clock.
 *
 * Every timestamp is optional because every one of them can be missing on a
 * real path: a cache hit never resolves, a crossfade never reports a first
 * audio change, and a stream served by a decoder that was already warm never
 * stands one up. A reader must treat a null as "this leg did not happen (or
 * was not observed)", never as zero.
 */
data class TrackTiming(
    /** The listener's tap, or the queue moving on. */
    val requestedAtMs: Long? = null,
    /** The resolver handed back a stream URL. */
    val resolvedAtMs: Long? = null,
    /** The player's data source was opened for this track. */
    val firstByteAtMs: Long? = null,
    /** The audio decoder was configured for the stream. */
    val decoderAtMs: Long? = null,
    /** The player reached [androidx.media3.common.Player.STATE_READY]. */
    val readyAtMs: Long? = null,
    /** The first sample was actually being played. */
    val firstAudioAtMs: Long? = null,
) {
    /** The honest TTFS figure: selection to first audible sample. */
    val timeToFirstAudioMs: Long?
        get() = span(requestedAtMs, firstAudioAtMs)

    val resolveMs: Long? get() = span(requestedAtMs, resolvedAtMs)
    val firstByteMs: Long? get() = span(requestedAtMs, firstByteAtMs)
    val decoderMs: Long? get() = span(requestedAtMs, decoderAtMs)
    val readyMs: Long? get() = span(requestedAtMs, readyAtMs)
    val isComplete: Boolean get() = firstAudioAtMs != null

    private fun span(from: Long?, to: Long?): Long? =
        if (from == null || to == null || to < from) null else to - from
}

/**
 * One seek, and which tier served it.
 *
 * [latencyMs] is only known once audio starts again, so it is nullable: a
 * paused seek stays open until the listener presses play. The tier is
 * classified when the jump arrives and refined once the disk check answers,
 * because asking the cache while inside Media3's event dispatch would put a
 * filesystem read on the main thread.
 */
data class SeekRecord(
    val mediaId: String?,
    val fromMs: Long,
    val toMs: Long,
    val tier: SeekTier,
    val startedAtMs: Long,
    val landedAtMs: Long? = null,
) {
    val latencyMs: Long? get() = landedAtMs?.let { (it - startedAtMs).coerceAtLeast(0L) }
}

/**
 * What the audio decoder is actually being fed, for "stats for nerds".
 *
 * Every figure here is measured rather than inferred, and every label derived
 * from them — "High Quality", "Lossless", "Hi-Res Lossless" — is decided on
 * the measurements alone. Codec, sample rate, channel count and depth come
 * from the `Format` the audio renderer was configured with, topped up from the
 * stream's own header where its container left them blank; see
 * `PlaybackService.measure`. Bitrate is the one a container usually withholds,
 * so a lossless stream reports what its samples decode to and a lossy one
 * falls back to the bitrate of the stream the resolver genuinely chose for
 * that track. Anything the player hasn't reported stays null and is left out
 * of the display instead of being guessed at.
 *
 * [Snapshot.claimed] is the one figure here that is *not* measured, and is kept
 * apart from the rest for that reason: it is what a source said it was about
 * to send. Holding both is the point — a source promising 24-bit/192kHz while
 * the decoder reports 16-bit/48kHz is the single most likely way for a lossless
 * setting to be quietly doing nothing, and it is invisible unless the two
 * numbers are put side by side. See [Snapshot.downgraded].
 */
object NerdStats {

    data class Snapshot(
        val mimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
        /** From the decoder's PCM encoding, where it states one. */
        val bitDepth: Int? = null,
        /** What the source said it would serve, when it came from one that says. */
        val claimed: StreamFormat? = null,
        /** Authoritative source/provider that supplied this stream. */
        val sourceName: String? = null,
        /**
         * The file's container where it says something the codec doesn't —
         * "DSF" or "DFF" for DSD, which reaches the decoder as plain PCM.
         */
        val container: String? = null,
        /** Provenance of the displayed source/encoded bitrate. */
        val bitrateProvenance: TelemetryProvenance = TelemetryProvenance.UNKNOWN,
        /** True PCM throughput (sampleRate * bitDepth * channels / 1000). Never conflated with encoded bitrate. */
        val pcmDataRateKbps: Int? = null,
        /** Backend inside a multi-backend source that served this stream, if it says. */
        val backendName: String? = null,
        /** The decoder Media3 actually stood up, when it named one. */
        val decoderName: String? = null,
        /** Stage timestamps for the track this snapshot describes. */
        val timing: TrackTiming? = null,
    ) {
        /**
         * Whether what arrived is measurably worse than what was promised.
         *
         * Only ever true when both figures are known — an absent measurement is
         * not evidence of a downgrade, and reporting one on that basis would
         * make the warning worthless the moment it fired on a container that
         * simply doesn't state its rate.
         */
        val downgraded: Boolean
            get() {
                val wantedRate = claimed?.sampleRateHz
                val wantedDepth = claimed?.bitDepth
                return (wantedRate != null && sampleRateHz != null && sampleRateHz < wantedRate) ||
                    (wantedDepth != null && bitDepth != null && bitDepth < wantedDepth)
            }

        /**
         * Whether the decoder is genuinely being fed a lossless codec — the
         * figure the Now Playing screen's "Lossless" badge is gated on, not
         * just what a source promised. [claimed] alone would let a source
         * that said "FLAC" and quietly served Opus still light the badge.
         *
         * Which is exactly what it did, because this was written as
         * `claimed?.isLossless == true || …` — the claim on its own, the very
         * thing the paragraph above says it must not be. Observed: an upgrade
         * to a Tidal FLAC was served, recorded as the declared format, and
         * then died on `ERROR_CODE_IO_BAD_HTTP_STATUS`; playback recovered
         * onto YouTube's Opus and the badge went on reading "Lossless" over
         * it, because the claim outlived the stream that made it.
         *
         * So the decoder gets the only word. The claim used to stand in
         * before the renderer had been configured, on the grounds that a
         * wrong answer there lasts a second rather than a song; it no longer
         * does. A second of a badge that turns out to be wrong is still a
         * badge that was wrong, [current] is nulled across a track change so
         * that gap shows nothing rather than the previous track's figures,
         * and "measured or not stated" is a rule that can be read off the
         * screen — "measured, except briefly, when it is whatever the module
         * said" is not.
         */
        val isLossless: Boolean
            get() = isLosslessMime(mimeType)

        /**
         * What the decoder proved about the Dolby stream, if anything.
         *
         * Kept apart from [isDolby] and [isDolbyAtmos] because the two answer
         * different questions: a software FFmpeg decoder happily plays the 5.1
         * E-AC-3 bed and reports `audio/eac3`, and calling that "Dolby Atmos"
         * claims an object layer that was dropped before it reached the
         * speaker. The family is still Dolby (and still worth a Dolby line);
         * only a JOC/Atmos input lets the badge say Atmos.
         */
        val atmosEvidence: AtmosEvidence
            get() = when {
                isDolbyAtmosJocMime(mimeType) || isJocDecoderName(decoderName) ->
                    AtmosEvidence.DOLBY_OBJECTS
                isDolbyAtmosMime(mimeType) -> AtmosEvidence.DOLBY_BED
                else -> AtmosEvidence.NONE
            }

        /**
         * Dolby Atmos, and only when the decoder's own identity supports it.
         *
         * This used to accept any E-AC-3/AC-3 mime, and before that a source's
         * claim while the decoder had not spoken. Both were wrong in the same
         * direction: they claimed object audio for a stream that may be the
         * plain 5.1 bed, which is exactly what the bundled software decoder
         * plays. The claim still reaches the reader through [claimed], where it
         * is labelled as a claim; it is no longer what lights the badge.
         */
        val isDolbyAtmos: Boolean
            get() = atmosEvidence == AtmosEvidence.DOLBY_OBJECTS

        /**
         * Any Dolby bitstream, object-coded or not. The honest label for the
         * bed a device without a JOC decoder plays.
         */
        val isDolby: Boolean
            get() = atmosEvidence != AtmosEvidence.NONE

        /**
         * Whether this is better than CD quality — the line Tidal, Deezer and
         * Apple Music all draw it at: past 16-bit or past 48kHz, not merely
         * lossless. A 16-bit/44.1kHz FLAC is a bit-exact CD rip and gets
         * called "Lossless"; a 24-bit/96kHz one is "Hi-Res Lossless", because
         * calling both the same thing would flatten a distinction the
         * listener can plausibly hear.
         */
        val isHiRes: Boolean
            get() = isLossless && ((bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 48_000)

        /**
         * Whether this is lossy, but at the top of what lossy gets — a 320kbps
         * AAC or MP3 from a module's HIGH tier, rather than YouTube's 160kbps
         * Opus.
         *
         * Worth naming on screen because it is the honest answer often enough
         * to matter: plenty of catalogues simply have no lossless copy of a
         * track, and a badge with only two states — "Lossless" or nothing —
         * makes a good stream and a mediocre one look identical. Not called
         * lossless anywhere, because it isn't.
         *
         * Decided on the bitrate rather than on which source served it: a
         * 256kbps stream is a 256kbps stream wherever it came from — and on
         * [bitrateKbps] rather than on [claimed], for the reason [isLossless]
         * gives at length. A module that offers a LOSSLESS tier and then
         * serves SoundCloud at 128kbps is the ordinary case here, not the
         * exotic one.
         */
        val isHiQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) >= HI_QUALITY_KBPS

        /**
         * Whether this is a medium quality stream (e.g. YouTube Opus ~160kbps, AAC 128-192kbps).
         * Sits strictly between low-quality (<= 80kbps) and high-quality (>= 256kbps).
         */
        val isMediumQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) in (LOW_QUALITY_MAX_KBPS + 1) until HI_QUALITY_KBPS

        /**
         * Whether this is a low quality stream (e.g. YouTube Opus 50-70kbps).
         */
        val isLowQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) in 1..LOW_QUALITY_MAX_KBPS

        /**
         * The bitrate worth printing.
         *
         * Lossless prints the PCM decode rate (1411 for CD, 4608 for
         * 24/96) — never the container's compressed figure, which says how
         * compressible the recording was, not what plays. A FLAC header
         * claiming 171 kbps is exactly the lie this suppresses: unknown PCM
         * prints no rate at all rather than a wrong one. DSD keeps its
         * encoded rate — 1 bit × 2.8 MHz × 2 ch is exact by format, not an
         * estimate. Lossy prints the encoded rate, the only one it has.
         */
        fun displayBitrateKbps(): Int? =
            if (isLossless && !isDsdMime(mimeType)) pcmDataRateKbps else bitrateKbps
    }

    /**
     * What the renderer's input mime type looks like when the bytes behind it
     * are bit-exact.
     *
     * `audio/raw` is here because that is how Media3 names PCM — a WAV stream
     * reaches the renderer as raw samples, not as `audio/wav`.
     */
    private val LOSSLESS_CODEC_SUFFIXES = listOf("flac", "alac", "raw")

    /**
     * Whether [mimeType] names a bit-exact codec.
     *
     * Exposed because the decoder's own verdict is also what decides whether a
     * track playing from the disk cache is worth hunting a better copy of — see
     * [QualityUpgrade.adoptUnresolved][com.music.bitchord.playback.QualityUpgrade.adoptUnresolved].
     * Reading [Snapshot.isLossless] there instead would mean trusting whichever
     * track the last [current] publish happened to describe, which after a
     * queue advance is the previous one.
     */
    fun isLosslessMime(mimeType: String?): Boolean =
        mimeType != null && (LOSSLESS_CODEC_SUFFIXES.any { mimeType.endsWith(it) } || mimeType.startsWith("audio/dsd"))

    /**
     * A rate of a megahertz or more, as DSD's are named: 2822400 -> "2.8224 MHz".
     * Null below that, where each caller keeps its own kHz wording. Exact
     * digits rather than one decimal place, since the DSD rates are all
     * multiples of 44.1 kHz and rounding them makes them unrecognisable.
     */
    fun megahertzLabel(hz: Int): String? =
        if (hz < 1_000_000) null
        else java.math.BigDecimal(hz).movePointLeft(6).stripTrailingZeros().toPlainString() + " MHz"

    /**
     * Whether [mimeType] is the decoder being handed the Dolby family.
     *
     * The AC-3/E-AC-3 family, not only JOC. A DASH manifest routinely states
     * `ec-3` and leaves the object coding to a supplemental property, so the
     * decoder's input is `audio/eac3` on a stream that may be Atmos; the
     * FFmpeg bed fallback reports the same. This answers "is this Dolby", and
     * [isDolbyAtmos] is the narrower question. Both are needed: the DSP that
     * must stand down would fight either, while the badge may only claim the
     * object layer the decoder actually reported.
     */
    fun isDolbyAtmosMime(mimeType: String?): Boolean {
        if (mimeType == null) return false
        val bare = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return "eac3" in bare || "ec-3" in bare || "ac-3" in bare || "ac3" in bare
    }

    /**
     * Whether [mimeType] names Dolby's joint object coding — the only mime that
     * proves the object layer reached the decoder.
     *
     * Deliberately narrower than [isDolbyAtmosMime]: plain `audio/eac3` and
     * `audio/ac3` are the same family without that proof.
     */
    fun isDolbyAtmosJocMime(mimeType: String?): Boolean {
        if (mimeType == null) return false
        val bare = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return "eac3-joc" in bare ||
            "ec3-joc" in bare ||
            "eac3_joc" in bare ||
            "ec3_joc" in bare ||
            "dolby-atmos" in bare
    }

    /**
     * Whether a decoder's own name is one that claims object audio.
     *
     * A vendor JOC decoder often names itself rather than relying on the mime
     * the renderer reports. Used only as supporting evidence for
     * [AtmosEvidence.DOLBY_OBJECTS]; a generic E-AC-3 decoder — including the
     * bundled FFmpeg one, which drops the objects and plays the bed — does not
     * match and therefore cannot claim Atmos.
     */
    fun isJocDecoderName(decoderName: String?): Boolean {
        val name = decoderName?.lowercase(Locale.ROOT) ?: return false
        return "joc" in name || "atmos" in name
    }

    fun isRawPcm(mimeType: String?): Boolean =
        mimeType != null && (mimeType == "audio/raw" || mimeType.endsWith("wav", ignoreCase = true) || mimeType == "audio/x-wav")

    fun isDsdMime(mimeType: String?): Boolean =
        mimeType?.startsWith("audio/dsd") == true

    /**
     * The upper bound in kbps for a low-bitrate / data-saver lossy stream.
     */
    const val LOW_QUALITY_MAX_KBPS = 80

    /**
     * The bitrate a lossy stream has to reach to be worth calling out.
     *
     * At 256 an Apple-style AAC counts and YouTube's Opus, which tops out
     * around 160, does not — which is the distinction the label exists to
     * draw.
     */
    const val HI_QUALITY_KBPS = 256

    fun codecLabel(mimeType: String?): String? {
        if (mimeType == null) return null
        return when {
            mimeType.endsWith("flac", ignoreCase = true) -> "FLAC"
            mimeType.endsWith("opus", ignoreCase = true) -> "Opus"
            mimeType.endsWith("mp4a-latm", ignoreCase = true) || mimeType.endsWith("aac", ignoreCase = true) -> "AAC"
            mimeType.endsWith("mpeg", ignoreCase = true) || mimeType.endsWith("mp3", ignoreCase = true) -> "MP3"
            mimeType.endsWith("alac", ignoreCase = true) -> "ALAC"
            mimeType.endsWith("raw", ignoreCase = true) -> "PCM"
            mimeType.startsWith("audio/dsd", ignoreCase = true) -> mimeType.substringAfter('/').uppercase()
            mimeType.endsWith("vorbis", ignoreCase = true) -> "Vorbis"
            mimeType.endsWith("eac3-joc", ignoreCase = true) -> "E-AC-3 JOC"
            mimeType.endsWith("eac3", ignoreCase = true) -> "E-AC-3"
            mimeType.contains("/") -> mimeType.substringAfterLast("/").uppercase()
            else -> mimeType.uppercase()
        }
    }

    val current = MutableStateFlow<Snapshot?>(null)

    // ── Time to first sound ───────────────────────────────────────────────

    /**
     * Stage timestamps per track, recorded where each stage actually happens.
     *
     * The service used to log one figure — selection to `isPlaying` — and that
     * is the right top-level number but a useless diagnosis: it says a start
     * was slow, not whether the resolver, the first byte, the decoder or the
     * load-control gate spent the time. These are the legs, all on one clock,
     * with [TrackTiming.timeToFirstAudioMs] remaining the figure a listener
     * feels.
     *
     * Written from the loader thread, the main thread and Media3's analytics
     * dispatch, hence the concurrent map and the bounded size.
     */
    private val timings = ConcurrentHashMap<String, TrackTiming>()

    /** Monotonic, like every other timing in the playback path. */
    private fun nowElapsedMs(): Long = System.nanoTime() / 1_000_000L

    private fun markTiming(mediaId: String?, transform: (TrackTiming) -> TrackTiming) {
        if (mediaId.isNullOrBlank()) return
        if (timings.size >= MAX_TIMING_ENTRIES) timings.clear()
        timings.compute(mediaId) { _, current -> transform(current ?: TrackTiming()) }
    }

    /** A new track was selected — the listener's tap, or the queue moving on. */
    fun beginTiming(mediaId: String?) {
        if (mediaId.isNullOrBlank()) return
        if (timings.size >= MAX_TIMING_ENTRIES) timings.clear()
        timings[mediaId] = TrackTiming(requestedAtMs = nowElapsedMs())
    }

    /** The resolver answered with the stream URL that will be opened. */
    fun onStreamResolved(mediaId: String?) = markTiming(mediaId) {
        if (it.resolvedAtMs != null) it else it.copy(resolvedAtMs = nowElapsedMs())
    }

    /** The player's data source was opened for this track, cache hit or network. */
    fun onFirstByte(mediaId: String?) = markTiming(mediaId) {
        if (it.firstByteAtMs != null) it else it.copy(firstByteAtMs = nowElapsedMs())
    }

    /** The audio renderer was configured for the stream. */
    fun onDecoderReady(mediaId: String?) = markTiming(mediaId) {
        if (it.decoderAtMs != null) it else it.copy(decoderAtMs = nowElapsedMs())
    }

    /** The player reached [androidx.media3.common.Player.STATE_READY]. */
    fun onReady(mediaId: String?) = markTiming(mediaId) {
        if (it.readyAtMs != null) it else it.copy(readyAtMs = nowElapsedMs())
    }

    /** Sound is actually being played. The terminal timestamp of TTFS. */
    fun onFirstAudio(mediaId: String?) = markTiming(mediaId) {
        if (it.firstAudioAtMs != null) it else it.copy(firstAudioAtMs = nowElapsedMs())
    }

    fun timingFor(mediaId: String?): TrackTiming? = mediaId?.let { timings[it] }

    fun forgetTiming(mediaId: String?) {
        if (mediaId != null) timings.remove(mediaId)
    }

    // ── Seek tiers ────────────────────────────────────────────────────────

    /**
     * Seeks that have not started sounding again yet, by track.
     *
     * A seek's latency is only over when audio comes back, and the callback
     * that knows is [`onAudioPositionAdvancing`][androidx.media3.exoplayer.analytics.AnalyticsListener.onAudioPositionAdvancing],
     * which may arrive well after the jump — or not at all while paused. This
     * keeps the open record so the two events can be paired without assuming
     * they are adjacent.
     */
    private val pendingSeeks = ConcurrentHashMap<String, SeekRecord>()

    private val _recentSeeks = MutableStateFlow<List<SeekRecord>>(emptyList())

    /** The last few seeks, newest last, for diagnostics and future UI. */
    val recentSeeks: StateFlow<List<SeekRecord>> = _recentSeeks.asStateFlow()

    fun beginSeek(
        mediaId: String?,
        fromMs: Long,
        toMs: Long,
        tier: SeekTier,
    ) {
        val id = mediaId ?: return
        val record = SeekRecord(
            mediaId = id,
            fromMs = fromMs,
            toMs = toMs,
            tier = tier,
            startedAtMs = nowElapsedMs(),
        )
        pendingSeeks[id] = record
        publishSeek(record)
    }

    /**
     * Replaces the tier of the open seek for [mediaId] when a disk check
     * answers after the jump was classified from the player's buffer alone.
     */
    fun refineSeekTier(mediaId: String?, tier: SeekTier) {
        val id = mediaId ?: return
        val updated = pendingSeeks.computeIfPresent(id) { _, record -> record.copy(tier = tier) } ?: return
        publishSeek(updated)
    }

    /** Audio started again, closing the open seek for [mediaId] if there is one. */
    fun finishSeek(mediaId: String?) {
        val id = mediaId ?: return
        val record = pendingSeeks.remove(id) ?: return
        publishSeek(record.copy(landedAtMs = nowElapsedMs()))
    }

    /** The most recently recorded seek, open or closed. */
    fun lastSeek(): SeekRecord? = _recentSeeks.value.lastOrNull()

    private fun publishSeek(record: SeekRecord) {
        _recentSeeks.update { records ->
            val index = records.indexOfFirst {
                it.mediaId == record.mediaId && it.startedAtMs == record.startedAtMs
            }
            if (index >= 0) {
                records.toMutableList().also { it[index] = record }
            } else {
                (records + record).takeLast(MAX_SEEK_ENTRIES)
            }
        }
    }

    /**
     * YouTube video ids with a module lookup racing YouTube's own resolve
     * for the stream to actually play — see
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s
     * resolving data source. Both start together and whichever answers first
     * plays; the module is the one still worth hearing about, because a
     * YouTube win only means the search continues under the music — so this
     * is what the UI shows "looking for a better copy" from. A track leaves
     * the set the moment its own lookup settles either way, never on a timer.
     */
    val racingLossless = MutableStateFlow<Set<String>>(emptySet())

    fun onLosslessRaceStart(videoId: String) {
        // Upgrade lookups also run for read-ahead items, so two loader threads
        // can arrive here together. Assigning `value += videoId` is a
        // read/modify/write sequence: both threads can read the same old set and
        // the last assignment then erases the other track's marker. That left
        // the audible track upgrading in the background while Now Playing fell
        // through to its plain "High quality" label. StateFlow.update retries
        // the transform atomically when another writer wins that race.
        racingLossless.update { it + videoId }
    }

    fun onLosslessRaceEnd(videoId: String) {
        // Atomic for the same reason as start: completing one read-ahead lookup
        // must not discard a concurrently-started lookup for another track.
        racingLossless.update { it - videoId }
    }

    /**
     * Bitrate in kbps of the stream picked for each videoId.
     *
     * Keyed by track rather than kept as a single "last picked": the read-ahead
     * resolves the *next* track through the same code, so one loose value would
     * end up describing the wrong song.
     */
    private val picked = ConcurrentHashMap<String, Int>()

    /** As [picked], for the richer format a non-YouTube source can state. */
    private val declared = ConcurrentHashMap<String, StreamFormat>()

    /** Authoritative source name that supplied the stream, keyed by media/track id. */
    private val sources = ConcurrentHashMap<String, String>()

    /** Backend inside a multi-backend source that served the stream, same keys. */
    private val backends = ConcurrentHashMap<String, String>()

    /**
     * The decoder Media3 stood up for a track, by media id.
     *
     * Kept rather than only put on the snapshot because the decoder's identity
     * is evidence in its own right — see [Snapshot.atmosEvidence] — and the
     * analytics callback that names it can arrive before or after the format
     * change that builds the snapshot.
     */
    private val decoders = ConcurrentHashMap<String, String>()

    fun onDecoderIdentified(mediaId: String?, decoderName: String?) {
        if (mediaId.isNullOrBlank() || decoderName.isNullOrBlank()) return
        if (decoders.size >= MAX_REMEMBERED) decoders.clear()
        decoders[mediaId] = decoderName
    }

    fun decoderNameFor(mediaId: String?): String? = mediaId?.let { decoders[it] }

    /**
     * The last refusal each stream host served, keyed by host. A 403 on a
     * stream URL is where upgrades actually die — the catalogue answered,
     * the match was made, and then the bytes were refused — and the status
     * code alone never says why. The body usually does ("Addon is inactive",
     * "Token inválido"), so it is kept here, briefly, for the Sources
     * screen to report instead of failing silently into a revert.
     */
    private val refusals = ConcurrentHashMap<String, Pair<String, Long>>()

    /** How long a recorded refusal stays reportable. */
    const val REFUSAL_TTL_MS = 15 * 60 * 1000L

    fun noteRefusal(host: String?, message: String?) {
        if (host.isNullOrBlank() || message.isNullOrBlank()) return
        if (refusals.size >= MAX_REMEMBERED) refusals.clear()
        refusals[host.lowercase()] = message.take(160) to System.currentTimeMillis()
    }

    /** The recorded refusal for [host], or null when none is fresh. */
    fun refusalFor(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val (message, at) = refusals[host.lowercase()] ?: return null
        if (System.currentTimeMillis() - at > REFUSAL_TTL_MS) {
            refusals.remove(host.lowercase())
            return null
        }
        return message
    }

    /** Drop all recorded refusals — a manual retry asks everything again. */
    fun clearRefusals() {
        refusals.clear()
    }

    /** Test-only alias. Public because app-module tests need it. */
    fun clearRefusalsForTests() {
        clearRefusals()
    }

    fun onStreamPicked(videoId: String, kbps: Int, source: String? = null) {
        if (kbps > 0) {
            // Enough for the queue in hand; this is a lookup, not a store.
            if (picked.size >= MAX_REMEMBERED) picked.clear()
            picked[videoId] = kbps
        }
        if (!source.isNullOrBlank()) {
            if (sources.size >= MAX_REMEMBERED) sources.clear()
            sources[videoId] = source
        }
    }

    /** Recorded as a source hands over a stream, keyed by that source's own track id. */
    fun onSourceStream(
        trackId: String?,
        format: StreamFormat,
        source: String? = null,
        backend: String? = null,
    ) {
        if (trackId.isNullOrBlank()) return
        if (declared.size >= MAX_REMEMBERED) declared.clear()
        declared[trackId] = format
        if (!source.isNullOrBlank()) {
            if (sources.size >= MAX_REMEMBERED) sources.clear()
            sources[trackId] = source
        }
        if (!backend.isNullOrBlank()) {
            if (backends.size >= MAX_REMEMBERED) backends.clear()
            backends[trackId] = backend
        }
    }

    fun recordSource(mediaId: String?, source: String?) {
        if (mediaId.isNullOrBlank() || source.isNullOrBlank()) return
        if (sources.size >= MAX_REMEMBERED) sources.clear()
        sources[mediaId] = source
    }

    fun exactSourceFor(mediaId: String?): String? = mediaId?.let { sources[it] }

    fun sourceFor(mediaId: String?): String? {
        val key = mediaId ?: return null
        return sources[key]
            ?: SourceTrackKeys.parse(key)?.second?.let { sources[it] }
    }

    /** Backend that served [mediaId], same lookup shape as [sourceFor]. */
    fun backendFor(mediaId: String?): String? {
        val key = mediaId ?: return null
        return backends[key]
            ?: SourceTrackKeys.parse(key)?.second?.let { backends[it] }
    }

    fun pickedBitrateKbps(videoId: String?): Int? = videoId?.let { picked[it] }

    /**
     * Undoes [onSourceStream] for [trackId].
     *
     * For when a swap to a claimed-better stream doesn't pan out and the
     * player falls back to what it had — see
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s
     * upgrade revert. Without this the claim from the abandoned stream keeps
     * describing the one that's actually playing, which is how a reverted
     * FLAC swap leaves the "Lossless" badge lit over plain Opus.
     */
    fun clearDeclared(trackId: String?) {
        if (trackId != null) {
            declared.remove(trackId)
            sources.remove(trackId)
            backends.remove(trackId)
        }
    }

    /**
     * @param mediaId the queue's id for the track, which for a source-backed
     *   one wraps the source's id — unwrapped here so callers don't each have
     *   to know the key format.
     */
    fun declaredFormat(mediaId: String?): StreamFormat? {
        val key = mediaId ?: return null
        return declared[key]
            ?: SourceTrackKeys.parse(key)?.second?.let { declared[it] }
    }

    fun onTrackTransition() {
        current.value = null
    }

    /**
     * Drops everything measured about the last player, because there isn't one
     * any more.
     *
     * All of this describes a stream that a particular player was reading, and
     * it is scoped to the *process* while the player it describes is scoped to
     * [PlaybackService][com.music.bitchord.playback.PlaybackService] — which the
     * app being closed destroys while leaving the process alive to be reused.
     * Nothing else clears it: [current] is nulled when the queue moves on, and
     * a service standing back up is not the queue moving on.
     *
     * Measured, with the process surviving throughout — one log buffer holds
     * both halves:
     *
     * ```
     *   15:12:11  upgraded to FLAC at 4759ms      ← last session
     *   ——— app closed, service destroyed ———
     *   15:13:38  AdEKgwUqPKI <- audio/opus       ← played from the cache
     * ```
     *
     * Between those two lines the Now Playing screen read "Lossless" over a
     * player that had not been handed a single byte, and the nerd stats sheet
     * read `audio/opus · 160 kbps (source said: FLAC)` afterwards. Both are the
     * same fact: [Snapshot.isLossless] falls back to [Snapshot.claimed] while
     * the decoder has not spoken, and the claim came from a stream that had
     * stopped existing a minute earlier.
     *
     * @see com.music.bitchord.playback.QualityUpgrade.forgetLastSession for the
     *   half of this that decides whether the track gets its lossless copy back
     *   rather than merely how it is labelled.
     */
    fun forgetLastSession() {
        current.value = null
        racingLossless.value = emptySet()
        picked.clear()
        declared.clear()
        sources.clear()
        backends.clear()
        decoders.clear()
        timings.clear()
        pendingSeeks.clear()
        _recentSeeks.value = emptyList()
    }

    private const val MAX_REMEMBERED = 64

    /** Enough for the queue in hand plus whatever read-ahead touches. */
    private const val MAX_TIMING_ENTRIES = 64

    private const val MAX_SEEK_ENTRIES = 16
}
