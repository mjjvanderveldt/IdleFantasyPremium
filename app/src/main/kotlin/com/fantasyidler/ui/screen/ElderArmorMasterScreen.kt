package com.fantasyidler.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fantasyidler.R
import com.fantasyidler.ui.viewmodel.ElderArmorMasterViewModel
import com.fantasyidler.ui.viewmodel.ElderPieceRow

/**
 * Elder Armor Master: sigil embedding only. Elder armor is now crafted through the
 * Smithing skill on the isle Skills tab. This screen exists to slot sigil stones into
 * whichever Elder pieces you own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElderArmorMasterScreen(
    onBack: () -> Unit,
    vm: ElderArmorMasterViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    AppBannerEffect(state.snackbarMessage, vm::snackbarConsumed)

    val craftedRows = state.rows.filter { it.crafted }
    var embedTargetPiece by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.elder_isle_armor_master_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // Set progress header
            item {
                Surface(
                    shape    = RoundedCornerShape(16.dp),
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = "Elder Set Progress",
                                style      = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier   = Modifier.weight(1f),
                            )
                            Text(
                                text  = "${craftedRows.size} / ${state.rows.size}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (state.rows.isEmpty()) 0f else craftedRows.size / state.rows.size.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text  = "Craft Elder armor via the Smithing skill. This screen embeds sigil stones into the pieces you own.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (craftedRows.isEmpty()) {
                item {
                    Surface(
                        shape    = RoundedCornerShape(16.dp),
                        color    = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier            = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text       = "No Elder pieces yet",
                                style      = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text      = "Craft your first Elder piece via Smithing to unlock the Sigil Embedder.",
                                style     = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                item {
                    SigilEmbedderCard(
                        rows           = craftedRows,
                        stoneNameOf    = vm::sigilStoneDisplayName,
                        stoneEffectOf  = vm::sigilStoneEffect,
                        onEmbedTapped  = { pieceKey -> embedTargetPiece = pieceKey },
                        onRemoveTapped = vm::removeSigil,
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // Sigil stone picker sheet
    val target = embedTargetPiece
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { embedTargetPiece = null }, sheetState = sheetState) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text       = stringResource(R.string.elder_armor_pick_stone),
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                state.sigilStones.forEach { row ->
                    val enabled = row.owned > 0
                    Surface(
                        shape    = RoundedCornerShape(10.dp),
                        color    = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .let { if (enabled) it.clickable { vm.embedSigil(target, row.stone.itemKey); embedTargetPiece = null } else it },
                    ) {
                        Row(
                            modifier            = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment   = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text       = row.stone.displayName,
                                    style      = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color      = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text  = row.stone.effect,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text  = "Owned: ${row.owned}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SigilEmbedderCard(
    rows: List<ElderPieceRow>,
    stoneNameOf: (String) -> String,
    stoneEffectOf: (String) -> String,
    onEmbedTapped: (String) -> Unit,
    onRemoveTapped: (String) -> Unit,
) {
    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = stringResource(R.string.elder_armor_sigil_embedder),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f),
                )
                Text(
                    text  = "${rows.size} slot" + if (rows.size == 1) "" else "s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text  = "Embed sigil stones into your Elder armor for permanent bonuses. Swapping a stone returns the previous one to your inventory.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            rows.forEach { row ->
                SigilSocketRow(
                    row            = row,
                    stoneNameOf    = stoneNameOf,
                    stoneEffectOf  = stoneEffectOf,
                    onEmbedTapped  = onEmbedTapped,
                    onRemoveTapped = onRemoveTapped,
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SigilSocketRow(
    row: ElderPieceRow,
    stoneNameOf: (String) -> String,
    stoneEffectOf: (String) -> String,
    onEmbedTapped: (String) -> Unit,
    onRemoveTapped: (String) -> Unit,
) {
    val hasSigil = row.embeddedSigil != null
    Surface(
        shape    = RoundedCornerShape(10.dp),
        color    = if (hasSigil) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment   = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = row.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (hasSigil) {
                    Text(
                        text  = stoneNameOf(row.embeddedSigil!!),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text  = stoneEffectOf(row.embeddedSigil!!),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text  = stringResource(R.string.elder_armor_sigil_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            if (hasSigil) {
                TextButton(onClick = { onRemoveTapped(row.key) }) {
                    Text(stringResource(R.string.elder_armor_sigil_remove))
                }
                Button(onClick = { onEmbedTapped(row.key) }) {
                    Text(stringResource(R.string.elder_armor_sigil_change))
                }
            } else {
                Button(onClick = { onEmbedTapped(row.key) }) {
                    Text(stringResource(R.string.elder_armor_sigil_embed))
                }
            }
        }
    }
}
