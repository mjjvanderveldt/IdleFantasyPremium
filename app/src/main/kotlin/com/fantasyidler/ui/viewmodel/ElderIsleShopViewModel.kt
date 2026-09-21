package com.fantasyidler.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.util.withAppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Isle Shop — spend Elder Essence on isle unlocks and bundles. Simple item-swap for now
 * (essence → item). Zone fast-travel and recipe-unlock entries would need dedicated
 * flags to persist, so they show as "coming later" but the item bundles buy immediately.
 */
data class ElderIsleShopState(
    val essence: Int = 0,
    val snackbarMessage: String? = null,
)

@HiltViewModel
class ElderIsleShopViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : ViewModel() {

    private val _extra = MutableStateFlow(ElderIsleShopState())

    val state: StateFlow<ElderIsleShopState> = combine(
        playerRepo.playerFlow.map { p ->
            if (p == null) 0
            else try {
                (json.decodeFromString<Map<String, Int>>(p.inventory)["elder_essence"] ?: 0)
            } catch (_: Exception) { 0 }
        },
        _extra,
    ) { essence, extra -> extra.copy(essence = essence) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ElderIsleShopState())

    /** Spend [cost] essence, receive [reward] items. All-or-nothing. */
    fun buyBundle(cost: Int, reward: Map<String, Int>, label: String) {
        viewModelScope.launch {
            val ok = playerRepo.consumeItems(mapOf("elder_essence" to cost))
            if (!ok) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.elder_isle_shop_insufficient)) }
                return@launch
            }
            playerRepo.addItems(reward)
            _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.elder_isle_shop_purchased, label)) }
        }
    }

    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }
}
