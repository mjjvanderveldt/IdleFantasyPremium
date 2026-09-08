package com.fantasyidler.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.data.json.BossData
import com.fantasyidler.data.json.EnemyData
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.util.GameStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import javax.inject.Inject

enum class BestiarySort { ALPHABETICAL, BY_LOCATION }

data class BestiaryEntry(
    val key: String,
    val nameLoader: (Context, String) -> String,
    val killCount: Int,
    val locations: List<String>,
    val enemy: EnemyData? = null,
    val boss: BossData? = null,
) {
    val encountered: Boolean get() = killCount > 0
}

data class BestiaryUiState(
    val enemies: List<BestiaryEntry> = emptyList(),
    val bosses: List<BestiaryEntry> = emptyList(),
    val sort: BestiarySort = BestiarySort.ALPHABETICAL,
)

@HiltViewModel
class BestiaryViewModel @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val gameData: GameDataRepository,
    private val json: Json,
) : ViewModel() {

    private val _sort = MutableStateFlow(BestiarySort.ALPHABETICAL)

    val uiState: StateFlow<BestiaryUiState> = combine(
        playerRepo.playerFlow,
        _sort,
    ) { player, sort ->
        val flags: PlayerFlags = if (player != null)
            json.decodeFromString(player.flags) else PlayerFlags()
        val kills = flags.enemyKills

        val enemies = gameData.enemies
            .map { (key, enemy) ->
                BestiaryEntry(
                    key         = key,
                    nameLoader  = GameStrings::enemyName,
                    killCount   = kills[key] ?: 0,
                    locations   = gameData.enemyLocations[key] ?: emptyList(),
                    enemy       = enemy,
                )
            }.sortedBy { it.key }

        val bosses = gameData.bosses
            .map { (key, boss) ->
                BestiaryEntry(
                    key         = key,
                    nameLoader  = GameStrings::bossName,
                    killCount   = kills[key] ?: 0,
                    locations   = emptyList(),
                    boss        = boss,
                )
            }.sortedBy { it.key }

        BestiaryUiState(enemies = enemies, bosses = bosses, sort = sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BestiaryUiState())

    fun setSort(sort: BestiarySort) { _sort.value = sort }
}
