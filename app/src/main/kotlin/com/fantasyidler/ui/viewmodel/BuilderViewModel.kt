package com.fantasyidler.ui.viewmodel

import com.fantasyidler.util.withAppLocale

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.Skills
import com.fantasyidler.repository.BoostRepository
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.TownRepository
import com.fantasyidler.repository.UpgradeBuildingResult
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

data class BuilderUiState(
    val isLoading: Boolean = true,
    val constructionLevel: Int = 1,
    val extraDiscountPerMille: Int = 0,
    val coins: Long = 0L,
    val inventory: Map<String, Int> = emptyMap(),
    val innTier: Int = 0,
    val guildHallTier: Int = 0,
    val churchTier: Int = 0,
    val fairgroundsTier: Int = 0,
    val gardenTier: Int = 0,
    val queueMasterTier: Int = 0,
    val capeRackTier: Int = 0,
    val artisansWorkshopTier: Int = 0,
    val chronosSpireTier: Int = 0,
    val dockTier: Int = 0,
    val snackbarMessage: String? = null,
    /** True after the Dock is first built (tier 0 → 1), until the player dismisses the
     *  Sea Serpent lore prompt. Consumed only via [BuilderViewModel.dismissDockUnlockedDialog]. */
    val showDockUnlockedDialog: Boolean = false,
)

@HiltViewModel
class BuilderViewModel @Inject constructor(
    val gameData: GameDataRepository,
    val townRepo: TownRepository,
    private val boostRepo: BoostRepository,
    private val playerRepo: PlayerRepository,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : ViewModel() {

    private val _extra = MutableStateFlow(BuilderUiState())

    val uiState: StateFlow<BuilderUiState> = combine(
        playerRepo.playerFlow,
        _extra,
    ) { player, extra ->
        if (player == null) return@combine extra.copy(isLoading = true)
        val flags: PlayerFlags          = json.decodeFromString(player.flags)
        val levels: Map<String, Int>    = json.decodeFromString(player.skillLevels)
        val inventory: Map<String, Int> = json.decodeFromString(player.inventory)
        extra.copy(
            isLoading         = false,
            constructionLevel = levels[Skills.CONSTRUCTION] ?: 1,
            extraDiscountPerMille = boostRepo.builderDiscountPerMille(flags),
            coins             = player.coins,
            inventory         = inventory,
            innTier           = flags.townBuildingTiers["inn"] ?: 0,
            guildHallTier     = flags.townBuildingTiers["guild_hall"] ?: 0,
            churchTier        = flags.townBuildingTiers["church"] ?: 0,
            fairgroundsTier   = flags.townBuildingTiers["fairgrounds"] ?: 0,
            gardenTier        = flags.townBuildingTiers["garden"] ?: 0,
            queueMasterTier   = flags.townBuildingTiers["queue_master"] ?: 0,
            capeRackTier      = flags.townBuildingTiers["cape_rack"] ?: 0,
            artisansWorkshopTier = flags.townBuildingTiers["artisans_workshop"] ?: 0,
            chronosSpireTier  = flags.townBuildingTiers["chronos_spire"] ?: 0,
            dockTier          = flags.townBuildingTiers["dock"] ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BuilderUiState())

    fun upgrade(buildingKey: String) {
        viewModelScope.launch {
            val currentTier = when (buildingKey) {
                "inn"               -> uiState.value.innTier
                "guild_hall"        -> uiState.value.guildHallTier
                "fairgrounds"       -> uiState.value.fairgroundsTier
                "garden"            -> uiState.value.gardenTier
                "queue_master"      -> uiState.value.queueMasterTier
                "cape_rack"         -> uiState.value.capeRackTier
                "artisans_workshop" -> uiState.value.artisansWorkshopTier
                "chronos_spire"     -> uiState.value.chronosSpireTier
                "dock"              -> uiState.value.dockTier
                else                -> uiState.value.churchTier
            }
            val def = gameData.townBuildings[buildingKey]
            when (townRepo.upgradeBuilding(buildingKey)) {
                UpgradeBuildingResult.Success ->
                    _extra.update { it.copy(
                        snackbarMessage = context.withAppLocale().getString(R.string.town_upgrade_success),
                        // First-time Dock build fires the Sea Serpent lore prompt so the
                        // gate to Elder Isle is spelled out at the moment the Dock unlocks.
                        showDockUnlockedDialog = it.showDockUnlockedDialog || (buildingKey == "dock" && currentTier == 0),
                    ) }
                UpgradeBuildingResult.InsufficientLevel -> {
                    val req = def?.tiers?.getOrNull(currentTier)?.constructionLevelRequired ?: 0
                    _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.town_upgrade_fail_level, req)) }
                }
                UpgradeBuildingResult.InsufficientCoins ->
                    _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.town_upgrade_fail_coins)) }
                UpgradeBuildingResult.InsufficientMaterials ->
                    _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.town_upgrade_fail_mats)) }
                else -> {}
            }
        }
    }

    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }

    fun dismissDockUnlockedDialog() = _extra.update { it.copy(showDockUnlockedDialog = false) }
}
