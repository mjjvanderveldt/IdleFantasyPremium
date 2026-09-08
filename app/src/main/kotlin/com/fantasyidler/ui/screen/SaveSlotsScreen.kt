package com.fantasyidler.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fantasyidler.R
import com.fantasyidler.repository.SlotInfo
import com.fantasyidler.ui.viewmodel.SaveSlotsViewModel
import com.fantasyidler.ui.viewmodel.combatLevelFrom
import com.fantasyidler.ui.viewmodel.totalLevelFrom
import com.fantasyidler.util.formatCoins
import com.fantasyidler.util.formatDurationMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSlotsScreen(
    onBack: () -> Unit,
    onSwitched: () -> Unit,
    viewModel: SaveSlotsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var createSlot by remember { mutableStateOf<SlotInfo?>(null) }
    var deleteSlot by remember { mutableStateOf<SlotInfo?>(null) }

    LaunchedEffect(state.switchCompleted) {
        if (state.switchCompleted) {
            if (state.ironmanDemoted) {
                AppBannerCenter.enqueue(context.getString(R.string.settings_imported_demoted))
            }
            viewModel.switchCompletedConsumed()
            onSwitched()
        }
    }
    LaunchedEffect(state.switchFailed) {
        if (state.switchFailed) {
            viewModel.switchFailedConsumed()
            AppBannerCenter.enqueue(context.getString(R.string.save_slot_switch_failed))
        }
    }

    createSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { createSlot = null },
            title = { Text(stringResource(R.string.save_slot_create_title)) },
            text  = { Text(stringResource(R.string.save_slot_create_body, slot.slot)) },
            confirmButton = {
                Button(onClick = { createSlot = null; viewModel.switchTo(slot.slot) }) {
                    Text(stringResource(R.string.save_slot_create_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { createSlot = null }) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }

    deleteSlot?.let { slot ->
        val name = slot.flags?.characterName?.ifBlank { null }
            ?: stringResource(R.string.home_adventurer)
        AlertDialog(
            onDismissRequest = { deleteSlot = null },
            title = { Text(stringResource(R.string.save_slot_delete_title)) },
            text  = { Text(stringResource(R.string.save_slot_delete_body, name)) },
            confirmButton = {
                Button(
                    onClick = { deleteSlot = null; viewModel.deleteSlot(slot.slot) },
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.save_slot_delete_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSlot = null }) { Text(stringResource(R.string.btn_cancel)) }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.save_slots_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text  = stringResource(R.string.save_slots_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.slots.forEach { slot ->
                    SaveSlotCard(
                        slot     = slot,
                        enabled  = !state.isSwitching,
                        onClick  = {
                            when {
                                slot.isActive -> {}
                                slot.exists   -> viewModel.switchTo(slot.slot)
                                else          -> createSlot = slot
                            }
                        },
                        onDelete = { deleteSlot = slot },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.isSwitching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun SaveSlotCard(
    slot: SlotInfo,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val flags = slot.flags
    Card(
        modifier = modifier.clickable(enabled = enabled && !slot.isActive, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (slot.isActive) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        if (!slot.exists || flags == null) {
            Row(
                modifier              = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector        = Icons.Filled.Add,
                    contentDescription = null,
                    modifier           = Modifier.size(40.dp),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    Text(
                        text  = stringResource(R.string.save_slot_new),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text  = stringResource(R.string.save_slot_label, slot.slot),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Card
        }

        Row(
            modifier          = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CharacterSprite(
                    race       = flags.characterRace.ifBlank { "human" },
                    skinTone   = flags.characterSkinTone,
                    hairStyle  = flags.characterHairStyle,
                    hairColor  = flags.characterHairColor,
                    eyeStyle   = flags.characterEyeStyle,
                    beardStyle = flags.characterBeardStyle,
                    beardColor = flags.characterBeardColor,
                    modifier   = Modifier.height(56.dp).aspectRatio(64f / 36f),
                )
                Text(
                    text  = stringResource(R.string.save_slot_label, slot.slot),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Name on its own line: sharing a row with the badge let the badge (and the
                // unweighted right column) squeeze the name down to a few characters on
                // narrow screens (issue #1552).
                Text(
                    text       = flags.characterName.ifBlank { stringResource(R.string.home_adventurer) },
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (flags.ironman) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.Shield,
                            contentDescription = null,
                            modifier           = Modifier.size(12.dp),
                            tint               = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            text  = stringResource(R.string.save_slot_ironman),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                // Two lines instead of one bullet-joined line: the joined form wraps
                // mid-phrase on narrow cards (issue #1433)
                Text(
                    text  = stringResource(R.string.save_slot_combat_level, combatLevelFrom(slot.skillLevels)),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text  = stringResource(R.string.save_slot_total_level, totalLevelFrom(slot.skillLevels)),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text  = slot.coins.formatCoins(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val createdAt = flags.characterCreatedAt
                if (createdAt > 0L) {
                    val age = (System.currentTimeMillis() - createdAt).coerceAtLeast(60_000L)
                    Text(
                        text  = stringResource(R.string.save_slot_created, age.formatDurationMs(context)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (slot.isActive) {
                    Text(
                        text       = stringResource(R.string.save_slot_playing),
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text  = stringResource(R.string.save_slot_last_played, elapsedSince(slot.lastPlayedAt).formatDurationMs(context)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector        = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.save_slot_delete_btn),
                            modifier           = Modifier.size(18.dp),
                            tint               = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun elapsedSince(epochMs: Long): Long =
    (System.currentTimeMillis() - epochMs).coerceAtLeast(60_000L)
