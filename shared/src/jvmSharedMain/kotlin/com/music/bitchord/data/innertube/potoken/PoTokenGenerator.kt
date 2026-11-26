package com.music.bitchord.data.innertube.potoken

import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * One loaded BotGuard: a browser page that has run the challenge, holds an
 * integrity token, and mints PoTokens from it until it expires or dies. The
 * phone's is an Android WebView, the desktop's a JavaFX one; both drive the
 * same [PO_TOKEN_HTML].
 */
interface PoTokenMinter {
    /** Past the integrity token's lifetime, less a margin. */
    val isExpired: Boolean

    /**
     * Wall clock when the integrity token expires, when the platform knows it.
     *
     * Null is a supported answer, not a failure: the generator then falls back
     * to [isExpired] and refreshes on demand. Implementations that do know
     * should report the real expiry rather than the already-margined
     * [isExpired] instant, so the refresh can be scheduled about five minutes
     * ahead of it.
     */
    val expiresAtMillis: Long? get() = null

    /** The page crashed, threw after start-up, or a mint timed out. */
    val isDead: Boolean

    /** A PoToken bound to [identifier] — a visitor data or a video id. */
    suspend fun generatePoToken(identifier: String): String

    /** Tears the page down, on whatever thread the browser needs. */
    fun close()
}

/**
 * BotGuard PoTokens for YouTube's web clients, minted in a browser page.
 *
 * Keeps one loaded page per session (visitor data): the session's streaming
 * token is minted once when the page is made, and each track's player token is
 * minted from the same page. A page that has expired, died or belongs to
 * another session is replaced; a mint that fails on an old page gets one retry
 * on a fresh one.
 *
 * @param createMinter loads a new BotGuard page; the platform's browser.
 * @param available whether this platform can host a page at all.
 */
class PoTokenGenerator(
    private val createMinter: suspend () -> PoTokenMinter,
    private val available: () -> Boolean = { true },
) {
    private val TAG = "PoTokenGenerator"

    private var webViewBadImpl = false // whether the platform's browser turned out to be unusable

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null

    /**
     * The live page. Volatile because the background refresh scheduler looks
     * at identity without holding the mint lock; every mutation still happens
     * under [webPoTokenGenLock].
     */
    @Volatile
    private var webPoTokenGenerator: PoTokenMinter? = null

    /**
     * A 401 asked for a fresh identity. The page in hand keeps answering until
     * a replacement is ready; the next mint rebuilds immediately if the
     * background attempt did not get there first.
     */
    @Volatile
    private var refreshRequested = false

    /**
     * The page is a browser, and a browser is not cheap: inside this process
     * it is tens of megabytes and a few dozen threads, and it is alive for as
     * long as nobody asks it for anything.
     *
     * Nothing asked it for most of the time. It is loaded because a track's
     * source was being worked out and YouTube was one of the candidates — and
     * YouTube often turns out not to have the track at all, which leaves a
     * loaded page behind a resolution that went nowhere.
     *
     * So the page is dropped once it has been idle long enough that nobody is
     * using YouTube: a few minutes of no tokens asked for, and the browser
     * goes away and takes its memory and its threads with it. The next YouTube
     * track loads a fresh one, which costs a second and a half — a second and
     * a half that is paid only when YouTube is actually wanted, instead of a
     * browser's worth of memory held for the rest of the session.
     */
    private val idleReleaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleReleaseJob: Job? = null

    /**
     * The scheduled refresh of the live page.
     *
     * One job, cancelled and rescheduled whenever a page is replaced or
     * released. It only ever replaces a page that is already expiring; the
     * page in hand keeps minting while the replacement is being prepared.
     */
    @Volatile
    private var refreshJob: Job? = null

    /** Mints in flight, so the page is never pulled out from under one. */
    private val activeMints = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        val supported = available()
        TrackLog.d(TAG, "WebView state: supported=$supported, badImpl=$webViewBadImpl")
        if (!supported || webViewBadImpl) {
            TrackLog.d(TAG, "WebView not available: supported=$supported, badImpl=$webViewBadImpl")
            return null
        }
        return try {
            withTimeout(POTOKEN_TIMEOUT_MS) {
                getWebClientPoToken(videoId, sessionId, forceRecreate = false)
            }
        } catch (e: TimeoutCancellationException) {
            TrackLog.w(TAG, "poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
            clearGenerator()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadWebViewException) {
            TrackLog.e(TAG, "Could not obtain PO token because WebView is unavailable")
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            TrackLog.e(TAG, "PO token generation failed type=${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }

    suspend fun close() {
        clearGenerator()
    }

    private suspend fun clearGenerator() {
        refreshJob?.cancel()
        refreshJob = null
        webPoTokenGenLock.withLock {
            try {
                webPoTokenGenerator?.close()
            } catch (error: Exception) {
                TrackLog.e(TAG, "PO token WebView cleanup failed type=${error::class.simpleName ?: "unknown"}")
            }
            webPoTokenGenerator = null
            webPoTokenStreamingPot = null
            webPoTokenSessionId = null
            refreshRequested = false
        }
    }

    /**
     * Drop the page when it has gone unused for [IDLE_RELEASE_MS].
     *
     * Scheduled rather than polled: one delayed task per mint, cancelled and
     * rescheduled by the next one. Nothing runs while the app is idle — the
     * task fires once, finds the page still idle, and closes it.
     */
    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = idleReleaseScope.launch {
            delay(IDLE_RELEASE_MS)
            // A mint that started after the schedule was made is left alone.
            if (activeMints.get() > 0) {
                scheduleIdleRelease()
                return@launch
            }
            val hadPage = webPoTokenGenLock.withLock { webPoTokenGenerator != null }
            if (!hadPage) return@launch
            TrackLog.d(TAG, "PO token page idle for ${IDLE_RELEASE_MS / 1000}s; releasing it")
            clearGenerator()
        }
    }

    private companion object {
        const val POTOKEN_TIMEOUT_MS = 8_000L

        /**
         * How long the page may sit unused before it is released.
         *
         * Long enough that a listener playing YouTube tracks back to back
         * never pays for a reload, short enough that a session spent on other
         * services stops carrying a browser around.
         */
        const val IDLE_RELEASE_MS = 3 * 60 * 1000L

        /**
         * Refreshed this long before the integrity token expires, when the
         * platform reports an expiry. The old page keeps minting meanwhile, so
         * this only has to cover one new page load, which is a second or two.
         */
        const val REFRESH_LEAD_MS = 5 * 60 * 1000L

        /**
         * How often to look at [PoTokenMinter.isExpired] when the platform
         * gives no expiry metadata. That flag already carries the minter's own
         * safety margin, so nothing is guessed; a minute is simply the cost of
         * noticing it.
         */
        const val REFRESH_POLL_MS = 60 * 1000L

        /** Let the request that reported a 401 finish unwinding first. */
        const val REFRESH_ON_DEMAND_DELAY_MS = 500L
    }

    /**
     * Ask for a fresh page without waiting for it.
     *
     * The caller saw a 401 and has already served the request with whatever it
     * had; this only lines up the next identity. Safe to call when no page is
     * loaded too — the next mint creates one and clears the flag.
     */
    fun refreshInBackground() {
        refreshRequested = true
        val current = webPoTokenGenerator ?: return
        refreshJob?.cancel()
        refreshJob = idleReleaseScope.launch {
            // Let the request that just failed unwind before competing with
            // whatever playback is doing; the old page is still usable.
            delay(REFRESH_ON_DEMAND_DELAY_MS)
            refreshPage(current)
        }
    }

    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val current = webPoTokenGenerator
                val shouldRecreate =
                    forceRecreate || refreshRequested || current == null || current.isExpired ||
                        current.isDead ||
                        webPoTokenSessionId != sessionId
                if (shouldRecreate) {
                    TrackLog.d(TAG, "Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
                    current?.close()
                    webPoTokenGenerator = null
                    webPoTokenStreamingPot = null
                    webPoTokenSessionId = null
                    val newGenerator = createMinter()
                    val newStreamingPot = try {
                        newGenerator.generatePoToken(sessionId)
                    } catch (t: Throwable) {
                        runCatching { newGenerator.close() }
                        throw t
                    }
                    webPoTokenGenerator = newGenerator
                    webPoTokenStreamingPot = newStreamingPot
                    webPoTokenSessionId = sessionId
                    refreshRequested = false
                    TrackLog.d(TAG, "Streaming PO token generated")
                    // A browser, loaded into this process: tens of megabytes
                    // and a few dozen threads. Counted, because it is the
                    // largest single thing the app holds that nothing is
                    // looking at.
                    com.music.bitchord.data.PerfCounters.bump(
                        com.music.bitchord.data.PerfCounters.Counter.WEBVIEW,
                    )
                }
                // Counted under the same lock that a refresh takes before it
                // swaps pages: a refresh can then see an in-flight mint and
                // leave the page alone instead of closing it underneath one.
                activeMints.incrementAndGet()
                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }
        // A new page needs its own expiry watch even when the old page's job
        // is still sleeping: that job is watching the minter that was just
        // replaced and will exit without scheduling anything for this one.
        if (hasBeenRecreated || refreshJob?.isActive != true) {
            startRefresh(poTokenGenerator)
        }
        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                TrackLog.e(TAG, "PO-token generation failed; recreating WebView")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        } finally {
            // The page has been used, so the clock on its idle life starts
            // again: it stays as long as YouTube is in use and no longer.
            if (activeMints.decrementAndGet() == 0) scheduleIdleRelease()
        }
        TrackLog.d(TAG, "PO token generated successfully")
        return PoTokenResult(
            playerRequestPoToken = streamingPot,
            streamingDataPoToken = playerPot,
        )
    }

    /**
     * Schedules the proactive replacement of [minter].
     *
     * With expiry metadata, the refresh starts [REFRESH_LEAD_MS] before the
     * token is due to expire. Without it, [PoTokenMinter.isExpired] is the only
     * safe clock and is polled at a coarse interval; nothing is guessed. Either
     * way the old page keeps serving until the replacement is fully ready.
     */
    private fun startRefresh(minter: PoTokenMinter) {
        refreshJob?.cancel()
        refreshJob = idleReleaseScope.launch {
            while (true) {
                if (webPoTokenGenerator !== minter || minter.isDead) return@launch
                val expiresAt = minter.expiresAtMillis
                if (expiresAt != null) {
                    val waitMs = expiresAt - System.currentTimeMillis() - REFRESH_LEAD_MS
                    if (waitMs > 0L) {
                        delay(waitMs)
                        continue
                    }
                    break
                }
                delay(REFRESH_POLL_MS)
                if (webPoTokenGenerator !== minter || minter.isDead) return@launch
                if (!minter.isExpired) continue
                break
            }
            refreshPage(minter)
        }
    }

    /**
     * Builds and swaps in a replacement page, leaving [old] alive until the new
     * one has its streaming token. A mint in flight keeps the old page too; the
     * on-demand path in [getWebClientPoToken] is the fallback when that happens.
     */
    private suspend fun refreshPage(old: PoTokenMinter) {
        val session = webPoTokenGenLock.withLock {
            if (webPoTokenGenerator !== old) return@withLock null
            webPoTokenSessionId
        } ?: return
        val expiresAt = old.expiresAtMillis
        val due = refreshRequested || old.isExpired ||
            (expiresAt != null && expiresAt - System.currentTimeMillis() <= REFRESH_LEAD_MS)
        if (!due) return
        if (activeMints.get() > 0) {
            TrackLog.d(TAG, "PO-token refresh deferred; a mint is in flight")
            return
        }

        val newMinter = try {
            createMinter()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TrackLog.w(TAG, "PO-token background refresh could not create a page type=${e::class.simpleName ?: "unknown"}")
            return
        }
        val newStreamingPot = try {
            newMinter.generatePoToken(session)
        } catch (e: CancellationException) {
            runCatching { newMinter.close() }
            throw e
        } catch (e: Throwable) {
            runCatching { newMinter.close() }
            TrackLog.w(TAG, "PO-token background refresh could not mint type=${e::class.simpleName ?: "unknown"}")
            return
        }

        var installed = false
        webPoTokenGenLock.withLock {
            if (webPoTokenGenerator === old &&
                webPoTokenSessionId == session &&
                activeMints.get() == 0
            ) {
                webPoTokenGenerator = newMinter
                webPoTokenStreamingPot = newStreamingPot
                webPoTokenSessionId = session
                refreshRequested = false
                installed = true
            }
        }
        if (installed) {
            runCatching { old.close() }
            com.music.bitchord.data.PerfCounters.bump(
                com.music.bitchord.data.PerfCounters.Counter.WEBVIEW,
            )
            TrackLog.d(TAG, "PO-token page refreshed in the background")
            startRefresh(newMinter)
        } else {
            runCatching { newMinter.close() }
        }
    }

    /** This generator as InnerTubeX's token provider, for WEB_REMIX and the other BotGuard clients. */
    fun asTokenProvider(): TokenProvider {
        // A 401 from a stream minted with this provider must line up a fresh
        // page without blocking the player. Binding here covers Android and
        // desktop in one place; the resolver only stores a lambda.
        com.music.bitchord.data.innertube.InnerTubeXResolver.poTokenRefresher =
            ::refreshInBackground
        return object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(
                providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                usesWebView = true,
            )

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): com.metrolist.innertubex.extraction.PoTokenResult? =
                getWebClientPoToken(videoId, visitorData)?.let { token ->
                    com.metrolist.innertubex.extraction.PoTokenResult(
                        playerRequestToken = token.playerRequestPoToken,
                        streamingDataToken = token.streamingDataPoToken,
                        visitorData = visitorData,
                    )
                }

            override suspend fun close() {
                this@PoTokenGenerator.close()
            }
        }
    }
}
