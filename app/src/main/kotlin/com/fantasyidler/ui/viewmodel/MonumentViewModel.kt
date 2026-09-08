package com.fantasyidler.ui.viewmodel

import com.fantasyidler.util.withAppLocale

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.repository.MonumentRepository
import com.fantasyidler.repository.MonumentTouchResult
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.util.GameStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class MonumentUiState(
    val isLoading: Boolean = true,
    /** Completed stage, 0-5. */
    val tier: Int = 0,
    /** Coins contributed toward the stage-5 Eternal Flame. */
    val fund: Long = 0L,
    val coins: Long = 0L,
    val touchedToday: Boolean = false,
    val snackbarMessage: String? = null,
)

@HiltViewModel
class MonumentViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepo: PlayerRepository,
    private val monumentRepo: MonumentRepository,
    private val json: Json,
) : ViewModel() {

    private val _extra = MutableStateFlow(MonumentUiState())

    val uiState: StateFlow<MonumentUiState> = combine(
        playerRepo.playerFlow,
        _extra,
    ) { player, extra ->
        if (player == null) {
            extra.copy(isLoading = true)
        } else {
            val flags: PlayerFlags = try { json.decodeFromString(player.flags) } catch (_: Exception) { PlayerFlags() }
            extra.copy(
                isLoading    = false,
                tier         = flags.monumentTier,
                fund         = flags.monumentFund,
                coins        = player.coins,
                touchedToday = monumentRepo.touchedToday(flags),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonumentUiState())

    fun purchaseNextStage() {
        viewModelScope.launch {
            val message = if (monumentRepo.purchaseNextStage()) {
                if (playerRepo.getFlags().monumentTier == 4) R.string.monument_goose_joined
                else R.string.monument_stage_built
            } else R.string.monument_not_enough_coins
            _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(message)) }
        }
    }

    fun contribute(amount: Long) {
        viewModelScope.launch {
            val before = playerRepo.getFlags().monumentTier
            val message = if (monumentRepo.contributeToFlame(amount)) {
                if (playerRepo.getFlags().monumentTier >= 5 && before < 5) R.string.monument_flame_lit
                else R.string.monument_contributed
            } else R.string.monument_not_enough_coins
            _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(message)) }
        }
    }

    fun debugForceTouchMonument() {
        viewModelScope.launch {
            val flags = playerRepo.getFlagsUnlocked()
            playerRepo.updateFlagsUnlocked(flags.copy(
                monumentTouchDay        = -1,
            ))
            touchMonument()
        }
    }

    fun touchMonument() {
        viewModelScope.launch {
            val message = when (val result = monumentRepo.touchMonument()) {
                is MonumentTouchResult.Blessing -> context.withAppLocale().getString(R.string.monument_touch_blessing)
                is MonumentTouchResult.BlessingExtended -> context.withAppLocale().getString(R.string.monument_touch_blessing_extended)
                is MonumentTouchResult.Items    -> context.withAppLocale().getString(
                    R.string.monument_touch_items,
                    result.items.entries.joinToString { (key, qty) -> "$qty× ${GameStrings.itemName(context, key)}" },
                )
                MonumentTouchResult.AlreadyTouchedToday -> context.withAppLocale().getString(R.string.monument_touched_today)
                MonumentTouchResult.NotUnlocked         -> return@launch
            }
            _extra.update { it.copy(snackbarMessage = message) }
        }
    }

    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }
}
