package com.fantasyidler.ui.screen.skills

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Default spline fling, cut short once it slows below [MIN_FLING_VELOCITY_DP_PER_SEC].
 * While a fling animation runs, a tap on the scrollable is consumed as tap-to-stop
 * instead of being delivered as a click, and the spline's final stretch glides almost
 * imperceptibly for a second or more — so taps shortly after a scroll were silently
 * eaten (issue #1761). Ending the fling at the perceptibility floor closes that window.
 *
 * A fling cancelled at a list edge still reports its leftover velocity so it keeps
 * flowing into the sheet swipe handling (issues #1174, #1365); a fling cut at the
 * floor reports none, so the residue can't start an invisible sheet settle animation
 * that would capture the next tap as a drag.
 */
private const val MIN_FLING_VELOCITY_DP_PER_SEC = 125

@Composable
fun rememberTapFriendlyFlingBehavior(): FlingBehavior {
    val decay = rememberSplineBasedDecay<Float>()
    val density = LocalDensity.current
    return remember(decay, density) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                if (abs(initialVelocity) <= 1f) return initialVelocity
                val minVelocityPx = with(density) { MIN_FLING_VELOCITY_DP_PER_SEC.dp.toPx() }
                var velocityLeft = initialVelocity
                var lastValue = 0f
                var cutAtFloor = false
                AnimationState(initialValue = 0f, initialVelocity = initialVelocity)
                    .animateDecay(decay) {
                        val delta = value - lastValue
                        val consumed = scrollBy(delta)
                        lastValue = value
                        velocityLeft = this.velocity
                        if (abs(delta - consumed) > 0.5f) {
                            cancelAnimation()
                        } else if (abs(velocityLeft) < minVelocityPx) {
                            cutAtFloor = true
                            cancelAnimation()
                        }
                    }
                return if (cutAtFloor) 0f else velocityLeft
            }
        }
    }
}
