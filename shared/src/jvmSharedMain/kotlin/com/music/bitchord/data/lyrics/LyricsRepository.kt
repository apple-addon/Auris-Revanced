package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the player gets its lyrics.
 *
 * Sources tried in [order] — the user's own priority list in Settings,
 * defaulting to [LyricsSource.offered]; a [hidden][LyricsSource.hidden] one is
 * never asked, whatever the saved settings say:
 *
 *  - [LrcRed] — Apple Music TTML filed by ISRC, and the catalogue
 *    [BiniLyrics] itself answers from.
 *  - [Extension][LyricsSource.EXTENSION] — an installed `.sflx` extension
 *    that can answer lyrics, asked through the host.
 *  - [BetterLyrics], [PaxSenix] and [BiniLyrics] — Apple Music TTML,
 *    per-syllable, from three independent hosts so one having a bad day
 *    doesn't cost the timing.
 *  - [LyricsPlus] — the YouLy+ backend; finest timing of the lot, flakiest hosting.
 *  - [SimpMusicLyrics] — keyed on the video id, so it can't fetch the wrong edit.
 *  - [Unison] — contributed rather than licensed; thin, and occasionally the
 *    only place a track exists.
 *  - [LrcLib], [Musixmatch], [KuGou] — line-synced only, but between them
 *    almost always up, and [KuGou] carries a lot that the others don't.
 *
 * Enabled sources are raced a few at a time — at most [MAX_CONCURRENT_SOURCES]
 * in flight, each given [SOURCE_TIMEOUT_MS] — and their answers are still taken
 * in [order]: the loop awaits them one at a time in that sequence, so a
 * lower-priority source finishing first never preempts one still pending ahead
 * of it. Raced together, a miss on each source costs one round trip rather than
 * one per source; the cap keeps a long list of providers from opening a
 * connection to every host at once, and the deadline keeps the one host having
 * a bad morning from holding up the rest.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken
 * from the highest-priority source that had it — better a whole line lighting
 * up in sync than the right animation on lyrics that don't exist.
 *
 * ### Matching on the recording
 *
 * Everything above matches a *name*, and a name is ambiguous in a way that
 * matters: a single and its album cut share a title, an artist and very nearly
 * a length, and routinely differ in the words. An ISRC names one recording and
 * settles it. Two sources accept one — [BiniLyrics] and [LyricsPlus] — and
 * [BiniLyrics] is also the one that hands them out, reporting the ISRC of
 * whatever its own search matched.
 *
 * So the recording is settled *in* the race rather than in front of it: one
 * small search against [BiniLyrics], run beside the source requests so it no
 * longer adds a round trip before the first of them starts. It is capped at
 * [IDENTIFY_TIMEOUT_MS], sources that can use an ISRC give it a short
 * [IDENTIFY_WAIT_MS] head start and otherwise ask by name, and its result is
 * kept against the video id — a track asked about twice pays for this once. A
 * downloaded or local file can skip it entirely: its own tags name the
 * recording, which is what the [isrc] parameter is for.
 *
 * A finished lookup is also kept by ISRC for [LYRICS_CACHE_TTL_NANOS], so
 * another video naming the same recording is answered without asking anybody
 * again. Only a timed or word-synced winner is stored — a plain fallback is
 * worth retrying — and only while it is still a source the listener has
 * enabled.
 *
 * The identify is only run when the user has [LyricsSource.BINI_LYRICS]
 * enabled. It is a request to a third party like any other, and a source
 * somebody has turned off is a source this app does not contact — not even for
 * something it would only use to help the sources they left on.
 */
object LyricsRepository {

    /** Lyrics, and which source they turned out to come from. */
    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* has come
     * back: off, the highest-priority source's own answer is taken as-is,
     * word-synced or not — priority is priority, and second-guessing it with
     * more network calls after it has already answered is not what "first"
     * was supposed to mean. On, a merely line-synced answer is kept only as a
     * fallback, and the search keeps going through the rest of [order] for a
     * word-synced one, taking the top-priority source that has one.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = false,
        isrc: String? = null,
        /** Called from the provider job itself, including for lazily-started providers. */
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        /**
         * Reports a completed provider attempt. A null result is a genuine miss;
         * a cancelled race loser is deliberately not reported as one.
         */
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        /** Lets callers turn a cancelled race loser back into "not fetched". */
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
    ): Result? = coroutineScope {
        val sequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in sources && !it.hidden }

        // Every source but [SimpMusicLyrics] is asked for a name, and
        // YouTube's is not the name anyone catalogued. Cleaned once, here,
        // rather than by whichever source thought to do it for itself.
        val searchTitle = title.forLyricsSearch()
        val searchArtist = artist.artistForLyricsSearch()

        // What the caller knows beats what we worked out last time, and both
        // beat asking again. A recording already answered for within the TTL
        // needs neither the identify nor the race below.
        val known = isrc?.takeIf { it.isNotBlank() } ?: isrcs[videoId]
        cachedLyrics(known, sources, prioritizeSyllableSync)?.let { return@coroutineScope it }

        // The identify that used to run before the race, now riding beside it.
        // UNDISPATCHED so it takes its permit and reaches the network before
        // any source job is launched; the suspending call returns control
        // immediately, so this does not hold the lookup up. Sources that can
        // use an ISRC give it a short head start below; the rest do not wait
        // for it at all.
        val permits = Semaphore(MAX_CONCURRENT_SOURCES)
        val identified = AtomicReference<BiniLyrics.Hit?>(null)
        val identify: Deferred<BiniLyrics.Hit?>? =
            if (known == null && LyricsSource.BINI_LYRICS in sequence) {
                async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    permits.withPermit {
                        withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
                            runCatching {
                                BiniLyrics.identify(searchTitle, searchArtist, durationMs, album)
                            }.getOrNull()
                        }?.also { hit ->
                            identified.set(hit)
                            remember(videoId, hit.isrc)
                        }
                    }
                }
            } else {
                null
            }
        val documents = SharedDocuments(this)

        // Genius is a plain text web scraper. To preserve bandwidth and avoid rate-limiting,
        // it starts lazily and is only contacted if all higher-priority synced sources miss.
        val racing: List<Pair<LyricsSource, Deferred<Result?>>> = sequence.map { source ->
            val startMode = if (source == LyricsSource.GENIUS) CoroutineStart.LAZY else CoroutineStart.DEFAULT
            source to async(Dispatchers.IO, start = startMode) {
                try {
                    permits.withPermit {
                        onSourceStarted?.invoke(source)
                        val found = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            val (hit, recording) = recordingFor(source, known, identify)
                            fetch(
                                source,
                                videoId,
                                searchTitle,
                                searchArtist,
                                durationMs,
                                album,
                                recording,
                                hit,
                                documents::get,
                            )
                        }?.let { result(source, it) }
                        onSourceResult?.invoke(source, found)
                        found
                    }
                } catch (cancelled: CancellationException) {
                    onSourceCancelled?.invoke(source)
                    throw cancelled
                } catch (_: Exception) {
                    onSourceResult?.invoke(source, null)
                    null
                }
            }
        }

        try {
            var lineSynced: Result? = null
            for ((source, job) in racing) {
                // If we already found a line-synced or better result, skip Genius completely
                if (lineSynced != null && source == LyricsSource.GENIUS) continue

                val found = runCatching { job.await() }.getOrNull() ?: continue
                if (found.lines.any { it.isWordSynced }) {
                    rememberLyrics(currentRecording(known, identified), found)
                    return@coroutineScope found
                }
                if (!prioritizeSyllableSync && found.lines.any { it.timeMs > 0 }) {
                    rememberLyrics(currentRecording(known, identified), found)
                    return@coroutineScope found
                }
                if (lineSynced == null) lineSynced = found
            }
            lineSynced?.also { rememberLyrics(currentRecording(known, identified), it) }
        } finally {
            // Whoever lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they are still running.
            racing.forEach { it.second.cancel() }
            identify?.cancel()
            documents.cancel()
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        /** What the ISRC identify already found, where it ran; saves a second search. */
        hit: BiniLyrics.Hit?,
        /** Fetches a document once per lookup, however many sources want it. */
        get: suspend (String) -> String?,
    ): List<LyricLine>? {
        val found = when (source) {
            LyricsSource.LRC_RED -> LrcRed.lyrics(title, artist, durationMs, isrc, get)
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
            LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)
            LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)
            LyricsSource.BINI_LYRICS ->
                (hit?.let { BiniLyrics.lyricsFor(it, get) }
                    ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc, get))
                    ?.also { remember(videoId, it.isrc) }
                    ?.lines
            LyricsSource.UNISON -> Unison.lyrics(title, artist, durationMs, album)
            LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)
            LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)
            LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
            LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)
            LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
            LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
            LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)
            LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)
            LyricsSource.GENIUS -> Genius.lyrics(title, artist)
            LyricsSource.EXTENSION -> extensionProvider?.lyrics(title, artist, album, durationMs)
        }
        return found
    }

    /**
     * The fetcher that answers for an installed `.sflx` extension.
     *
     * Only the host can run an extension, so the host installs a fetcher here
     * and the repository asks it like any other source. The lines it returns
     * are the extension's own — Apple's timings, word by word — parsed by
     * [ExtensionLyrics].
     */
    fun interface ExtensionProvider {
        /** The lines for this track, or null when there are none. */
        suspend fun lyrics(
            title: String,
            artist: String,
            album: String?,
            durationMs: Long,
        ): List<LyricLine>?
    }

    /**
     * The fetcher the host installed, if any.
     *
     * Null when nothing can answer — no extension installed, or none that
     * declares itself a lyrics provider — and the source is then a miss
     * rather than an error, so the lookup carries on with the sources behind
     * it.
     */
    @Volatile
    var extensionProvider: ExtensionProvider? = null

    /**
     * The name of the installed extension that answers lyrics, when there is
     * one.
     *
     * "Mornye extension" was a name for a *kind* of thing, shown in the
     * sources list and beside the lyric line, while the package actually
     * answering was Apple's — and the listener was told "Mornye extension"
     * and had no way to tell which package was meant. The host publishes the
     * package's own name here, and [LyricsSource] shows that.
     *
     * Null when nothing is installed, and the source keeps its generic name.
     */
    @Volatile
    var extensionLabel: String? = null

    /**
     * What the installed extension is waiting for, when it cannot answer yet.
     *
     * Apple's package asks the listener for their MusicKit token and answers
     * every track with nothing until it has one. Nothing here fails: the
     * lookup treats it as a miss and carries on with the sources behind it,
     * which is right — but it means the player says "no lyrics found" for a
     * song Apple has the words to, and nobody is told why. This says it
     * instead: the setting that is missing, and the extension that wants it.
     */
    @Volatile
    var extensionSetup: ExtensionSetup? = null

    /**
     * An installed lyrics extension that is not ready to answer.
     *
     * @property extensionName the package's own name, e.g. "Apple Music".
     * @property settingLabel the setting it is waiting for, as its manifest
     *   labels it, e.g. "Media User Token".
     */
    data class ExtensionSetup(
        val extensionName: String,
        val settingLabel: String,
    )

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     */
    private fun result(source: LyricsSource, lines: List<LyricLine>) =
        Result(source, lines.withBackgroundVocals())

    /**
     * At most this many providers are contacted at once.
     *
     * The order can name sixteen sources, and asking every one of them
     * together meant a socket to each host opening for a single track. Three
     * keeps the race overlapping enough that a miss costs one round trip,
     * without making a dead host the only thing in flight.
     */
    private const val MAX_CONCURRENT_SOURCES = 3

    /**
     * Longest one provider has to answer.
     *
     * A lyrics panel that is still empty after the second chorus is of no use
     * to anyone, and the sources behind this one are the better answer. The
     * deadline covers the identify wait below as well as the provider's own
     * request.
     */
    private const val SOURCE_TIMEOUT_MS = 1_500L

    /**
     * Longest the lookup will wait to find out which recording this is.
     *
     * Runs inside the race and under the same cap as the sources. Short on
     * purpose: knowing the recording makes every match better, but not knowing
     * it only puts things back where they were a release ago, and a lyrics
     * panel sitting empty because one host is having a slow morning is a worse
     * failure than a fuzzy match.
     */
    private const val IDENTIFY_TIMEOUT_MS = 1_500L

    /**
     * How long an ISRC-capable source gives the identify before asking by name.
     *
     * Enough for the search — which is small — to take its head start, and
     * short enough that a slow identify still leaves most of
     * [SOURCE_TIMEOUT_MS] for the source's own request. Without an ISRC these
     * sources simply search the way they did before it existed.
     */
    private const val IDENTIFY_WAIT_MS = 500L

    /**
     * The recording and the identify hit for one source, if either is known.
     *
     * The hit is handed back so [BiniLyrics] need not search twice; every
     * other source only has a use for the ISRC. Answered without either when
     * the source that performs the identify is switched off.
     */
    private suspend fun recordingFor(
        source: LyricsSource,
        known: String?,
        identify: Deferred<BiniLyrics.Hit?>?,
    ): Pair<BiniLyrics.Hit?, String?> {
        if (known != null) return null to known
        val job = identify ?: return null to null
        val hit = when (source) {
            // This source is the identify: take its answer rather than asking
            // the same question a second time.
            LyricsSource.BINI_LYRICS -> job.await()
            // These two can match on an ISRC, so they give the identify the
            // head start documented on [IDENTIFY_WAIT_MS].
            LyricsSource.LRC_RED, LyricsSource.LYRICS_PLUS ->
                withTimeoutOrNull(IDENTIFY_WAIT_MS) { job.await() }
            else -> null
        }
        return hit to hit?.isrc?.takeIf { it.isNotBlank() }
    }

    /** The recording this lookup has settled on, if it has settled on one. */
    private fun currentRecording(
        known: String?,
        identified: AtomicReference<BiniLyrics.Hit?>,
    ): String? = known ?: identified.get()?.isrc?.takeIf { it.isNotBlank() }

    /** How many recordings to keep in hand; see [isrcs]. */
    private const val REMEMBERED = 100

    /**
     * The recording behind a video id, once something has worked it out.
     *
     * Bounded, least-recently-used, and in memory only. This is a shortcut,
     * not a store: losing it costs one fuzzy match, which is what every lookup
     * did before any of this, and keeping it on disk would mean keeping a
     * wrong answer on disk too.
     */
    private val isrcs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > REMEMBERED
        },
    )

    private fun remember(videoId: String, isrc: String?) {
        if (isrc.isNullOrBlank() || videoId.isEmpty()) return
        isrcs.put(videoId, isrc)
    }

    /** A finished answer, and when it arrived; see [lyricsByIsrc]. */
    private class CachedLyrics(val result: Result, val atNanos: Long)

    /** How many recordings' worth of finished lyrics to keep in hand. */
    private const val CACHED_RECORDINGS = 200

    /** How long a finished answer stays good. */
    private const val LYRICS_CACHE_TTL_NANOS = 24L * 60L * 60L * 1_000_000_000L

    /**
     * Finished lyrics by ISRC, kept for a day.
     *
     * An ISRC names one recording, so the words that answered for it answer
     * for every video that turns out to be the same recording — the single and
     * the album cut, or the same track found through two different rows. The
     * lookup is expensive by design (a race across several hosts), so this is
     * the one thing worth remembering across it.
     *
     * In memory only, and bounded: an answer here is a convenience, not a
     * store, and a cache that outlives the process would need invalidation it
     * cannot get from a third party.
     */
    private val lyricsByIsrc: MutableMap<String, CachedLyrics> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedLyrics>(CACHED_RECORDINGS, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedLyrics>) = size > CACHED_RECORDINGS
        },
    )

    private fun cachedLyrics(
        isrc: String?,
        sources: Set<LyricsSource>,
        prioritizeSyllableSync: Boolean,
    ): Result? {
        val key = cacheKey(isrc) ?: return null
        val cached = lyricsByIsrc[key] ?: return null
        if (System.nanoTime() - cached.atNanos >= LYRICS_CACHE_TTL_NANOS) return null
        // A source switched off since the answer was stored is still a source
        // this app must not answer from: the cache is a shortcut past the
        // network, not past the listener's choices.
        if (cached.result.source !in sources || cached.result.source.hidden) return null
        // A merely line-synced answer is not what prioritising syllable sync
        // asked for; let the race try for a word-synced one.
        if (prioritizeSyllableSync && cached.result.lines.none { it.isWordSynced }) return null
        // Only an answer the race itself would have taken at once is worth
        // returning here. Plain text with no timings is the last resort, and
        // serving it from cache would keep the next lookup from trying the
        // timed sources that are behind it.
        if (cached.result.lines.none { it.isWordSynced || it.timeMs > 0 }) return null
        if (!cached.result.isRecordingScoped()) return null
        return cached.result
    }

    private fun rememberLyrics(isrc: String?, result: Result) {
        val key = cacheKey(isrc) ?: return
        // Same rule as [cachedLyrics]: a plain fallback is not worth pinning
        // for a day, because a later lookup should still try for timings.
        if (result.lines.none { it.isWordSynced || it.timeMs > 0 }) return
        if (!result.isRecordingScoped()) return
        lyricsByIsrc[key] = CachedLyrics(result, System.nanoTime())
    }

    /**
     * Whether these words are about the recording rather than about the video.
     *
     * Caching is keyed by ISRC, which names a recording and not a video. The
     * three sources below answer for the latter — a caption track belongs to
     * one upload, and SimpMusic's index is keyed by the video id — so their
     * lines must not be handed to a different video that happens to share the
     * recording's ISRC.
     */
    private fun Result.isRecordingScoped(): Boolean = when (source) {
        LyricsSource.SIMP_MUSIC,
        LyricsSource.YOUTUBE_TRANSCRIPT,
        LyricsSource.YOUTUBE_MUSIC,
        -> false
        else -> true
    }

    /** ISRCs are compared and cached without their spacing or case. */
    private fun cacheKey(isrc: String?): String? =
        isrc?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)

    /**
     * Documents fetched during one lookup, by URL, so two sources after the
     * same file make one request between them. That is the ordinary case, not
     * an edge: [BiniLyrics]' search answers with an [LrcRed] URL, and [LrcRed],
     * handed the ISRC that search found, asks for exactly that URL too.
     *
     * Each download runs in the lookup's own scope rather than the asking
     * source's, so the source that started it losing the race doesn't take
     * the other one's copy down with it; [cancel] ends whatever is left.
     */
    private class SharedDocuments(private val scope: CoroutineScope) {
        private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

        suspend fun get(url: String): String? =
            inFlight.computeIfAbsent(url) { scope.async(Dispatchers.IO) { lyricsGet(url) } }.await()

        fun cancel() = inFlight.values.forEach { it.cancel() }
    }
}
