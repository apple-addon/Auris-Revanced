package com.music.bitchord.playback

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from outside the app to open the full player, or to warm a live
 * station before it is drawn.
 *
 * Tapping a widget's artwork should land on the now-playing sheet, and there is
 * nothing to hand that intent to: whether the sheet is up is `showNowPlaying`, a
 * `remember`ed boolean inside `BitChordApp`, which only exists once the
 * composition is running and which
 * [MainActivity][com.music.bitchord.MainActivity] cannot reach. Because the
 * activity is `singleTask`, the intent also arrives down two different paths
 * depending on whether the app happened to be alive — `onCreate` for a cold
 * launch, `onNewIntent` for every other time — so both relay through here and
 * the composition watches this.
 */
object PlayerDeepLink {

    /** Set on the widget's artwork intent. Nothing else sets it. */
    const val EXTRA_OPEN_PLAYER = "bitchord.openPlayer"

    /**
     * A live station's playlist, when the intent is a direct link into radio.
     *
     * Not set by the current widget: it exists so a notification, widget or
     * deep link that names a station can hand over the exact stream it wants
     * before the composition exists. See [pendingLive].
     */
    const val EXTRA_LIVE_STREAM = "bitchord.liveStream"

    /** The matching license endpoint, when it is not the addon's derived one. */
    const val EXTRA_LIVE_LICENSE = "bitchord.liveLicense"

    /**
     * A live stream a direct launch asked to warm, before anything was drawn.
     *
     * The stream is read off the intent rather than resolved in the
     * composition, which is the whole point: a cold start into radio can hand
     * the service the playlist and license address while the first frame is
     * still being drawn, instead of waiting for a view model, a home feed and
     * a station lookup before the player is even asked to prepare. Null when
     * the launch did not name a live station.
     */
    data class LiveStationRequest(
        val streamUrl: String,
        val licenseUrl: String? = null,
    )

    private val _pending = MutableStateFlow(false)

    /** Whether a request is outstanding. Cleared by [handled]. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    private val _pendingLive = MutableStateFlow<LiveStationRequest?>(null)

    /** A live station a launch asked to prewarm, or null. Cleared by [liveHandled]. */
    val pendingLive: StateFlow<LiveStationRequest?> = _pendingLive.asStateFlow()

    /** Reads an incoming intent, and reports whether it asked for playback. */
    fun consume(intent: Intent?): Boolean {
        if (intent == null) return false
        val openPlayer = intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)
        val stream = intent.getStringExtra(EXTRA_LIVE_STREAM)?.takeIf { it.isNotBlank() }
        val license = intent.getStringExtra(EXTRA_LIVE_LICENSE)?.takeIf { it.isNotBlank() }
        if (!openPlayer && stream == null) return false
        // Cleared off the intent as well. A singleTask activity keeps the intent
        // that launched it, and getIntent() returns the same one after every
        // configuration change — left in place, a widget tap would reopen the
        // sheet each time the device changed theme or font size.
        intent.removeExtra(EXTRA_OPEN_PLAYER)
        intent.removeExtra(EXTRA_LIVE_STREAM)
        intent.removeExtra(EXTRA_LIVE_LICENSE)
        if (stream != null) {
            _pendingLive.value = LiveStationRequest(
                streamUrl = stream,
                licenseUrl = license,
            )
        } else {
            // A new launch that names no station must not leave an older
            // station's warm-up standing; it would prewarm a stream this tap
            // did not ask for.
            _pendingLive.value = null
        }
        if (openPlayer || stream != null) _pending.value = true
        return true
    }

    /**
     * Called once the player has actually been opened.
     *
     * The flag has to be cleared by whoever acts on it, not by whoever set it:
     * this object outlives the composition, so a request left standing would be
     * served again by the next composition — which is to say, the sheet would
     * spring back open the first time the activity was recreated after the user
     * dismissed it.
     */
    fun handled() {
        _pending.value = false
    }

    /** Called once the live stream has been handed to the player. */
    fun liveHandled() {
        _pendingLive.value = null
    }
}
