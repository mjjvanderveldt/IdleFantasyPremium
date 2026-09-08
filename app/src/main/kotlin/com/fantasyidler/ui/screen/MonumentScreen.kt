package com.fantasyidler.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fantasyidler.BuildConfig
import com.fantasyidler.R
import com.fantasyidler.repository.MonumentRepository
import com.fantasyidler.ui.viewmodel.MonumentViewModel
import com.fantasyidler.util.formatCoins

private val STAGE_EMOJI = listOf("🪨", "🧱", "🏛️", "🗿", "✨", "🔥")

private val STAGE_NAME_RES = listOf(
    R.string.monument_stage_1_name, R.string.monument_stage_2_name, R.string.monument_stage_3_name,
    R.string.monument_stage_4_name, R.string.monument_stage_5_name,
)

private val STAGE_REWARD_RES = listOf(
    R.string.monument_stage_1_reward, R.string.monument_stage_2_reward, R.string.monument_stage_3_reward,
    R.string.monument_stage_4_reward, R.string.monument_stage_5_reward,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonumentScreen(
    viewModel: MonumentViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    AppBannerEffect(state.snackbarMessage, viewModel::snackbarConsumed)

    // Confirmation for the "All" quick-feed button only: it drains the entire coin balance
    // in one tap (discussion #1352), while the fixed +1M/+10M/+100M buttons are bounded.
    var showFeedAllConfirm by remember { mutableStateOf(false) }
    if (showFeedAllConfirm) {
        AlertDialog(
            onDismissRequest = { showFeedAllConfirm = false },
            title = { Text(stringResource(R.string.monument_feed_all_confirm_title)) },
            text  = { Text(stringResource(R.string.monument_feed_all_confirm_message, state.coins.formatCoins())) },
            confirmButton = {
                TextButton(onClick = {
                    showFeedAllConfirm = false
                    viewModel.contribute(state.coins)
                }) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showFeedAllConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.monument_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Hero: current construction state ─────────────────────────
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(STAGE_EMOJI[state.tier], style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text       = if (state.tier == 0) stringResource(R.string.monument_stage_0_name)
                                     else stringResource(STAGE_NAME_RES[state.tier - 1]),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text      = stringResource(R.string.monument_desc),
                        style     = MaterialTheme.typography.bodySmall,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text  = stringResource(R.string.monument_your_coins, state.coins.formatCoins()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ── Daily touch (stage 2+) ────────────────────────────────────
            if (state.tier >= 2) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text       = stringResource(R.string.monument_touch_title),
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (state.touchedToday) {
                            Text(
                                text  = stringResource(R.string.monument_touched_today),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (BuildConfig.DEBUG) {
                                TextButton(onClick = viewModel::debugForceTouchMonument) {
                                    Text("[Debug] Touch the monument anyway")
                                }
                            }
                        } else {
                            Button(onClick = viewModel::touchMonument, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.monument_touch_btn))
                            }
                        }
                    }
                }
            }

            // ── Stages 1-4 ────────────────────────────────────────────────
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    MonumentRepository.STAGE_COSTS.forEachIndexed { index, cost ->
                        val stage = index + 1
                        if (index > 0) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text       = "${STAGE_EMOJI[stage]} ${stringResource(STAGE_NAME_RES[index])}",
                                    style      = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text  = stringResource(STAGE_REWARD_RES[index]),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            when {
                                state.tier >= stage -> Text(
                                    text       = stringResource(R.string.monument_built),
                                    style      = MaterialTheme.typography.labelMedium,
                                    color      = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                                state.tier == stage - 1 -> Button(
                                    onClick = viewModel::purchaseNextStage,
                                    enabled = state.coins >= cost,
                                ) {
                                    Text(stringResource(R.string.monument_build_btn, cost.formatCoins()))
                                }
                                else -> Text("🔒", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }

            // ── Stage 5: the Eternal Flame fund ───────────────────────────
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text       = "${STAGE_EMOJI[5]} ${stringResource(R.string.monument_stage_5_name)}",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text  = stringResource(R.string.monument_stage_5_reward),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.tier >= 5) {
                        Text(
                            text       = stringResource(R.string.monument_flame_complete),
                            style      = MaterialTheme.typography.bodyMedium,
                            color      = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        LinearProgressIndicator(
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                            progress = { (state.fund.toFloat() / MonumentRepository.FLAME_GOAL).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color    = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text  = "${state.fund.formatCoins()} / ${MonumentRepository.FLAME_GOAL.formatCoins()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.tier == 4) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text  = stringResource(R.string.monument_contribute_label),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1_000_000L, 10_000_000L, 100_000_000L).forEach { amount ->
                                    OutlinedButton(
                                        onClick  = { viewModel.contribute(amount) },
                                        enabled  = state.coins >= amount,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) {
                                        Text("+${amount / 1_000_000}M", maxLines = 1, softWrap = false)
                                    }
                                }
                                OutlinedButton(
                                    onClick  = { showFeedAllConfirm = true },
                                    enabled  = state.coins > 0,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                ) {
                                    Text(stringResource(R.string.monument_contribute_all), maxLines = 1, softWrap = false)
                                }
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text  = stringResource(R.string.monument_flame_locked),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
