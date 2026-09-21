package com.fantasyidler.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SessionRepository
import com.fantasyidler.util.withAppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Shared VM used by the Skills / Combat / Quests / Profile screens to know whether the
 * player is currently on the Elder Isle and to flip the location from the isle stubs.
 * The Home tab has its own toggle path (HomeViewModel.toggleElderIsleLocation); this VM
 * exists so the other four tabs don't each have to plumb the flag through their own state.
 */
data class ElderIsleLocationState(
    val onElderIsle: Boolean = false,
    val snackbarMessage: String? = null,
)

@HiltViewModel
class ElderIsleLocationViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val sessionRepo: SessionRepository,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : ViewModel() {

    private val _extra = MutableStateFlow(ElderIsleLocationState())

    val state: StateFlow<ElderIsleLocationState> = combine(
        playerRepo.playerFlow.map { p ->
            if (p == null) false else try {
                json.decodeFromString<PlayerFlags>(p.flags).onElderIsle
            } catch (_: Exception) { false }
        },
        _extra,
    ) { onIsle, extra ->
        extra.copy(onElderIsle = onIsle)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ElderIsleLocationState())

    fun returnToMainland() {
        viewModelScope.launch {
            if (sessionRepo.getActiveSession() != null) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.elder_isle_sail_blocked_by_session)) }
                return@launch
            }
            val flags = playerRepo.getFlags()
            if (flags.onElderIsle) playerRepo.updateFlags(flags.copy(onElderIsle = false))
        }
    }

    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }
}
