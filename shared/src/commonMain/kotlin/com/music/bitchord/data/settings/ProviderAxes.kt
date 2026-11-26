package com.music.bitchord.data.settings

/**
 * The three provider axes as they are stored.
 *
 * A small value class rather than three separate preferences, because the
 * three are written together by the master switch and read together by the
 * screens: a half-written set of axes would be a state no listener asked for.
 */
data class ProviderAxes(
    /** The catalogue look: the skin the screens wear. */
    val ui: MusicProvider = MusicProvider.YOUTUBE,
    /** The discovery algorithm: what Home, Flow and Autoplay suggest. */
    val recommendation: MusicProvider = MusicProvider.YOUTUBE,
    /** The content source: songs, metadata, lyrics and artwork. */
    val source: MusicProvider = MusicProvider.YOUTUBE,
) {
    /** The provider on [axis]. */
    fun on(axis: ProviderAxis): MusicProvider =
        when (axis) {
            ProviderAxis.UI -> ui
            ProviderAxis.RECOMMENDATION -> recommendation
            ProviderAxis.SOURCE -> source
        }

    /** This, with [axis] pointing at [provider]. */
    fun with(axis: ProviderAxis, provider: MusicProvider): ProviderAxes =
        when (axis) {
            ProviderAxis.UI -> copy(ui = provider)
            ProviderAxis.RECOMMENDATION -> copy(recommendation = provider)
            ProviderAxis.SOURCE -> copy(source = provider)
        }

    /** Whether every axis points at Apple Music. */
    val allApple: Boolean
        get() = ui == MusicProvider.APPLE &&
            recommendation == MusicProvider.APPLE &&
            source == MusicProvider.APPLE
}
