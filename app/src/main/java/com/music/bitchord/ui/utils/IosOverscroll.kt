/*
 * Adapted from Convx's IosOverscroll.kt (GPL-3.0):
 * https://github.com/cosmictaserdev-creator/Convx
 */
package com.music.bitchord.ui.utils

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private const val rubberBandConstant = 0.55f
/** Top overscroll sits under the header inset; keep it visibly lighter than the foot. */
private const val topOverscrollResponse = 0.55f
private const val fallbackContainerPx = 2_000f
private const val maxBounceVelocity = 10_000f
private const val highVelocityThreshold = 5_000f
private const val normalBounceStiffness = 247f
private const val fastBounceStiffness = 130f

/**
 * Converts raw pull distance to UIKit's self-limiting rubber-band curve.
 */
private fun rubberBand(rawDistance: Float, containerPx: Float): Float {
    val dimension = containerPx.takeIf { it > 0f } ?: fallbackContainerPx
    val distance = abs(rawDistance)
    val banded = (1f - 1f / (distance * rubberBandConstant / dimension + 1f)) *
        dimension / rubberBandConstant
    return banded * sign(rawDistance)
}

/** Direction-aware band; top pull is reduced without touching the bottom pull. */
internal fun overscrollTranslation(rawDistance: Float, containerPx: Float): Float {
    val banded = rubberBand(rawDistance, containerPx)
    return if (rawDistance > 0f) banded * topOverscrollResponse else banded
}

/** Reverse touch wins immediately over an in-flight or held bounce. */
internal fun releasesOverscrollBounce(current: Float, delta: Float): Boolean =
    current != 0f && delta != 0f && sign(current) != sign(delta)

/** A wide, shorter-than-viewport scroll container is a horizontal row. */
internal fun isHorizontalScrollContainer(
    placeableWidth: Int,
    placeableHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    widthBounded: Boolean,
    heightBounded: Boolean,
): Boolean {
    // Horizontal rows are measured with unbounded height inside a LazyColumn.
    if (!heightBounded) return true
    val fillsWidth = !widthBounded || placeableWidth >= maxWidth - 1
    val fillsHeight = placeableHeight >= maxHeight - 1
    return fillsWidth && !fillsHeight
}

/**
 * An iOS-style overscroll effect for Compose scroll containers.
 *
 * It replaces Android's stretch/glow with a UIScrollView-like rubber band during a
 * drag, then critically damps the content back to its resting position on release.
 */
private class IosOverscrollEffect(private val scope: CoroutineScope) : OverscrollEffect {
    private val rawPullX = mutableFloatStateOf(0f)
    private val rawPullY = mutableFloatStateOf(0f)
    private var settleXJob: Job? = null
    private var settleYJob: Job? = null
    private var containerWidthPx = 0f
    private var containerHeightPx = 0f
    /** Horizontal shelves must never hold a vertical leftover from the page. */
    private var horizontalOnly = false

    override val isInProgress: Boolean
        get() = rawPullX.floatValue != 0f || rawPullY.floatValue != 0f

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        // A horizontal shelf has no vertical range. Never keep Y here: it is
        // the parent LazyColumn's gesture, not this row's bounce.
        if (horizontalOnly) rawPullY.floatValue = 0f
        // Opposite touch is a navigation command, not payment for the bounce:
        // cancel the animation and hand the full delta to the list immediately.
        if (source == NestedScrollSource.UserInput) {
            if (releasesOverscrollBounce(rawPullX.floatValue, delta.x)) {
                settleXJob?.cancel()
                settleXJob = null
                rawPullX.floatValue = 0f
            }
            if (!horizontalOnly && releasesOverscrollBounce(rawPullY.floatValue, delta.y)) {
                settleYJob?.cancel()
                settleYJob = null
                rawPullY.floatValue = 0f
            }
        }
        val paidX = payDown(rawPullX, delta.x)
        val paidY = if (horizontalOnly) 0f else payDown(rawPullY, delta.y)
        val remaining = Offset(delta.x - paidX, delta.y - paidY)
        val consumed = performScroll(remaining)
        val leftover = remaining - consumed

        // Absorb only touch deltas. Fling leftovers must be returned so Compose
        // cancels its decay and gives their velocity to applyToFling below.
        var stretchedX = 0f
        var stretchedY = 0f
        if (source == NestedScrollSource.UserInput) {
            if (leftover.x != 0f) {
                rawPullX.floatValue += leftover.x
                stretchedX = leftover.x
            }
            if (!horizontalOnly && leftover.y != 0f) {
                rawPullY.floatValue += leftover.y
                stretchedY = leftover.y
            }
        }
        return Offset(consumed.x + paidX + stretchedX, consumed.y + paidY + stretchedY)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val consumed = performFling(velocity)
        val leftover = velocity - consumed
        if (!isInProgress && leftover == Velocity.Zero) return

        coroutineScope {
            val x = launch { settle(rawPullX, leftover.x) }
            settleXJob = x
            val y = if (!horizontalOnly) launch { settle(rawPullY, leftover.y) } else null
            settleYJob = y
            try {
                x.join()
                y?.join()
            } finally {
                if (settleXJob === x) settleXJob = null
                if (settleYJob === y) settleYJob = null
            }
        }
    }

    private fun payDown(pull: MutableFloatState, delta: Float): Float {
        val current = pull.floatValue
        if (current == 0f || delta == 0f || sign(current) == sign(delta)) return 0f
        val target = current + delta
        val next = if (sign(target) != sign(current)) 0f else target
        pull.floatValue = next
        return next - current
    }

    private suspend fun settle(pull: MutableFloatState, leftoverVelocity: Float) {
        if (pull.floatValue == 0f && leftoverVelocity == 0f) return
        val velocity = leftoverVelocity.coerceIn(-maxBounceVelocity, maxBounceVelocity)
        animate(
            initialValue = pull.floatValue,
            targetValue = 0f,
            initialVelocity = velocity,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = if (abs(velocity) > highVelocityThreshold) {
                    fastBounceStiffness
                } else {
                    normalBounceStiffness
                },
            ),
        ) { value, _ -> pull.floatValue = value }
    }

    override val node: DelegatableNode = IosOverscrollNode(
        offsetX = { rubberBand(rawPullX.floatValue, containerWidthPx) },
        offsetY = { overscrollTranslation(rawPullY.floatValue, containerHeightPx) },
        onMeasured = { width, height, horizontal ->
            containerWidthPx = width
            containerHeightPx = height
            horizontalOnly = horizontal
        },
    )
}

private class IosOverscrollNode(
    private val offsetX: () -> Float,
    private val offsetY: () -> Float,
    private val onMeasured: (Float, Float, Boolean) -> Unit,
) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val width = (if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width).toFloat()
        val height = (if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height).toFloat()
        onMeasured(
            width,
            height,
            isHorizontalScrollContainer(
                placeableWidth = placeable.width,
                placeableHeight = placeable.height,
                maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width,
                maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height,
                widthBounded = constraints.hasBoundedWidth,
                heightBounded = constraints.hasBoundedHeight,
            ),
        )
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                translationX = offsetX()
                translationY = offsetY()
            }
        }
    }
}

private class IosOverscrollFactory(
    private val density: Density,
    private val scope: CoroutineScope,
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = IosOverscrollEffect(scope)

    override fun equals(other: Any?): Boolean =
        other is IosOverscrollFactory && other.density == density && other.scope === scope

    override fun hashCode(): Int = 31 * density.hashCode() + scope.hashCode()
}

/** Provides iOS-style rubber-band overscroll to every descendant scroll container. */
@Composable
fun rememberIosOverscrollFactory(): OverscrollFactory {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(density, scope) { IosOverscrollFactory(density, scope) }
}
