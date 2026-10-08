package com.fantasyidler.ui.viewmodel

import com.fantasyidler.util.withAppLocale

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.model.ElderActivities
import com.fantasyidler.data.model.HiredWorker
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.WorkerTier
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SessionRepository
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
import java.util.Calendar
import javax.inject.Inject
import kotlin.random.Random

data class DailyFoodItem(
    val key: String,
    val displayName: String,
    val healValue: Int,
    val price: Int,
)

data class InnUiState(
    val isLoading: Boolean = true,
    val coins: Long = 0L,
    val hiredWorker: HiredWorker? = null,
    val hiredWorker2: HiredWorker? = null,
    val longLaborerName: String = "",
    val apprenticeName: String = "",
    val journeymanName: String = "",
    val masterName: String = "",
    val dailyFoods: List<DailyFoodItem> = emptyList(),
    val dailyResetHour: Int = 6,
    val snackbarMessage: String? = null,
    val navigateToWorkerSkillsSlot: Int = 0,
    /** Ironman characters cannot hire workers or buy the daily food. */
    val ironman: Boolean = false,
)

@HiltViewModel
class InnViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val sessionRepo: SessionRepository,
    private val gameData: GameDataRepository,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : ViewModel() {

    private val _extra = MutableStateFlow(InnUiState())

    val uiState: StateFlow<InnUiState> = combine(
        playerRepo.playerFlow,
        _extra,
    ) { player, extra ->
        if (player == null) extra.copy(isLoading = true)
        else {
            val flags: PlayerFlags = json.decodeFromString(player.flags)
            extra.copy(
                isLoading       = false,
                coins           = player.coins,
                hiredWorker     = flags.hiredWorker,
                hiredWorker2    = flags.hiredWorker2,
                longLaborerName = workerName(WorkerTier.LONG_LABORER, flags.dailyResetHour),
                apprenticeName  = workerName(WorkerTier.APPRENTICE, flags.dailyResetHour),
                journeymanName  = workerName(WorkerTier.JOURNEYMAN, flags.dailyResetHour),
                masterName      = workerName(WorkerTier.MASTER, flags.dailyResetHour),
                dailyFoods      = computeDailyFoods(flags.dailyResetHour, flags.elderIsleUnlocked),
                dailyResetHour  = flags.dailyResetHour,
                ironman         = flags.ironman,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InnUiState())

    fun hire(tier: WorkerTier) {
        viewModelScope.launch {
            val flags = playerRepo.getFlags()
            if (flags.ironman) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.ironman_inn_blocked)) }
                return@launch
            }
            val slot = if (tier == WorkerTier.LONG_LABORER) 1 else 2

            val slotOccupied = if (slot == 1) flags.hiredWorker != null else flags.hiredWorker2 != null
            if (slotOccupied) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.inn_worker_already_hired)) }
                return@launch
            }
            val otherName = if (slot == 1) flags.hiredWorker2?.dailyName else flags.hiredWorker?.dailyName
            val name = uniqueWorkerName(tier, otherName, flags.dailyResetHour)
            if (!playerRepo.spendCoins(tier.hireCost)) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.inn_not_enough_coins)) }
                return@launch
            }
            sessionRepo.deleteAllWorkerSessions(slot)
            val newFlags = if (slot == 1) flags.copy(hiredWorker = HiredWorker(tier, name))
                           else flags.copy(hiredWorker2 = HiredWorker(tier, name))
            playerRepo.updateFlags(newFlags)
            _extra.update { it.copy(navigateToWorkerSkillsSlot = slot) }
        }
    }

    fun buyFood(key: String, price: Int, qty: Int = 1) {
        viewModelScope.launch {
            if (playerRepo.getFlags().ironman) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.ironman_shop_buy_blocked)) }
                return@launch
            }
            val success = playerRepo.buyItem(key, qty, price)
            _extra.update {
                it.copy(
                    snackbarMessage = if (success) context.withAppLocale().getString(R.string.inn_food_purchased)
                                      else context.withAppLocale().getString(R.string.inn_not_enough_coins)
                )
            }
        }
    }

    fun navigationHandled() = _extra.update { it.copy(navigateToWorkerSkillsSlot = 0) }
    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }

    private fun workerName(tier: WorkerTier, resetHour: Int): String {
        val names = context.resources.getStringArray(R.array.worker_names)
        if (names.isEmpty()) return "Worker"
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < resetHour) cal.add(Calendar.DAY_OF_YEAR, -1)
        val daySeed = cal.get(Calendar.YEAR) * 10000L + cal.get(Calendar.MONTH) * 100 + cal.get(Calendar.DAY_OF_MONTH)
        val rng = Random(daySeed + tier.ordinal * 7919L)
        return names[rng.nextInt(names.size)]
    }

    private fun uniqueWorkerName(tier: WorkerTier, exclude: String?, resetHour: Int): String {
        val base = workerName(tier, resetHour)
        if (exclude == null || base != exclude) return base
        val names = context.resources.getStringArray(R.array.worker_names)
        if (names.isEmpty()) return "Worker"
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < resetHour) cal.add(Calendar.DAY_OF_YEAR, -1)
        val daySeed = cal.get(Calendar.YEAR) * 10000L + cal.get(Calendar.MONTH) * 100 + cal.get(Calendar.DAY_OF_MONTH)
        for (offset in 1..names.size) {
            val candidate = names[Random(daySeed + tier.ordinal * 7919L + offset).nextInt(names.size)]
            if (candidate != exclude) return candidate
        }
        return base
    }

    private fun computeDailyFoods(resetHour: Int, elderIsleUnlocked: Boolean): List<DailyFoodItem> {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < resetHour) cal.add(Calendar.DAY_OF_YEAR, -1)
        val seed = cal.get(Calendar.YEAR) * 10000L + cal.get(Calendar.MONTH) * 100 + cal.get(Calendar.DAY_OF_MONTH) + 54321L
        val rng = Random(seed)

        val elderCookedItems = ElderActivities.COOKING.map { it.key }.toSet()
        val recipes = gameData.cookingRecipes.values
            .filter { elderIsleUnlocked || it.cookedItem !in elderCookedItems }
        val small  = recipes.filter { it.healingValue in 1..7 }
        val medium = recipes.filter { it.healingValue in 8..14 }
        val large  = recipes.filter { it.healingValue >= 15 }

        return listOfNotNull(
            small.randomOrNull(rng)?.let  { DailyFoodItem(it.cookedItem, it.displayName, it.healingValue, 100) },
            medium.randomOrNull(rng)?.let { DailyFoodItem(it.cookedItem, it.displayName, it.healingValue, 250) },
            large.randomOrNull(rng)?.let  { DailyFoodItem(it.cookedItem, it.displayName, it.healingValue, 500) },
        )
    }
}
