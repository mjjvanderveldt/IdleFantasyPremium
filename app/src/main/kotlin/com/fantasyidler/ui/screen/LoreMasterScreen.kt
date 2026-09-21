package com.fantasyidler.ui.screen

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fantasyidler.R
import com.fantasyidler.ui.viewmodel.ElderQuestsViewModel

private data class LoreFragment(
    val title: String,
    val body: String,
    /** Quest id gating this fragment; null = always shown. */
    val unlockedByQuest: String?,
    val unlockHint: String,
)

private val LORE: List<LoreFragment> = listOf(
    LoreFragment(
        title           = "Prologue: Rowan's Note",
        body            = "\"You who sail here, know this. I am the last living student of the Ancient School of the Elders. My tutors are silent stones. What I have learned, I offer to those brave enough to walk the isle. Read gently. Some of what waits below is not meant to be woken.\"",
        unlockedByQuest = null,
        unlockHint      = "Given by Rowan upon your first landing.",
    ),

    // Act I fragments
    LoreFragment(
        title           = "I.a: The Coastline Was Once Kept",
        body            = "\"The tide pools around the dock aren't natural. They were carved. Someone lined them with stone. The stones are older than any temple I have visited on the mainland, and their edges are precise the way old work rarely is. The isle was inhabited long before it was forgotten.\"",
        unlockedByQuest = "act1_first_steps",
        unlockHint      = "Rowan writes this after your first clears of the Beach.",
    ),
    LoreFragment(
        title           = "I.b: The Rock Remembers",
        body            = "\"The mythrite you brought me is not natural ore. Its grain is too even, its colour too consistent. It reads as if it was grown, not formed. My grandmother told me a rhyme: 'the isle grows the isle back, block by patient block.' I never understood it. I am starting to.\"",
        unlockedByQuest = "act1_rowans_cache",
        unlockHint      = "Rowan writes after receiving her mythrite cache.",
    ),
    LoreFragment(
        title           = "I.c: The Fish Are Old",
        body            = "\"The tidepool crab you cooked has a taste I remember only from a single fish my grandmother once served. She called it 'a scale off the elder shore.' She would not say more. The isle's food is not just old. It is the same food, in the same shapes, kept somehow.\"",
        unlockedByQuest = "act1_first_cooking",
        unlockHint      = "Rowan's kitchen notes after your cooking run.",
    ),

    // Act II fragments
    LoreFragment(
        title           = "II.a: The Grove Was a Garden",
        body            = "\"The roots grow in rows. The trees are the same trees, spaced exactly, tended by no one. This grove was a garden, someone's garden, and whoever tended it left the shears where they last rested. They're still sharp.\"",
        unlockedByQuest = "act2_cutting_vines",
        unlockHint      = "Rowan's notes after your Forest clears.",
    ),
    LoreFragment(
        title           = "II.b: A Ritual for Not Dying",
        body            = "\"The library's oldest text describes a rite the writers called the Grand Ritual. It promised the practitioner would not die. It does not say the practitioner would live. There is a difference. The text does not go on. I am not sure I want it to.\"",
        unlockedByQuest = "act2_library_salvage",
        unlockHint      = "Recovered once you help Rowan shore up the library.",
    ),
    LoreFragment(
        title           = "II.c: The Order That Bore the Ritual",
        body            = "\"They called themselves the Elders. Not as a title of age, but of station: those who came first, and those who would still be here when everyone else was gone. Your Coastal armour matches an illustration on the second wall. The measurements are identical. They wore what you wear now.\"",
        unlockedByQuest = "act2_first_armor",
        unlockHint      = "Rowan compares your first Coastal set to the library plates.",
    ),

    // Act III fragments
    LoreFragment(
        title           = "III.a: The Peak Was Their Altar",
        body            = "\"The summit is a ring. The ring is a seal. The stones of the ring are not carved. They are named. Each stone is one of the Elders. There are hundreds of stones. That was, I think, once the whole order.\"",
        unlockedByQuest = "act3_ascent",
        unlockHint      = "Rowan's map after your Volcano clears.",
    ),
    LoreFragment(
        title           = "III.b: The Alloy That Holds",
        body            = "\"Your Voidsteel and the seal's stones are the same substance. The Elders bound themselves with what they mined here. They literally cast their names into the alloy. This is how you contain something that will not stop wanting to live: you write your own name against its name, and hold.\"",
        unlockedByQuest = "act3_voidsteel_study",
        unlockHint      = "Rowan compares Voidsteel ore to a shard of the seal.",
    ),
    LoreFragment(
        title           = "III.c: The One Who Would Not Sit Down",
        body            = "\"Every Elder walked to the ring and lay themselves down as a stone. Every Elder but one. The last of them refused, and so the others bound that one inside the seal instead of joining it. His stone would have been carved 'Merren.' It's the same word my grandmother would sometimes say in her sleep, and never in the day.\"",
        unlockedByQuest = "act3_grove_gear",
        unlockHint      = "Rowan reads to you from the sealing site's ledger.",
    ),

    // Act IV fragments
    LoreFragment(
        title           = "IV.a: The Chamber Is Flooding",
        body            = "\"The caldera below the peak was dry when the seal was cast. It is now filling with sea. The seal is failing not because someone attacks it from the outside, but because the thing inside it has spent every one of the intervening centuries learning the seal's shape from the inside. When it breaks the seal, it breaks it with the seal's own hands.\"",
        unlockedByQuest = "act4_descent",
        unlockHint      = "Rowan writes this after your descents into the Abyss.",
    ),
    LoreFragment(
        title           = "IV.b: The Ritual's True Cost",
        body            = "\"The Grand Ritual did not fail. It worked. The practitioners lived. What it took, in exchange, was everything that made those lives worth living: their names, their kin, their remembered dead. The oldest Elders were the emptiest. The Last Elder is the oldest of them all. He does not remember he was once Merren, or that Merren once had a granddaughter, or that that granddaughter is standing on this isle right now. Rowan wrote none of this down. She said it to me in one breath.\"",
        unlockedByQuest = "act4_full_set",
        unlockHint      = "Rowan speaks this once you finish the full Elder set.",
    ),
    LoreFragment(
        title           = "IV.c: Epilogue: What You Bring Back",
        body            = "\"He did not know me. He knew my armour, because he wore it once. He knew the seal, because he made it. When it came apart, when I struck the last of him down, he did what no other Elder did: he remembered. Not my name. Not his. He remembered the taste of the crab I served him at the peak. My grandmother's recipe.\"\n\n\"That was enough. The isle recognises you now. The stones on the peak have one fewer name, and one more. Whatever you build after this is your own story.\"",
        unlockedByQuest = "act4_face_last_elder",
        unlockHint      = "Rowan's final letter after the Last Elder falls.",
    ),
)

/** Lore Master: the isle's world-building. Fragments drip out as the quest chain
 *  advances. Reads like a story, from Rowan's arrival on the isle to the truth of
 *  what the Last Elder was. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoreMasterScreen(
    onBack: () -> Unit,
    questsVm: ElderQuestsViewModel = hiltViewModel(),
) {
    val questsState by questsVm.state.collectAsState()
    val completed = questsState.completedIds

    val discovered = LORE.count { it.unlockedByQuest == null || it.unlockedByQuest in completed }
    val total = LORE.size

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.elder_isle_lore_master_title)) },
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

            item {
                Surface(
                    shape    = RoundedCornerShape(16.dp),
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = "Lore Fragments",
                                style      = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier   = Modifier.weight(1f),
                            )
                            Text(
                                text  = "$discovered / $total",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text  = "Fragments unlock as you complete quests. Each drop reveals a little more of what the Elder Isle is and what the Last Elder actually was.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(LORE, key = { it.title }) { fragment ->
                val unlocked = fragment.unlockedByQuest == null || fragment.unlockedByQuest in completed
                LoreFragmentCard(fragment, unlocked)
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LoreFragmentCard(fragment: LoreFragment, unlocked: Boolean) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape    = CircleShape,
                    color    = if (unlocked) MaterialTheme.colorScheme.primaryContainer
                               else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector        = if (unlocked) Icons.Filled.Check else Icons.Filled.Lock,
                            contentDescription = null,
                            tint               = if (unlocked) MaterialTheme.colorScheme.primary
                                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier           = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text       = fragment.title,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color      = if (unlocked) MaterialTheme.colorScheme.onSurface
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (unlocked) {
                Text(
                    text  = fragment.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text  = fragment.unlockHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
