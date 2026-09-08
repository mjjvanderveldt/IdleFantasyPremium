package com.fantasyidler.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FantasyIdlerTheme(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = AppTypography,
    ) {
        // No overscroll stretch: while its animation runs, scrollables treat the next tap
        // as tap-to-stop and swallow it, eating first taps at list edges (issue #1725).
        CompositionLocalProvider(LocalOverscrollConfiguration provides null, content = content)
    }
}
