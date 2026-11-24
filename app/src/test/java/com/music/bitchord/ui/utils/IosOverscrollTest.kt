package com.music.bitchord.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IosOverscrollTest {
    @Test
    fun topPullIsReducedWithoutChangingSign() {
        val top = overscrollTranslation(240f, 2_000f)
        val bottom = overscrollTranslation(-240f, 2_000f)
        assertTrue("top pull must stay downward", top > 0f)
        assertTrue("bottom pull must stay upward", bottom < 0f)
        assertTrue("top gap must stay smaller than the foot", top < -bottom)
    }

    @Test
    fun bottomPullKeepsExistingRubberBand() {
        assertEquals(-overscrollTranslation(180f, 1_800f) / 0.55f, overscrollTranslation(-180f, 1_800f), 0.001f)
    }

    @Test
    fun noPullIsNoOffset() {
        assertEquals(0f, overscrollTranslation(0f, 2_000f), 0f)
    }

    @Test
    fun reverseTouchReleasesTopBounceImmediately() {
        assertTrue(releasesOverscrollBounce(current = 48f, delta = -3f))
    }

    @Test
    fun reverseTouchReleasesBottomBounceImmediately() {
        assertTrue(releasesOverscrollBounce(current = -48f, delta = 3f))
    }

    @Test
    fun sameDirectionAndZeroDoNotReleaseBounce() {
        assertTrue(!releasesOverscrollBounce(current = 48f, delta = 3f))
        assertTrue(!releasesOverscrollBounce(current = -48f, delta = -3f))
        assertTrue(!releasesOverscrollBounce(current = 0f, delta = 3f))
        assertTrue(!releasesOverscrollBounce(current = 48f, delta = 0f))
    }

    @Test
    fun wideShortRowIsHorizontal() {
        assertTrue(
            isHorizontalScrollContainer(
                placeableWidth = 1_080,
                placeableHeight = 420,
                maxWidth = 1_080,
                maxHeight = 2_200,
                widthBounded = true,
                heightBounded = true,
            ),
        )
    }

    @Test
    fun pageHeightListIsNotHorizontal() {
        assertTrue(
            !isHorizontalScrollContainer(
                placeableWidth = 1_080,
                placeableHeight = 2_200,
                maxWidth = 1_080,
                maxHeight = 2_200,
                widthBounded = true,
                heightBounded = true,
            ),
        )
    }

    @Test
    fun unboundedHeightInsideLazyColumnIsHorizontal() {
        assertTrue(
            isHorizontalScrollContainer(
                placeableWidth = 1_080,
                placeableHeight = 420,
                maxWidth = 1_080,
                maxHeight = 0,
                widthBounded = true,
                heightBounded = false,
            ),
        )
    }
}
