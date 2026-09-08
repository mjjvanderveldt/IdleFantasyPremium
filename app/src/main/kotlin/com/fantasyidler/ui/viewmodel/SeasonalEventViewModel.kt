package com.fantasyidler.ui.viewmodel

import com.fantasyidler.util.withAppLocale

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.json.SeasonalEventData
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SeasonalBountyTaskWithProgress
import com.fantasyidler.repository.SeasonalEventRepository
import com.fantasyidler.repository.SeasonalMinigameResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class SeasonalEventUiState(
    val isLoading: Boolean = true,
    val event: SeasonalEventData? = null,
    val tokens: Int = 0,
    val bountyTasks: List<SeasonalBountyTaskWithProgress> = emptyList(),
    val minigameCooldownAt: Long = 0L,
    val minigameEasyMode: Boolean = false,
    val coins: Long = 0L,
    /** Token thresholds of reward tiers already claimed for the active event. */
    val claimedRewardTiers: List<Int> = emptyList(),
    /** Night Market offer id -> purchases made for the active event. */
    val marketPurchases: Map<String, Int> = emptyMap(),
    val snackbarMessage: String? = null,
)

@HiltViewModel
class SeasonalEventViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepo: PlayerRepository,
    private val gameData: GameDataRepository,
    private val seasonalEventRepo: SeasonalEventRepository,
    private val json: Json,
) : ViewModel() {

    private val _extra = MutableStateFlow(SeasonalEventUiState())

    init {
        viewModelScope.launch { seasonalEventRepo.ensureBountySlotsRefreshed() }
    }

    val uiState: StateFlow<SeasonalEventUiState> = combine(
        playerRepo.playerFlow,
        _extra,
    ) { player, extra ->
        val event = seasonalEventRepo.activeEvent()
        if (player == null || event == null) {
            extra.copy(isLoading = false, event = null)
        } else {
            val flags: PlayerFlags = try { json.decodeFromString(player.flags) } catch (_: Exception) { PlayerFlags() }
            val inventory: Map<String, Int> = try { json.decodeFromString(player.inventory) } catch (_: Exception) { emptyMap() }
            extra.copy(
                isLoading          = false,
                event              = event,
                tokens             = flags.seasonalTokensByEvent[event.id] ?: 0,
                bountyTasks        = seasonalEventRepo.bountyTasksWithProgress(event, flags, inventory),
                minigameCooldownAt = flags.seasonalMinigameCooldownAt,
                minigameEasyMode   = flags.seasonalMinigameEasyMode,
                coins              = player.coins,
                claimedRewardTiers = flags.seasonalRewardTiersClaimed[event.id].orEmpty(),
                marketPurchases    = event.nightMarket.associate { offer ->
                    offer.id to (flags.seasonalMarketPurchases["${event.id}:${offer.id}"] ?: 0)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeasonalEventUiState())

    fun claimBountyTask(taskId: String) {
        viewModelScope.launch { seasonalEventRepo.claimBountyTask(taskId) }
    }

    fun rerollBountyTask(taskId: String) {
        viewModelScope.launch {
            if (seasonalEventRepo.rerollBountyTask(taskId) == SeasonalEventRepository.RerollResult.NOT_ENOUGH_COINS) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.error_not_enough_coins)) }
            }
        }
    }

    /** Expedition dungeon that spawns [enemyKey], falling back to the event's first expedition. */
    fun expeditionKeyForKillTarget(event: SeasonalEventData, enemyKey: String): String? {
        val keys = event.expeditionKeys()
        return keys.firstOrNull { key -> gameData.dungeons[key]?.enemySpawns?.any { it.enemy == enemyKey } == true }
            ?: keys.firstOrNull()
    }

    fun claimRewardTier(tierTokens: Int) {
        viewModelScope.launch {
            if (seasonalEventRepo.claimRewardTier(tierTokens)) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.seasonal_reward_claim_success)) }
            }
        }
    }

    fun purchaseMarketOffer(offerId: String) {
        viewModelScope.launch {
            if (seasonalEventRepo.purchaseMarketOffer(offerId)) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.seasonal_market_purchase_success)) }
            }
        }
    }

    fun debugStopMinigameCooldown() {
        viewModelScope.launch {
            playerRepo.updateFlags(playerRepo.getFlags().copy(seasonalMinigameCooldownAt = 0L))
        }
    }

    fun setMinigameEasyMode(enabled: Boolean) {
        viewModelScope.launch {
            playerRepo.updateFlags(playerRepo.getFlags().copy(seasonalMinigameEasyMode = enabled))
        }
    }

    /** Called by the Bounty Board's per-slot countdown once it reaches zero, so the new task appears live. */
    fun refreshBountySlots() {
        viewModelScope.launch { seasonalEventRepo.ensureBountySlotsRefreshed() }
    }

    /** [won] is whether the player landed enough hits during the whack-a-mole rounds. */
    fun submitMinigameAttempt(won: Boolean) {
        viewModelScope.launch {
            // The shared success line ("Perfect timing!") reads as leftover from the whack
            // game when the event runs the memory game instead (issue #1734).
            val successRes = if (seasonalEventRepo.activeEvent()?.minigame?.type == "sequence")
                R.string.seasonal_minigame_sequence_success else R.string.seasonal_minigame_success
            when (seasonalEventRepo.submitMinigameAttempt(won)) {
                is SeasonalMinigameResult.Success -> _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(successRes)) }
                is SeasonalMinigameResult.Failure -> _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.seasonal_minigame_failure)) }
                is SeasonalMinigameResult.OnCooldown, SeasonalMinigameResult.NoActiveEvent -> {}
            }
        }
    }

    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }
}
