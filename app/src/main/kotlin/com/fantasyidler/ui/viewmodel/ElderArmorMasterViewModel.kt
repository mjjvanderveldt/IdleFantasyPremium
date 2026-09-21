package com.fantasyidler.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.util.withAppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** One Elder BIS piece with its craft recipe, material availability, and embedded sigil. */
data class ElderPieceRow(
    val key: String,
    val displayName: String,
    val slot: String,
    val materials: List<MaterialSlot>,
    val crafted: Boolean,
    val progress: Float,
    val canCraft: Boolean,
    /** Sigil stone item key currently embedded in this piece, or null if the socket is empty. */
    val embeddedSigil: String?,
) {
    data class MaterialSlot(val itemKey: String, val displayName: String, val required: Int, val owned: Int) {
        val met: Boolean get() = owned >= required
    }
}

/** A sigil stone type the player can embed. Effect text is display-only for now; wiring
 *  the actual gameplay bonuses is a follow-up slice. */
data class SigilStone(
    val itemKey: String,
    val displayName: String,
    val effect: String,
)

data class ElderArmorMasterState(
    val rows: List<ElderPieceRow> = emptyList(),
    /** Sigil stones the player owns, with per-stone counts pulled from inventory. */
    val sigilStones: List<SigilStoneRow> = emptyList(),
    val snackbarMessage: String? = null,
)

data class SigilStoneRow(val stone: SigilStone, val owned: Int)

@HiltViewModel
class ElderArmorMasterViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : ViewModel() {

    private val recipes = listOf(
        Recipe("elder_helm",        "Elder Helm",      "Head",
            listOf("starforged_bar" to 5, "starwood_log" to 3, "ancient_sigil" to 5)),
        Recipe("elder_platebody",   "Elder Platebody", "Body",
            listOf("starforged_bar" to 8, "starwood_log" to 4, "ancient_sigil" to 5)),
        Recipe("elder_platelegs",   "Elder Platelegs", "Legs",
            listOf("starforged_bar" to 7, "starwood_log" to 4, "ancient_sigil" to 5)),
        Recipe("elder_boots",       "Elder Boots",     "Boots",
            listOf("starforged_bar" to 4, "starwood_log" to 2, "ancient_sigil" to 5)),
        Recipe("elder_cape",        "Elder Cape",      "Cape",
            listOf("abyssal_log"    to 20, "starforged_bar" to 2, "ancient_sigil" to 5)),
        Recipe("elder_shield",      "Elder Shield",    "Shield",
            listOf("starforged_bar" to 6, "abyssal_log" to 4, "ancient_sigil" to 5)),
        Recipe("elder_signet_ring", "Elder Ring",      "Ring",
            listOf("starforged_bar" to 3, "elder_ruby"  to 1, "ancient_sigil" to 5)),
        Recipe("elder_amulet",      "Elder Amulet",    "Necklace",
            listOf("starforged_bar" to 3, "elder_sapphire" to 1, "ancient_sigil" to 5)),
    )

    private data class Recipe(val key: String, val displayName: String, val slot: String, val materials: List<Pair<String, Int>>)

    private val recipeByKey = recipes.associateBy { it.key }

    private val itemDisplayNames = mapOf(
        "starforged_bar" to "Starforged Bar",
        "starwood_log"   to "Starwood Log",
        "abyssal_log"    to "Abyssal Log",
        "ancient_sigil"  to "Ancient Sigil",
        "elder_ruby"     to "Elder Ruby",
        "elder_sapphire" to "Elder Sapphire",
    )

    /** Sigil stones the player can embed. Reuses the existing Elder gem items as stones. */
    private val sigilStones = listOf(
        SigilStone("elder_ruby",     "Ruby Sigil Stone",     "+5% coin drops"),
        SigilStone("elder_sapphire", "Sapphire Sigil Stone", "+5% XP gain"),
        SigilStone("elder_emerald",  "Emerald Sigil Stone",  "+5% loot chance"),
        SigilStone("elder_topaz",    "Topaz Sigil Stone",    "+5% Ancient Sigil drops"),
        SigilStone("elder_amethyst", "Amethyst Sigil Stone", "+5% Elder Essence gain"),
        SigilStone("elder_diamond",  "Diamond Sigil Stone",  "+5% Elder Bone drops"),
    )
    private val sigilStoneByKey = sigilStones.associateBy { it.itemKey }

    fun sigilStoneDisplayName(key: String): String = sigilStoneByKey[key]?.displayName ?: key
    fun sigilStoneEffect(key: String): String = sigilStoneByKey[key]?.effect ?: ""

    private val _snack = MutableStateFlow<String?>(null)

    val state: StateFlow<ElderArmorMasterState> = combine(
        playerRepo.playerFlow.map { p ->
            val inv: Map<String, Int> = if (p == null) emptyMap()
                else try { json.decodeFromString(p.inventory) } catch (_: Exception) { emptyMap() }
            val flags: PlayerFlags = if (p == null) PlayerFlags()
                else try { json.decodeFromString(p.flags) } catch (_: Exception) { PlayerFlags() }
            Triple(inv, flags.embeddedSigils, flags.elderQuestsCompleted /*placeholder to distinguish triple*/)
        },
        _snack,
    ) { (inv, embedded, _), snack ->
        val rows = recipes.map { r ->
            val mats = r.materials.map { (key, req) ->
                ElderPieceRow.MaterialSlot(
                    itemKey     = key,
                    displayName = itemDisplayNames[key] ?: key,
                    required    = req,
                    owned       = inv[key] ?: 0,
                )
            }
            val totalReq = mats.sumOf { it.required }
            val totalOwned = mats.sumOf { minOf(it.owned, it.required) }
            ElderPieceRow(
                key           = r.key,
                displayName   = r.displayName,
                slot          = r.slot,
                materials     = mats,
                crafted       = (inv[r.key] ?: 0) >= 1,
                progress      = if (totalReq == 0) 0f else totalOwned.toFloat() / totalReq,
                canCraft      = mats.all { it.met } && (inv[r.key] ?: 0) == 0,
                embeddedSigil = embedded[r.key],
            )
        }
        val stoneRows = sigilStones.map { s -> SigilStoneRow(s, inv[s.itemKey] ?: 0) }
        ElderArmorMasterState(rows = rows, sigilStones = stoneRows, snackbarMessage = snack)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ElderArmorMasterState())

    /** Immediate craft (button on the piece card). */
    fun craft(pieceKey: String) {
        viewModelScope.launch {
            val r = recipeByKey[pieceKey] ?: return@launch
            val cost = r.materials.associate { (k, q) -> k to q }
            val ok = playerRepo.consumeItems(cost)
            if (!ok) {
                _snack.value = context.withAppLocale().getString(R.string.elder_armor_missing_mats)
                return@launch
            }
            playerRepo.addItems(mapOf(pieceKey to 1))
            _snack.value = context.withAppLocale().getString(R.string.elder_armor_crafted, r.displayName)
        }
    }

    /**
     * Embed a sigil stone into an Elder piece. Consumes one stone from inventory, replaces
     * any previously-embedded stone (returning it to inventory).
     */
    fun embedSigil(pieceKey: String, stoneKey: String) {
        viewModelScope.launch {
            if (recipeByKey[pieceKey] == null) return@launch
            if (sigilStoneByKey[stoneKey] == null) return@launch
            val flags = playerRepo.getFlags()
            val consumed = playerRepo.consumeItems(mapOf(stoneKey to 1))
            if (!consumed) {
                _snack.value = context.withAppLocale().getString(R.string.elder_armor_missing_stone)
                return@launch
            }
            // Refund any previously embedded stone.
            val previous = flags.embeddedSigils[pieceKey]
            if (previous != null) {
                playerRepo.addItems(mapOf(previous to 1))
            }
            val next = flags.embeddedSigils.toMutableMap().apply { put(pieceKey, stoneKey) }
            playerRepo.updateFlags(flags.copy(embeddedSigils = next))
            _snack.value = context.withAppLocale().getString(
                R.string.elder_armor_stone_embedded,
                sigilStoneByKey[stoneKey]?.displayName ?: stoneKey,
                recipeByKey[pieceKey]?.displayName ?: pieceKey,
            )
        }
    }

    fun removeSigil(pieceKey: String) {
        viewModelScope.launch {
            val flags = playerRepo.getFlags()
            val previous = flags.embeddedSigils[pieceKey] ?: return@launch
            playerRepo.addItems(mapOf(previous to 1))
            val next = flags.embeddedSigils.toMutableMap().apply { remove(pieceKey) }
            playerRepo.updateFlags(flags.copy(embeddedSigils = next))
        }
    }

    fun snackbarConsumed() { _snack.value = null }
}
