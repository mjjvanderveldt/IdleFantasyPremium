package com.fantasyidler.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fantasyidler.R
import com.fantasyidler.data.model.Skills
import com.fantasyidler.simulator.XpTable
import com.fantasyidler.util.GameStrings
import java.util.Locale

@Composable
fun LampSkillDialog(
    skillLevels: Map<String, Int>,
    skillXp: Map<String, Long>,
    sessionXpGain: Long,
    onSkillSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.slayer_lamp_pick_skill)) },
        text = {
            Column(
                modifier            = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LampSkillSection(
                    title           = stringResource(R.string.label_gathering_skills),
                    skillKeys       = Skills.GATHERING.filter { it != Skills.AGILITY },
                    skillLevels     = skillLevels,
                    skillXp         = skillXp,
                    sessionXpGain   = sessionXpGain,
                    onSkillSelected = onSkillSelected,
                )
                HorizontalDivider()
                LampSkillSection(
                    title           = stringResource(R.string.label_crafting_skills),
                    skillKeys       = Skills.CRAFTING_SKILLS,
                    skillLevels     = skillLevels,
                    skillXp         = skillXp,
                    sessionXpGain   = sessionXpGain,
                    onSkillSelected = onSkillSelected,
                )
                HorizontalDivider()
                LampSkillSection(
                    title           = stringResource(R.string.label_support_skills),
                    skillKeys       = Skills.SUPPORT + listOf(Skills.AGILITY),
                    skillLevels     = skillLevels,
                    skillXp         = skillXp,
                    sessionXpGain   = sessionXpGain,
                    onSkillSelected = onSkillSelected,
                )
                HorizontalDivider()
                LampSkillSection(
                    title           = stringResource(R.string.label_combat),
                    // Prayer is listed under Support; Slayer belongs here (mirrors SkillsScreen).
                    skillKeys       = Skills.COMBAT.filter { it != Skills.PRAYER } + Skills.SLAYER,
                    skillLevels     = skillLevels,
                    skillXp         = skillXp,
                    sessionXpGain   = sessionXpGain,
                    onSkillSelected = onSkillSelected,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

@Composable
private fun LampSkillSection(
    title: String,
    skillKeys: List<String>,
    skillLevels: Map<String, Int>,
    skillXp: Map<String, Long>,
    sessionXpGain: Long,
    onSkillSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LampSectionHeader(title)
        skillKeys.forEach { skillKey ->
            LampSkillRow(
                skillKey        = skillKey,
                level           = skillLevels[skillKey] ?: 1,
                xp              = skillXp[skillKey] ?: 0L,
                sessionXpGain   = sessionXpGain,
                onSkillSelected = onSkillSelected,
            )
        }
    }
}

@Composable
private fun LampSectionHeader(title: String) {
    Column {
        Text(
            text     = title.uppercase(Locale.getDefault()),
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp,top= 12.dp ),
        )
    }
}

@Composable
private fun LampSkillRow(
    skillKey: String,
    level: Int,
    xp: Long,
    sessionXpGain: Long,
    onSkillSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val name = GameStrings.skillName(context, skillKey)
    Surface(
        onClick  = { onSkillSelected(skillKey) },
        shape    = RoundedCornerShape(8.dp),
        color    = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val iconRes = GameStrings.skillIconRes(skillKey)
                if (iconRes != null) {
                    Image(
                        painter            = painterResource(iconRes),
                        contentDescription = null,
                        modifier           = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        text  = GameStrings.skillEmoji(skillKey),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.slayer_level_label, level),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val endXp = xp + sessionXpGain
                    val isOverMax = sessionXpGain > 0L && endXp > XpTable.xpForLevel(99)
                    val xpLineText = if (sessionXpGain <= 0L) null else {
                        val levelAfter = XpTable.levelForXp(endXp)
                        val levelGain = levelAfter - XpTable.levelForXp(xp)
                        val pct = (XpTable.progressFraction(endXp) * 100).toInt()
                        if (levelGain > 0) {
                            stringResource(R.string.slayer_lamp_level_preview_gain, levelAfter, levelGain, pct)
                        } else {
                            stringResource(R.string.slayer_lamp_level_preview, levelAfter, pct)
                        }
                    }
                    if (isOverMax) {
                        Text(
                            text = stringResource(R.string.slayer_lamp_max_level_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Spacer(Modifier)
                    }

                    if (xpLineText != null) {
                        Text(
                            text = xpLineText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}