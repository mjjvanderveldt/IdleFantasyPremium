package com.fantasyidler.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Context
import com.fantasyidler.BuildConfig
import com.fantasyidler.R
import com.fantasyidler.repository.PrestigeActionResult
import com.fantasyidler.ui.viewmodel.PrestigeDetailViewModel
import com.fantasyidler.ui.viewmodel.PrestigeNodeUi
import com.fantasyidler.ui.viewmodel.PrestigePathUi
import com.fantasyidler.util.GameStrings
import com.fantasyidler.util.formatDurationMs

private val TIER_NUMERALS = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

private fun nodeDisplayName(context: Context, skill: String, pathKey: String, tier: Int): String =
    "${GameStrings.prestigePathName(context, skill, pathKey)} ${TIER_NUMERALS.getOrElse(tier - 1) { "$tier" }}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrestigeDetailScreen(
    onBack: () -> Unit = {},
    viewModel: PrestigeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val skill = viewModel.skill
    val skillName = GameStrings.skillName(context, skill)

    var selectedNode by remember { mutableStateOf<Pair<String, PrestigeNodeUi>?>(null) }
    var showPrestigeConfirm by remember { mutableStateOf(false) }
    var showRespecConfirm by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }

    AppBannerEffect(banner) { banner = null }

    if (showPrestigeConfirm) {
        AlertDialog(
            onDismissRequest = { showPrestigeConfirm = false },
            title = { Text(stringResource(R.string.prestige_confirm_title, skillName)) },
            text  = { Text(
                if (state.pointsOnPrestige > 0)
                    stringResource(R.string.prestige_confirm_message_points, skillName, state.pointsOnPrestige)
                else stringResource(R.string.prestige_confirm_message_xp_only, skillName)
            ) },
            confirmButton = {
                TextButton(onClick = {
                    showPrestigeConfirm = false
                    viewModel.prestige()
                }) { Text(stringResource(R.string.prestige)) }
            },
            dismissButton = {
                TextButton(onClick = { showPrestigeConfirm = false }) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }

    if (showRespecConfirm) {
        AlertDialog(
            onDismissRequest = { showRespecConfirm = false },
            title = { Text(stringResource(R.string.prestige_respec_confirm_title)) },
            text  = { Text(stringResource(R.string.prestige_respec_confirm_message, skillName)) },
            confirmButton = {
                TextButton(onClick = {
                    showRespecConfirm = false
                    viewModel.respec { result ->
                        banner = when (result) {
                            PrestigeActionResult.SUCCESS  -> context.getString(R.string.prestige_respec_done, skillName)
                            PrestigeActionResult.COOLDOWN -> context.getString(R.string.prestige_respec_on_cooldown)
                            else -> null
                        }
                    }
                }) { Text(stringResource(R.string.prestige_respec)) }
            },
            dismissButton = {
                TextButton(onClick = { showRespecConfirm = false }) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }

    selectedNode?.let { (pathKey, node) ->
        ModalBottomSheet(onDismissRequest = { selectedNode = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text  = nodeDisplayName(context, skill, pathKey, node.tier),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (node.owned) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                node.races?.let { races ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (node.raceLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text  = stringResource(R.string.prestige_node_race_only, GameStrings.raceNames(context, races)),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (node.raceLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Race-locked effects stay visible to other races so players can judge
                // whether a race change is worth it.
                Text(
                    text  = GameStrings.prestigeEffectDesc(context, node.effect, node.value, node.unlock),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!node.auto) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text  = stringResource(R.string.prestige_node_cost, node.cost),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                when {
                    node.auto -> Text(
                        text  = if (node.owned) stringResource(R.string.prestige_node_auto_earned, node.tier)
                                else stringResource(R.string.prestige_node_auto_pending, node.tier),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (node.owned) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    node.owned -> Text(
                        text  = stringResource(R.string.prestige_node_owned),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    node.raceLocked -> Text(
                        text  = stringResource(
                            R.string.prestige_node_race_locked_desc,
                            node.races?.let { GameStrings.raceNames(context, it) }.orEmpty(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    node.prereqLocked -> Text(
                        text  = stringResource(R.string.prestige_node_prereq_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Button(
                        onClick = {
                            viewModel.purchase(node.id) { result ->
                                banner = when (result) {
                                    PrestigeActionResult.SUCCESS -> context.getString(
                                        R.string.prestige_node_purchased,
                                        nodeDisplayName(context, skill, pathKey, node.tier),
                                    )
                                    PrestigeActionResult.NOT_ENOUGH_POINTS -> context.getString(R.string.prestige_node_not_enough)
                                    else -> null
                                }
                            }
                            selectedNode = null
                        },
                        enabled = node.affordable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (node.affordable) stringResource(R.string.prestige_buy, node.cost)
                            else stringResource(R.string.prestige_node_not_enough)
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val iconRes = GameStrings.skillIconRes(skill)
                        if (iconRes != null) {
                            Image(
                                painter            = painterResource(iconRes),
                                contentDescription = null,
                                modifier           = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(skillName)
                        } else {
                            Text("${GameStrings.skillEmoji(skill)} $skillName")
                        }
                    }
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
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            PrestigeHeaderCard(
                state          = state,
                skillName      = skillName,
                onPrestige     = { showPrestigeConfirm = true },
                onRespec       = { showRespecConfirm = true },
            )
            Spacer(Modifier.height(16.dp))

            // The tree: a vertical trunk with one right-angle branch per path.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .padding(vertical = 12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Column(Modifier.weight(1f)) {
                    state.paths.forEach { path ->
                        PathBranch(
                            skill      = skill,
                            path       = path,
                            playerRace = state.playerRace,
                            // Hidden nodes stay a mystery: a banner nudge instead of the detail sheet.
                            onNodeTap  = { node ->
                                if (node.prereqLocked && !BuildConfig.DEBUG) banner = context.getString(R.string.prestige_node_hidden)
                                else selectedNode = path.key to node
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrestigeHeaderCard(
    state: PrestigeDetailViewModel.UiState,
    skillName: String,
    onPrestige: () -> Unit,
    onRespec: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text  = stringResource(R.string.prestige_points_available, state.unspentPoints),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text  = stringResource(R.string.prestige_points_lifetime, state.earnedPoints, state.pointCap),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.prestigeCount > 0) {
                    Text(
                        text  = "★×${state.prestigeCount}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                !state.canPrestigeMore -> Text(
                    text  = stringResource(R.string.prestige_maxed_cap),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                state.level >= 99 -> Button(onClick = onPrestige, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.pointsOnPrestige > 0)
                            stringResource(R.string.prestige_button_with_points, state.pointsOnPrestige)
                        else stringResource(R.string.prestige_button_xp_only)
                    )
                }
                else -> Text(
                    text  = stringResource(R.string.prestige_requires_99, state.level),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.hasPurchasedNodes) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onRespec,
                    enabled = state.respecCooldownMs <= 0L,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.respecCooldownMs <= 0L) stringResource(R.string.prestige_respec)
                        else stringResource(
                            R.string.prestige_respec_available_in,
                            state.respecCooldownMs.formatDurationMs(context),
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PathBranch(
    skill: String,
    path: PrestigePathUi,
    playerRace: String,
    onNodeTap: (PrestigeNodeUi) -> Unit,
) {
    val context = LocalContext.current
    val racesLock = path.nodes.firstOrNull()?.races?.takeIf { first ->
        path.nodes.all { it.races?.toSet() == first.toSet() }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Right-angle branch connector off the trunk.
            Box(
                Modifier
                    .width(14.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text  = GameStrings.prestigePathName(context, skill, path.key),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (racesLock != null && playerRace !in racesLock)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            if (path.auto) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text  = stringResource(R.string.prestige_path_auto_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (racesLock != null) {
                Spacer(Modifier.width(6.dp))
                // Green lock: your race can use this path. Red lock: another race's exclusive.
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (playerRace in racesLock) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text  = GameStrings.raceNames(context, racesLock),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (playerRace in racesLock) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Mixed paths (race-locked tiers appended to an open path) still surface
                // their races here, so e.g. the Gnome farming bonus is findable in the tree.
                path.nodes.mapNotNull { it.races }
                    .flatten().distinct().forEach { race ->
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (playerRace == race) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text  = GameStrings.raceName(context, race),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (playerRace == race) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
        Row(
            modifier = Modifier
                .padding(start = 20.dp, top = 8.dp, bottom = 12.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            path.nodes.forEachIndexed { index, node ->
                if (index > 0) {
                    val linkOwned = path.nodes[index - 1].owned
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (linkOwned) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                    )
                }
                NodeCircle(node) { onNodeTap(node) }
            }
        }
    }
}

@Composable
private fun NodeCircle(node: PrestigeNodeUi, onTap: () -> Unit) {
    val purchasable = !node.owned && !node.raceLocked && !node.prereqLocked && node.affordable
    val circleColor = when {
        node.owned  -> MaterialTheme.colorScheme.primary
        purchasable -> MaterialTheme.colorScheme.primaryContainer
        else        -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        node.owned  -> MaterialTheme.colorScheme.onPrimary
        purchasable -> MaterialTheme.colorScheme.onPrimaryContainer
        else        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(circleColor)
                .let {
                    if (purchasable) it.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else it
                }
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            when {
                node.owned -> Icon(Icons.Filled.Check, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
                node.raceLocked -> Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                // Nodes past the next unowned tier hide behind "?" until the path reaches them.
                node.prereqLocked && !BuildConfig.DEBUG -> Text(
                    text  = "?",
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    fontWeight = FontWeight.Bold,
                )
                else -> Text(
                    text  = TIER_NUMERALS.getOrElse(node.tier - 1) { "${node.tier}" },
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text  = if (node.auto) "★" else "${node.cost}◆",
            style = MaterialTheme.typography.labelSmall,
            color = if (node.owned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
