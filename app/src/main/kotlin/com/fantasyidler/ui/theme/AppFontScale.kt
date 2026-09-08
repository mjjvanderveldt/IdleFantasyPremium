package com.fantasyidler.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity

/**
 * Carries the user's in-app Text Size preference so it can be re-applied inside dialogs and
 * bottom sheets. Those open their own Android window and derive a fresh LocalDensity from it,
 * so the LocalDensity override MainActivity applies to the rest of the composition doesn't
 * reach them (issue #1128) -- but a plain CompositionLocal like this one does.
 */
val LocalAppFontScale = compositionLocalOf { 1f }

/**
 * Blocks a scrollable sheet's leftover scroll from reaching the sheet's own drag-to-dismiss
 * handling only in the exact frame where a drag crosses a list boundary (issue #1123 -- a fast
 * scroll overshooting the top/bottom shouldn't close the sheet in that same frame). Every frame
 * after that -- still the same continuous swipe -- has nothing left for the list to consume, so
 * it's let through immediately, letting one uninterrupted swipe flow from "scroll to the top"
 * straight into "dismiss" (issue #1174), rather than requiring a second, separate swipe.
 *
 * Upward leftovers (scroll and fling) are swallowed entirely: a fully expanded sheet can't
 * move up, so all they do is feed Material3's drag-state settle, which can wedge and lock the
 * sheet's lists until it is reopened (issue #1365). Downward leftovers still flow to the
 * sheet so swipe-down-to-dismiss keeps working.
 */
@Composable
private fun rememberSheetSwipeConnection(): NestedScrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset = if (consumed.y != 0f || available.y < 0f) available else Offset.Zero

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (available.y < 0f) available else Velocity.Zero
    }
}

/** Wraps a dialog/bottom sheet's content so it honours the app's Text Size setting. */
@Composable
fun ScaledSheetContent(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, LocalAppFontScale.current)) {
        Box(Modifier.nestedScroll(rememberSheetSwipeConnection())) {
            content()
        }
    }
}
