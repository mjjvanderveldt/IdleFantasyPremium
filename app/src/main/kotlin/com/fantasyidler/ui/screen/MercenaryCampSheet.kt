package com.fantasyidler.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fantasyidler.R
import com.fantasyidler.data.json.MercenaryData
import com.fantasyidler.ui.viewmodel.MercContract
import com.fantasyidler.util.GameStrings
import com.fantasyidler.util.dailyResetClockTime
import com.fantasyidler.util.formatCoins
import com.fantasyidler.util.formatDurationMs

/**
 * Daily rotating pool of hireable raid mercenaries plus the current party.
 * Contracts run until the daily reset; dismissing is allowed but never refunds.
 */
@Composable
internal fun MercenaryCampSheet(
    pool: List<MercenaryData>,
    hiredMercs: List<MercContract>,
    maxParty: Int,
    onHire: (String) -> Unit,
    onDismissMerc: (String) -> Unit,
) {
    val context = LocalContext.current
    val hiredIds = hiredMercs.map { it.merc.id }.toSet()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp),
    ) {
        Text(
            text       = stringResource(R.string.merc_camp_title),
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text  = stringResource(R.string.merc_camp_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text       = stringResource(R.string.raid_party_status, hiredMercs.size, maxParty),
            style      = MaterialTheme.typography.labelLarge,
            color      = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        // The daily pool re-rolls while 24h contracts persist, so hired mercs missing
        // from today's pool would otherwise have no Dismiss button (issue #1624).
        if (hiredMercs.isNotEmpty()) {
            Text(
                text       = stringResource(R.string.raid_party_title),
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            hiredMercs.sortedBy { it.expiresAt }.forEach { contract ->
                val merc = contract.merc
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text       = "${merc.emoji} ${GameStrings.mercName(context, merc.id)}",
                            style      = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text  = tierLabel(merc.tier),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text  = stringResource(
                                R.string.merc_contract_remaining,
                                (contract.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L).formatDurationMs(context),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onDismissMerc(merc.id) }) {
                        Text(stringResource(R.string.merc_dismiss))
                    }
                }
                HorizontalDivider()
            }
            Spacer(Modifier.height(12.dp))
        }
        pool.sortedBy { it.hireCost }.forEach { merc ->
            val hired = merc.id in hiredIds
            Row(
                modifier              = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text       = "${merc.emoji} ${GameStrings.mercName(context, merc.id)}",
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text  = tierLabel(merc.tier),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text  = stringResource(R.string.merc_stats, merc.attackLevel + merc.attackBonus, merc.strengthLevel + merc.strengthBonus, merc.defenseLevel, merc.hp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text  = stringResource(R.string.merc_cost_per_day, merc.hireCost.formatCoins()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hired) {
                    TextButton(onClick = { onDismissMerc(merc.id) }) {
                        Text(stringResource(R.string.merc_dismiss))
                    }
                } else {
                    Button(
                        onClick = { onHire(merc.id) },
                        enabled = hiredMercs.size < maxParty,
                    ) {
                        Text(stringResource(R.string.merc_hire))
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun tierLabel(tier: String): String = when (tier) {
    "cheap"    -> stringResource(R.string.merc_tier_cheap)
    "seasoned" -> stringResource(R.string.merc_tier_seasoned)
    else       -> stringResource(R.string.merc_tier_elite)
}
