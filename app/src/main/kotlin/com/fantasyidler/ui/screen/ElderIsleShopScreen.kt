package com.fantasyidler.ui.screen

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fantasyidler.R
import com.fantasyidler.ui.viewmodel.ElderIsleShopViewModel

private data class ShopEntry(
    val name: String,
    val body: String,
    val cost: Int,
    val reward: Map<String, Int>,
)

/** Isle Shop: spend Elder Essence on material bundles and sigil stones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElderIsleShopScreen(
    onBack: () -> Unit,
    vm: ElderIsleShopViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    AppBannerEffect(state.snackbarMessage, vm::snackbarConsumed)

    val catalog = listOf(
        // Bulk material bundles for players who prefer buying over farming.
        ShopEntry(
            name   = "Elder Bones bundle",
            body   = "20 Elder Bones. Fuel for isle rituals and prayer.",
            cost   = 50,
            reward = mapOf("elder_bone" to 20),
        ),
        ShopEntry(
            name   = "Sacred Wood bundle",
            body   = "5 Sacred Wood. Rare crafting input.",
            cost   = 100,
            reward = mapOf("sacred_wood" to 5),
        ),
        ShopEntry(
            name   = "Ancient Sigil (single, premium)",
            body   = "1 Ancient Sigil. Direct-buy at a heavy premium for the final push toward a full Elder set.",
            cost   = 500,
            reward = mapOf("ancient_sigil" to 1),
        ),
        // Sigil stones for the Embedder. These otherwise only come from rare enemy drops,
        // so the shop is where you go to fill a specific colour you want.
        ShopEntry(
            name   = "Ruby Sigil Stone",
            body   = "One Ruby Sigil Stone. Embed for +5% coin drops.",
            cost   = 300,
            reward = mapOf("elder_ruby" to 1),
        ),
        ShopEntry(
            name   = "Sapphire Sigil Stone",
            body   = "One Sapphire Sigil Stone. Embed for +5% XP gain.",
            cost   = 300,
            reward = mapOf("elder_sapphire" to 1),
        ),
        ShopEntry(
            name   = "Emerald Sigil Stone",
            body   = "One Emerald Sigil Stone. Embed for +5% loot chance.",
            cost   = 300,
            reward = mapOf("elder_emerald" to 1),
        ),
        ShopEntry(
            name   = "Topaz Sigil Stone",
            body   = "One Topaz Sigil Stone. Embed for +5% Ancient Sigil drops.",
            cost   = 300,
            reward = mapOf("elder_topaz" to 1),
        ),
        ShopEntry(
            name   = "Amethyst Sigil Stone",
            body   = "One Amethyst Sigil Stone. Embed for +5% Elder Essence gain.",
            cost   = 300,
            reward = mapOf("elder_amethyst" to 1),
        ),
        ShopEntry(
            name   = "Diamond Sigil Stone",
            body   = "One Diamond Sigil Stone. Embed for +5% Elder Bone drops.",
            cost   = 500,
            reward = mapOf("elder_diamond" to 1),
        ),
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.elder_isle_shop_title)) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Surface(
                    shape    = MaterialTheme.shapes.medium,
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier              = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            text  = "Elder Essence",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text  = "${state.essence}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            items(catalog) { entry ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = entry.name,
                                style      = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier   = Modifier.weight(1f),
                            )
                            Text(
                                text  = "${entry.cost} essence",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text  = entry.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { vm.buyBundle(entry.cost, entry.reward, entry.name) },
                                enabled = state.essence >= entry.cost,
                            ) { Text(stringResource(R.string.elder_isle_shop_buy)) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
