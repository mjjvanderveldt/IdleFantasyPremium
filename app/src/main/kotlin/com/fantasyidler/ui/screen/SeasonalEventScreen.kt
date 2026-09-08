package com.fantasyidler.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fantasyidler.BuildConfig
import com.fantasyidler.R
import com.fantasyidler.data.json.NightMarketOfferData
import com.fantasyidler.data.json.SeasonalMinigameConfig
import com.fantasyidler.data.json.SeasonalRewardTierData
import com.fantasyidler.repository.SeasonalBountyTaskWithProgress
import com.fantasyidler.repository.SeasonalEventRepository
import com.fantasyidler.ui.viewmodel.CraftingViewModel
import com.fantasyidler.ui.viewmodel.SeasonalEventViewModel
import com.fantasyidler.ui.viewmodel.SkillsViewModel
import com.fantasyidler.util.GameStrings
import com.fantasyidler.util.formatCoins
import com.fantasyidler.util.formatDurationMs
import kotlin.random.Random
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeasonalEventScreen(
    viewModel: SeasonalEventViewModel = hiltViewModel(),
    skillsViewModel: SkillsViewModel = hiltViewModel(),
    craftingViewModel: CraftingViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onNavigateToExpedition: (String) -> Unit = {},
    onNavigateToBoss: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    AppBannerEffect(state.snackbarMessage, viewModel::snackbarConsumed)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = {
                    val event = state.event
                    Text(
                        if (event != null) GameStrings.seasonalEventName(context, event.id, event.displayName)
                        else stringResource(R.string.seasonal_event_title)
                    )
                },
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
        val event = state.event
        if (event == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text  = stringResource(R.string.seasonal_event_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text  = stringResource(R.string.seasonal_event_token_progress, state.tokens, event.tokenGoal),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        progress = { (state.tokens.toFloat() / event.tokenGoal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }

            if (event.rewardTiers.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.seasonal_rewards_title)) {
                    val tiers = event.rewardTiers.sortedBy { it.tokens }
                    tiers.forEachIndexed { index, tier ->
                        RewardTierRow(
                            tier    = tier,
                            eventId = event.id,
                            tokens  = state.tokens,
                            claimed = tier.tokens in state.claimedRewardTiers,
                            onClaim = { viewModel.claimRewardTier(tier.tokens) },
                        )
                        if (index != tiers.lastIndex) HorizontalDivider()
                    }
                }
            }

            if ("bounty" in event.pillars) {
                SectionCard(title = stringResource(R.string.seasonal_bounty_board_title)) {
                    state.bountyTasks.forEachIndexed { index, taskProgress ->
                        BountyTaskRow(
                            taskProgress       = taskProgress,
                            onClaim            = { viewModel.claimBountyTask(taskProgress.task.id) },
                            onReroll           = { viewModel.rerollBountyTask(taskProgress.task.id) },
                            onGo               = {
                                if (taskProgress.task.type == "kill") viewModel.expeditionKeyForKillTarget(event, taskProgress.task.target)?.let(onNavigateToExpedition)
                                else taskProgress.task.skill?.let(skillsViewModel::onSkillTapped)
                            },
                            onCooldownExpired  = viewModel::refreshBountySlots,
                        )
                        if (index != state.bountyTasks.lastIndex) HorizontalDivider()
                    }
                }
            }

            val expeditionKeys = event.expeditionKeys()
            if ("expedition" in event.pillars && expeditionKeys.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.label_dungeon)) {
                    Text(
                        text  = stringResource(R.string.seasonal_expedition_token_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    expeditionKeys.forEachIndexed { index, dungeonKey ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        Text(GameStrings.dungeonName(context, dungeonKey), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onNavigateToExpedition(dungeonKey) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.seasonal_go_to_combat))
                        }
                    }
                }
            }

            if ("boss" in event.pillars && event.bossKey != null) {
                SectionCard(title = stringResource(R.string.label_boss)) {
                    Text(GameStrings.bossName(context, event.bossKey), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text  = stringResource(R.string.seasonal_boss_token_hint, SeasonalEventRepository.BOSS_TOKENS_PER_DAY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onNavigateToBoss(event.bossKey) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.seasonal_go_to_combat))
                    }
                }
            }

            val minigame = event.minigame
            if ("minigame" in event.pillars && minigame != null) {
                SectionCard(title = GameStrings.seasonalMinigameName(context, minigame.id, minigame.displayName)) {
                    MinigameModeSelector(
                        easyMode      = state.minigameEasyMode,
                        onModeChange  = viewModel::setMinigameEasyMode,
                        visibleMs     = if (state.minigameEasyMode) minigame.visibleMsEasy else minigame.visibleMs,
                        cooldownMs    = if (state.minigameEasyMode) minigame.cooldownMsEasy else minigame.cooldownMs,
                        sequenceMode  = minigame.type == "sequence",
                    )
                    Spacer(Modifier.height(8.dp))
                    val now = System.currentTimeMillis()
                    if (state.minigameCooldownAt > now) {
                        MinigameCooldownRow(
                            resumesAtMs   = state.minigameCooldownAt,
                            onDebugFinish = viewModel::debugStopMinigameCooldown
                        )
                    } else if (minigame.type == "sequence") {
                        LanternSequenceGame(
                            config   = minigame,
                            easyMode = state.minigameEasyMode,
                            onSubmit = viewModel::submitMinigameAttempt,
                        )
                    } else {
                        BonfireRhythmGame(
                            config   = minigame,
                            easyMode = state.minigameEasyMode,
                            onSubmit = viewModel::submitMinigameAttempt,
                        )
                    }
                }
            }

            if (event.nightMarket.isNotEmpty()) {
                SectionCard(title = stringResource(R.string.seasonal_market_title)) {
                    event.nightMarket.forEachIndexed { index, offer ->
                        val effectUseful = when (offer.effect) {
                            "skip_bounty_cooldowns"  -> state.bountyTasks.any { it.cooldownUntilMs != null }
                            "skip_minigame_cooldown" -> state.minigameCooldownAt > System.currentTimeMillis()
                            else                     -> true
                        }
                        MarketOfferRow(
                            offer        = offer,
                            purchases    = state.marketPurchases[offer.id] ?: 0,
                            coins        = state.coins,
                            effectUseful = effectUseful,
                            onBuy        = { viewModel.purchaseMarketOffer(offer.id) },
                        )
                        if (index != event.nightMarket.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }

    SkillActivitySheet(
        viewModel         = skillsViewModel,
        craftingViewModel = craftingViewModel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinigameModeSelector(
    easyMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    visibleMs: Long,
    cooldownMs: Long,
    sequenceMode: Boolean = false,
) {
    val context = LocalContext.current
    Column {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                text  = stringResource(R.string.seasonal_minigame_mode_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.width(200.dp)) {
                SegmentedButton(
                    selected = !easyMode,
                    onClick  = { onModeChange(false) },
                    shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.seasonal_minigame_mode_normal)) }
                SegmentedButton(
                    selected = easyMode,
                    onClick  = { onModeChange(true) },
                    shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.seasonal_minigame_mode_easy)) }
            }
        }
        Text(
            text  = stringResource(
                if (sequenceMode) R.string.seasonal_minigame_sequence_mode_caption else R.string.seasonal_minigame_mode_caption,
                visibleMs, cooldownMs.formatDurationMs(context),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One row on the reward track: locked (padlock), claimable (button), or claimed. Tiers with no payload (the final banner tier) flip to "Claimed" automatically once reached. */
@Composable
private fun RewardTierRow(
    tier: SeasonalRewardTierData,
    eventId: String,
    tokens: Int,
    claimed: Boolean,
    onClaim: () -> Unit,
) {
    val context = LocalContext.current
    val hasPayload = tier.coins > 0 || tier.items.isNotEmpty() || tier.petId != null || tier.xpBoost
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text  = GameStrings.seasonalRewardDesc(context, eventId, tier.tokens, tier.description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text  = stringResource(R.string.seasonal_reward_tier_requirement, tier.tokens),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        when {
            claimed || (!hasPayload && tokens >= tier.tokens) -> Text(
                text       = stringResource(R.string.seasonal_reward_claimed),
                style      = MaterialTheme.typography.labelMedium,
                color      = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            hasPayload && tokens >= tier.tokens -> Button(onClick = onClaim) { Text(stringResource(R.string.seasonal_claim)) }
            else -> Text("🔒", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MarketOfferRow(
    offer: NightMarketOfferData,
    purchases: Int,
    coins: Long,
    effectUseful: Boolean,
    onBuy: () -> Unit,
) {
    val context = LocalContext.current
    val soldOut = offer.limit != null && purchases >= offer.limit
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text  = GameStrings.seasonalMarketName(context, offer.id, offer.displayName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text  = stringResource(R.string.seasonal_market_price, offer.coinCost.formatCoins()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val limit = offer.limit
            if (limit != null && !soldOut) {
                Text(
                    text  = stringResource(R.string.seasonal_market_limit_left, limit - purchases),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (soldOut) {
            Text(
                text  = stringResource(R.string.seasonal_market_sold_out),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(onClick = onBuy, enabled = coins >= offer.coinCost && effectUseful) {
                Text(stringResource(R.string.seasonal_market_buy))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun BountyTaskRow(
    taskProgress: SeasonalBountyTaskWithProgress,
    onClaim: () -> Unit,
    onGo: () -> Unit,
    onReroll: () -> Unit,
    onCooldownExpired: () -> Unit,
) {
    val task = taskProgress.task
    val context = LocalContext.current
    var showRerollConfirm by remember { mutableStateOf(false) }
    if (showRerollConfirm) {
        AlertDialog(
            onDismissRequest = { showRerollConfirm = false },
            title            = { Text(stringResource(R.string.seasonal_bounty_reroll)) },
            text             = { Text(stringResource(R.string.seasonal_bounty_reroll_confirm, SeasonalEventRepository.BOUNTY_REROLL_COST.formatCoins())) },
            confirmButton    = {
                TextButton(onClick = { showRerollConfirm = false; onReroll() }) {
                    Text(stringResource(R.string.seasonal_bounty_reroll))
                }
            },
            dismissButton    = {
                TextButton(onClick = { showRerollConfirm = false }) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(GameStrings.seasonalBountyName(context, task.id, task.displayName), style = MaterialTheme.typography.bodyMedium)
            Text(
                text  = GameStrings.seasonalBountyHint(context, task.id, task.hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text  = "${taskProgress.progress}/${task.amount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val cooldownUntilMs = taskProgress.cooldownUntilMs
        when {
            cooldownUntilMs != null -> BountyCooldownRow(cooldownUntilMs, onCooldownExpired)
            taskProgress.progress >= task.amount -> Button(onClick = onClaim) {
                Text(stringResource(if (task.type == "turn_in") R.string.seasonal_donate else R.string.seasonal_claim))
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showRerollConfirm = true }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.seasonal_bounty_reroll),
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onGo) { Text(stringResource(R.string.seasonal_go)) }
            }
        }
    }
}

@Composable
private fun BountyCooldownRow(resumesAtMs: Long, onExpired: () -> Unit) {
    var remainingMs by remember { mutableLongStateOf(resumesAtMs - System.currentTimeMillis()) }
    LaunchedEffect(resumesAtMs) {
        while (remainingMs > 0) {
            delay(1_000L)
            remainingMs = resumesAtMs - System.currentTimeMillis()
        }
        onExpired()
    }
    val totalSeconds = (remainingMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    Text(
        text  = stringResource(R.string.seasonal_bounty_cooldown, minutes, seconds),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MinigameCooldownRow(resumesAtMs: Long, onDebugFinish: () -> Unit) {
    var remainingMs by remember { mutableLongStateOf(resumesAtMs - System.currentTimeMillis()) }
    LaunchedEffect(resumesAtMs) {
        while (remainingMs > 0) {
            delay(1_000L)
            remainingMs = resumesAtMs - System.currentTimeMillis()
        }
    }
    val totalSeconds = (remainingMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    Text(
        text  = stringResource(R.string.carnival_cooldown, minutes, seconds),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (BuildConfig.DEBUG) {
        TextButton(onClick = onDebugFinish) {
            Text("[Debug] Finish Now")
        }
    }
}

/**
 * A whack-a-mole reflex minigame: over [SeasonalMinigameConfig.rounds] rounds, an ember lights
 * up in a random hole and the player must tap it before the reaction window elapses ([easyMode]
 * swaps in the longer, easier window and cooldown). Landing enough hits wins a token; falling
 * short is a real failure — either way the cooldown starts once the round set finishes.
 */
@Composable
private fun BonfireRhythmGame(
    config: SeasonalMinigameConfig,
    easyMode: Boolean,
    onSubmit: (Boolean) -> Unit,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var hits by remember { mutableIntStateOf(0) }
    var litHole by remember { mutableIntStateOf(-1) }
    val haptic = LocalHapticFeedback.current
    val visibleMs = if (easyMode) config.visibleMsEasy else config.visibleMs

    Text(
        text  = stringResource(R.string.seasonal_minigame_hint, config.hitsRequired, config.rounds),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))

    if (isPlaying) {
        Text(
            text  = stringResource(R.string.seasonal_minigame_round, round + 1, config.rounds, hits),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
    }

    val columns = 3
    val rows = (config.holeCount + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until columns) {
                    val index = r * columns + c
                    if (index >= config.holeCount) continue
                    val isLit = isPlaying && litHole == index
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (isLit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .clickable(enabled = isPlaying) {
                                if (litHole == index) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    litHole = -1
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLit) Text(config.emoji, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    countdown?.let { c ->
        Text(
            text       = "$c",
            style      = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary,
            textAlign  = TextAlign.Center,
            modifier   = Modifier.fillMaxWidth(),
        )
    }
    if (!isPlaying && countdown == null) {
        Button(
            onClick  = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); countdown = 3 },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.seasonal_tap))
        }
    }

    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        delay(1000L)
        if (c > 1) {
            countdown = c - 1
        } else {
            countdown = null
            isPlaying = true
            round = 0
            hits = 0
        }
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var localHits = 0
        for (r in 0 until config.rounds) {
            round = r
            litHole = Random.nextInt(config.holeCount)
            var elapsed = 0L
            var hitThisRound = false
            while (elapsed < visibleMs) {
                delay(30L)
                elapsed += 30L
                if (litHole == -1) { hitThisRound = true; break }
            }
            if (hitThisRound) { localHits++; hits = localHits }
            litHole = -1
            delay(150L)
        }
        isPlaying = false
        onSubmit(localHits >= config.hitsRequired)
    }
}

/**
 * A Simon-style memory minigame: each round the lanterns flash a sequence one step longer
 * ([easyMode] slows the flashes in exchange for a longer cooldown), then the player taps it
 * back in order. One wrong tap ends the run — reaching round
 * [SeasonalMinigameConfig.hitsRequired] (of [SeasonalMinigameConfig.rounds]) is a win either
 * way, matching the hint text's promise (issue #1734).
 */
@Composable
private fun LanternSequenceGame(
    config: SeasonalMinigameConfig,
    easyMode: Boolean,
    onSubmit: (Boolean) -> Unit,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var round by remember { mutableIntStateOf(1) }
    var showingSequence by remember { mutableStateOf(false) }
    var litLantern by remember { mutableIntStateOf(-1) }
    var inputIndex by remember { mutableIntStateOf(0) }
    val sequence = remember { mutableStateListOf<Int>() }
    val haptic = LocalHapticFeedback.current
    val flashMs = if (easyMode) config.visibleMsEasy else config.visibleMs

    Text(
        text  = stringResource(R.string.seasonal_minigame_sequence_hint, config.hitsRequired, config.rounds),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))

    if (isPlaying) {
        Text(
            text  = stringResource(R.string.seasonal_minigame_sequence_round, round, config.rounds),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text  = stringResource(if (showingSequence) R.string.seasonal_minigame_watch else R.string.seasonal_minigame_your_turn),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    val columns = 3
    val rows = (config.holeCount + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until columns) {
                    val index = r * columns + c
                    if (index >= config.holeCount) continue
                    val isLit = isPlaying && litLantern == index
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (isLit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .clickable(enabled = isPlaying && !showingSequence) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (index == sequence.getOrNull(inputIndex)) {
                                    inputIndex++
                                    if (inputIndex == sequence.size) {
                                        if (round == config.rounds) {
                                            isPlaying = false
                                            onSubmit(true)
                                        } else {
                                            round++
                                            showingSequence = true
                                        }
                                    }
                                } else {
                                    isPlaying = false
                                    onSubmit(round >= config.hitsRequired)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLit) Text(config.emoji, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    countdown?.let { c ->
        Text(
            text       = "$c",
            style      = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary,
            textAlign  = TextAlign.Center,
            modifier   = Modifier.fillMaxWidth(),
        )
    }
    if (!isPlaying && countdown == null) {
        Button(
            onClick  = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); countdown = 3 },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.seasonal_tap))
        }
    }

    LaunchedEffect(countdown) {
        val c = countdown ?: return@LaunchedEffect
        delay(1000L)
        if (c > 1) {
            countdown = c - 1
        } else {
            countdown = null
            sequence.clear()
            round = 1
            showingSequence = true
            isPlaying = true
        }
    }

    // Plays the sequence back at the start of every round, then hands control to the player.
    LaunchedEffect(isPlaying, round, showingSequence) {
        if (!isPlaying || !showingSequence) return@LaunchedEffect
        if (sequence.isEmpty()) sequence.add(Random.nextInt(config.holeCount))
        sequence.add(Random.nextInt(config.holeCount))
        delay(500L)
        for (index in sequence) {
            litLantern = index
            delay(flashMs)
            litLantern = -1
            delay(250L)
        }
        inputIndex = 0
        showingSequence = false
    }
}
