package com.fantasyidler.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.data.json.PrestigeSkillTreeData
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.repository.BoostRepository
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.PrestigeActionResult
import com.fantasyidler.simulator.PrestigeBoosts
import com.fantasyidler.simulator.PrestigePoints
import com.fantasyidler.simulator.XpTable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Display state of one tree node. */
data class PrestigeNodeUi(
    val id: String,
    val cost: Int,
    val effect: String,
    val value: Double,
    val races: List<String>?,
    val tier: Int,
    val owned: Boolean,
    val raceLocked: Boolean,
    val prereqLocked: Boolean,
    val affordable: Boolean,
    /** Recipe key this node unlocks (unlock_recipe effect only). */
    val unlock: String? = null,
    /** Node on an auto path: earned by prestiging, never bought. */
    val auto: Boolean = false,
)

data class PrestigePathUi(
    val key: String,
    val nodes: List<PrestigeNodeUi>,
    val auto: Boolean = false,
)

@HiltViewModel
class PrestigeDetailViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val gameData: GameDataRepository,
    private val boostRepo: BoostRepository,
    private val json: Json,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val skill: String = savedStateHandle["skill"] ?: ""

    data class UiState(
        val isLoading: Boolean = true,
        val ironman: Boolean = false,
        val level: Int = 1,
        val xp: Long = 0L,
        val prestigeCount: Int = 0,
        val unspentPoints: Int = 0,
        val earnedPoints: Int = 0,
        val pointCap: Int = 0,
        val playerRace: String = "human",
        val paths: List<PrestigePathUi> = emptyList(),
        /** Points a prestige would award right now (0 = below level 99). */
        val pointsOnPrestige: Int = 0,
        /** Ms until respec is allowed again; 0 = allowed now. */
        val respecCooldownMs: Long = 0L,
        val hasPurchasedNodes: Boolean = false,
        /** False once nothing is left: points at cap and every auto XP tier earned. */
        val canPrestigeMore: Boolean = true,
        val snackbarMessage: String? = null,
    )

    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<UiState> = playerRepo.playerFlow
        .combine(_message) { player, message ->
            if (player == null) return@combine UiState()
            val flags: PlayerFlags = json.decodeFromString(player.flags)
            val levels: Map<String, Int> = json.decodeFromString(player.skillLevels)
            val xpMap: Map<String, Long> = json.decodeFromString(player.skillXp)
            val tree: PrestigeSkillTreeData? = gameData.prestigeTrees[skill]
            val race = PrestigeBoosts.playerRace(flags)
            val owned = flags.prestigeNodes[skill].orEmpty().toSet()
            val unspent = PrestigeBoosts.unspentPoints(gameData.prestigeTrees, flags, skill)
            val earned = flags.prestigePointsEarned[skill] ?: 0
            val cap = PrestigeBoosts.pointCapForRace(tree, race, flags.ironman)
            val prestigeCount = flags.skillPrestige[skill] ?: 0
            val xp = xpMap[skill] ?: 0L
            val now = System.currentTimeMillis()
            val paths = tree?.paths.orEmpty().map { path ->
                var prevOwnedForRace = true
                PrestigePathUi(
                    key = path.key,
                    auto = path.auto,
                    nodes = path.nodes.mapIndexed { index, node ->
                        if (path.auto) {
                            PrestigeNodeUi(
                                id           = node.id,
                                cost         = node.cost,
                                effect       = node.effect,
                                value        = node.value,
                                races        = node.races,
                                tier         = index + 1,
                                owned        = index < prestigeCount,
                                raceLocked   = false,
                                prereqLocked = false,
                                affordable   = false,
                                unlock       = node.unlock,
                                auto         = true,
                            )
                        } else {
                            val raceOk = PrestigeBoosts.isNodeAvailableToRace(node, race)
                            val prereqLocked = raceOk && !prevOwnedForRace && node.id !in owned
                            val ui = PrestigeNodeUi(
                                id           = node.id,
                                cost         = node.cost,
                                effect       = node.effect,
                                value        = node.value,
                                races        = node.races,
                                tier         = index + 1,
                                owned        = node.id in owned,
                                raceLocked   = !raceOk,
                                prereqLocked = prereqLocked,
                                affordable   = unspent >= node.cost,
                                unlock       = node.unlock,
                            )
                            if (raceOk) prevOwnedForRace = node.id in owned
                            ui
                        }
                    },
                )
            }
            val purchasableIds = tree?.paths.orEmpty()
                .filterNot { it.auto }.flatMap { it.nodes }.mapTo(mutableSetOf()) { it.id }
            UiState(
                isLoading           = false,
                ironman             = flags.ironman,
                level               = levels[skill] ?: 1,
                xp                  = xp,
                prestigeCount       = prestigeCount,
                unspentPoints       = unspent,
                earnedPoints        = earned,
                pointCap            = cap,
                playerRace          = race,
                paths               = paths,
                pointsOnPrestige    = PrestigePoints.pointsForXp(xp)
                    .let { if (cap > 0) it.coerceAtMost((cap - earned).coerceAtLeast(0)) else it },
                respecCooldownMs    = ((flags.prestigeLastRespecAt[skill] ?: 0L) +
                    PlayerRepository.PRESTIGE_RESPEC_COOLDOWN_MS - now).coerceAtLeast(0L),
                hasPurchasedNodes   = owned.any { it in purchasableIds },
                canPrestigeMore     = PrestigeBoosts.prestigeHasReward(gameData.prestigeTrees, flags, skill),
                snackbarMessage     = message,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    fun prestige() {
        viewModelScope.launch { playerRepo.prestigeSkill(skill) }
    }

    fun purchase(nodeId: String, onResult: (PrestigeActionResult) -> Unit = {}) {
        viewModelScope.launch { onResult(playerRepo.purchasePrestigeNode(skill, nodeId)) }
    }

    fun respec(onResult: (PrestigeActionResult) -> Unit = {}) {
        viewModelScope.launch { onResult(playerRepo.respecPrestige(skill)) }
    }

    fun snackbarConsumed() { _message.value = null }

    /** Level-99 XP requirement, for the header progress line. */
    val level99Xp: Long = XpTable.xpForLevel(99)
}
