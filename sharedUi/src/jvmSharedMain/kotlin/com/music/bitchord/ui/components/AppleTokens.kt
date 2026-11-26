package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The measurements Apple Music's own app is built from.
 *
 * Every value here was read out of Apple's APK rather than guessed:
 * `res/values/dimens.xml` in the decoded resources, under the names in the
 * comments. They are kept in one place because the same numbers recur across
 * the Home, New, Radio, Library and Search pages, and three screens drifting
 * apart by a dp is exactly how an app stops looking like the one it copies.
 */
object AppleTokens {
    /** `dynamic_grid_item_spacing_gutter` — the gap between grid cells. */
    val GridGutter = 10.dp

    /** `dynamic_grid_peek` — how much of the next cell shows at the edge. */
    val GridPeek = 36.dp

    /** `grid_bottom_padding` — the breathing room under a grid. */
    val GridBottomPadding = 12.dp

    /** `corner_radius_s` — artwork tiles. */
    val CornerSmall = 5.dp

    /** `corner_radius_m` — cards that hold text on top of art. */
    val CornerMedium = 7.dp

    /** `corner_radius_l` — sheets and large surfaces. */
    val CornerLarge = 10.dp

    /** `corner_radius_xl` — hero art. */
    val CornerHero = 14.dp

    /** `radio_live_tile_corner_radius` — live station tiles. */
    val LiveTileRadius = 20.dp

    /**
     * `search_landing_title_item_aspect_ratio` — the genre wall's tile.
     *
     * 1.777, i.e. 16:9, which is wider than the 1.9 the wall was drawn at.
     */
    const val SearchTileAspect = 1.777f

    /** `Headline` — the size Apple labels a tile with. */
    val TileLabelSize = 17.sp

    /** `search_filter_height` — the filter pills above results. */
    val SearchFilterHeight = 42.dp

    /** `search_filter_margin_bottom`. */
    val SearchFilterGap = 6.dp
}
