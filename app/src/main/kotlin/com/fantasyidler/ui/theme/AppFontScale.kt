package com.fantasyidler.ui.theme

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Carries the user's in-app Text Size preference so it can be re-applied inside dialogs and
 * bottom sheets. Those open their own Android window and derive a fresh LocalDensity from it,
 * so the LocalDensity override MainActivity applies to the rest of the composition doesn't
 * reach them (issue #1128) -- but a plain CompositionLocal like this one does.
 */
val LocalAppFontScale = compositionLocalOf { 1f }

/**
 * Blocks a scrollable sheet's content gestures from reaching Material3's own drag handling,
 * whose two failure modes ate the first tap after any scroll for its full 300ms tween --
 * the sheet settling back after following a drag, and, worse, the settle Material3 runs after
 * EVERY content fling: even at rest exactly on its anchor it animates a zero-distance tween,
 * and while any sheet animation runs the drag modifier consumes every press
 * (startDragImmediately) before content ever sees it (issues #1123, #1365, #1725, #1761,
 * #1791).
 *
 * All leftover deltas and fling velocity are therefore consumed here. With [sheetState] the
 * connection then takes over the sheet's gesture duties itself:
 * - after every content fling, the zero-distance zombie settle is cancelled by seizing the
 *   drag mutex through the public expand() and releasing it a frame later, so taps land
 *   again within a few frames;
 * - a decisive downward fling closes the sheet via hide() + [onSwipeDismissed], keeping
 *   fling-to-close from a list working (issue #1174). Material3's own settle can't do this
 *   anymore: with the sheet pinned to its anchor it targets the current value regardless of
 *   velocity.
 *
 * The drag handle, scrim tap, and back button are unaffected. Without a [sheetState] the
 * zombie settle still runs, so pass one for any sheet whose content is scrollable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberSheetSwipeConnection(
    sheetState: SheetState?,
    onSwipeDismissed: (() -> Unit)?,
): NestedScrollConnection {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(density, scope, sheetState, onSwipeDismissed) {
        val dismissVelocityPx = with(density) { 125.dp.toPx() }
        object : NestedScrollConnection {
            // Drag deltas are consumed so the sheet never follows the finger. Fling-phase
            // deltas (SideEffect) must pass untouched: consuming them makes the inner list's
            // fling believe it is scrolling and decay to nothing instead of edge-cancelling,
            // so no leftover velocity would ever reach onPostFling below. Material3's own
            // connection ignores non-UserInput sources, so the sheet still cannot move.
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = if (source == NestedScrollSource.UserInput) available else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val velocity = available.y
                if (sheetState != null) scope.launch {
                    // Two frames so Material3's own post-fling settle has started; acting on
                    // the drag mutex sooner would lose it to that settle instead.
                    withFrameNanos { }
                    withFrameNanos { }
                    if (velocity >= dismissVelocityPx) {
                        runCatching { sheetState.hide() }
                        if (!sheetState.isVisible) onSwipeDismissed?.invoke()
                    } else if (sheetState.currentValue == sheetState.targetValue) {
                        val grab = launch { runCatching { sheetState.expand() } }
                        withFrameNanos { }
                        grab.cancel()
                    }
                }
                return available
            }
        }
    }
}

/** Wraps a dialog/bottom sheet's content so it honours the app's Text Size setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaledSheetContent(
    sheetState: SheetState? = null,
    onSwipeDismissed: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, LocalAppFontScale.current)) {
        // The zero-consuming scrollable claims vertical drags on the sheet's NON-scrollable
        // areas (headers, banners, sheets too short to scroll) and routes them through the
        // connection above. Without it those drags fall through to the sheet surface's own
        // drag modifier, whose settle-back after release eats the next tap (issue #1791) --
        // a path no nested-scroll connection can intercept. Inner lists still win drags over
        // their own area, and taps pass through untouched (drags only engage past touch slop).
        Box(
            Modifier
                .nestedScroll(rememberSheetSwipeConnection(sheetState, onSwipeDismissed))
                .scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
        ) {
            content()
        }
    }
}
