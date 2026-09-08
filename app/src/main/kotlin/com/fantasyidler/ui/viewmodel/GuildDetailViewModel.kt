package com.fantasyidler.ui.viewmodel

import com.fantasyidler.util.withAppLocale

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.GuildDailyWithProgress
import com.fantasyidler.repository.GuildQuestClaimResult
import com.fantasyidler.repository.GuildQuestWithProgress
import com.fantasyidler.repository.GuildRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.util.formatCoins
import com.fantasyidler.util.formatXp
import com.fantasyidler.util.toTitleCase
import com.fantasyidler.util.xpMultiplierBreakdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import android.content.Context
import com.fantasyidler.R
import com.fantasyidler.util.GameStrings
import dagger.hilt.android.qualifiers.ApplicationContext

data class GuildDetailUiState(
    val isLoading: Boolean = true,
    val guildKey: String = "",
    val guildLevel: Int = 0,
    val dailiesCompletedThisTier: Int = 0,
    val dailiesRequiredThisTier: Int = 1,
    val quests: List<GuildQuestWithProgress> = emptyList(),
    val dailies: List<GuildDailyWithProgress> = emptyList(),
    val nextResetMs: Long = 0L,
    val dailyResetHour: Int = 6,
    val questGateBlocked: Boolean = false,
    val guildUnlocked: Boolean = false,
    val snackbarMessage: String? = null,
    val inventory: Map<String, Int> = emptyMap(),
    val hideCompleted: Boolean = false,
)

@HiltViewModel
class GuildDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val playerRepo: PlayerRepository,
    private val guildRepo: GuildRepository,
    private val gameData: GameDataRepository,
    private val json: Json,
) : ViewModel() {

    val guild: String = savedStateHandle["guild"] ?: ""

    private val _extra = MutableStateFlow(GuildDetailUiState(guildKey = guild))

    init {
        viewModelScope.launch {
            guildRepo.ensureGuildDailiesRefreshed()
            val resetHour = playerRepo.getFlags().dailyResetHour
            _extra.update { it.copy(nextResetMs = guildRepo.nextResetMs(resetHour = resetHour), dailyResetHour = resetHour) }
        }
    }

    val uiState: StateFlow<GuildDetailUiState> = combine(
        playerRepo.playerFlow,
        guildRepo.observeQuestProgress(),
        _extra,
    ) { player, progressList, extra ->
        if (player == null) return@combine extra

        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val inventory: Map<String, Int> = json.decodeFromString(player.inventory)
        val completedQuestIds = progressList.filter { it.completed }.map { it.questId }.toSet()
        val level = guildRepo.guildLevel(guild, flags.guildDailyTierCounts, completedQuestIds)
        val dailiesCompletedThisTier = if (level >= GuildRepository.DAILIES_REQUIRED_PER_TIER.size) 0
            else flags.guildDailyTierCounts["$guild:$level"] ?: 0
        val dailiesRequiredThisTier = GuildRepository.DAILIES_REQUIRED_PER_TIER.getOrElse(level) { 1 }

        val progressMap = progressList.associateBy { it.questId }
        val quests = gameData.guildQuests.values
            .filter { it.guild == guild }
            .sortedBy { it.guildLevelRequired }
            .map { quest ->
                val row = progressMap[quest.id]
                GuildQuestWithProgress(
                    quest           = quest,
                    progress        = row?.progress ?: 0,
                    completed       = row?.completed ?: false,
                    effectiveAmount = guildRepo.effectiveQuestAmountFromFlags(quest, flags),
                )
            }

        val dailies = guildRepo.getGuildDailiesWithProgress(guild, flags)

        val tierQuests = gameData.guildQuests.values.filter { it.guild == guild && it.guildLevelRequired == level }
        val guildUnlocked = gameData.guildQuests.values.any { it.guild == guild && it.id in completedQuestIds }
        val questGateBlocked = guildUnlocked && level < 10 && tierQuests.isNotEmpty() && tierQuests.any { it.id !in completedQuestIds }

        extra.copy(
            isLoading                 = false,
            guildKey                  = guild,
            guildLevel                = level,
            dailiesCompletedThisTier  = dailiesCompletedThisTier,
            dailiesRequiredThisTier   = dailiesRequiredThisTier,
            quests                    = quests,
            dailies                   = dailies,
            questGateBlocked          = questGateBlocked,
            guildUnlocked             = guildUnlocked,
            inventory                 = inventory,
            hideCompleted             = flags.hideCompletedQuests,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuildDetailUiState(guildKey = guild))

    fun claimGuildQuest(questId: String) {
        viewModelScope.launch {
            when (val result = guildRepo.claimGuildQuestReward(questId)) {
                is GuildQuestClaimResult.Success -> {
                    val rewards = result.rewards
                    var xpSuffix = ""
                    var finalXp = 0L
                    if (rewards.xp > 0 && rewards.xpSkill.isNotBlank()) {
                        val b = playerRepo.previewFlatXpGrant(rewards.xpSkill, rewards.xp.toLong())
                        finalXp = b.finalXp
                        xpSuffix = xpMultiplierBreakdown(b.baseXp, b.boostFactor, b.blessingMult, b.prestigeXpPct)?.let { " $it" } ?: ""
                        playerRepo.applySessionResults(rewards.xpSkill, rewards.xp.toLong(), rewards.items)
                    } else if (rewards.items.isNotEmpty()) {
                        playerRepo.addItems(rewards.items)
                    }
                    if (rewards.coins > 0) playerRepo.addCoins(rewards.coins.toLong())
                    guildRepo.ensureGuildDailiesRefreshed()
                    val questName = GameStrings.questName(context, questId, gameData.guildQuests[questId]?.name ?: questId)
                    val parts = buildList {
                        if (rewards.xp > 0) add("+${finalXp.formatXp()} XP$xpSuffix")
                        if (rewards.coins > 0) add(context.withAppLocale().getString(R.string.reward_part_coins, rewards.coins.toLong().formatCoins()))
                        rewards.items.forEach { (key, qty) -> add("${GameStrings.itemName(context, key)} x$qty") }
                    }
                    val suffix = if (parts.isNotEmpty()) " (${parts.joinToString(", ")})" else ""
                    _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.guild_quest_complete, questName) + suffix) }
                }
                else -> Unit
            }
        }
    }

    fun claimGuildDaily(templateId: String) {
        viewModelScope.launch {
            val rewards = guildRepo.claimGuildDaily(templateId) ?: return@launch
            playerRepo.recordWeeklyProgress("guild_daily", "any", 1)
            var xpSuffix = ""
            var finalXp = 0L
            if (rewards.xp > 0 && rewards.xpSkill.isNotBlank()) {
                val b = playerRepo.previewFlatXpGrant(rewards.xpSkill, rewards.xp.toLong())
                finalXp = b.finalXp
                xpSuffix = xpMultiplierBreakdown(b.baseXp, b.boostFactor, b.blessingMult, b.prestigeXpPct)?.let { " $it" } ?: ""
                playerRepo.applySessionResults(rewards.xpSkill, rewards.xp.toLong(), rewards.items)
            } else if (rewards.items.isNotEmpty()) {
                playerRepo.addItems(rewards.items)
            }
            if (rewards.coins > 0) playerRepo.addCoins(rewards.coins.toLong())
            val parts = buildList {
                if (rewards.xp > 0) add("+${finalXp.formatXp()} XP$xpSuffix")
                if (rewards.coins > 0) add(context.withAppLocale().getString(R.string.reward_part_coins, rewards.coins.toLong().formatCoins()))
                rewards.items.forEach { (key, qty) -> add("${GameStrings.itemName(context, key)} x$qty") }
            }
            val suffix = if (parts.isNotEmpty()) " (${parts.joinToString(", ")})" else ""
            _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.guild_daily_reward_claimed) + suffix) }
        }
    }

    fun contributeFarmingDaily(templateId: String) {
        viewModelScope.launch {
            val inventory: Map<String, Int> = json.decodeFromString(playerRepo.getOrCreatePlayer().inventory)
            val consumed = guildRepo.contributeFarmingDaily(templateId, inventory)
            if (consumed > 0) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.guild_contributed_items, consumed)) }
            }
        }
    }

    fun contributeFarmingQuest(questId: String) {
        viewModelScope.launch {
            val inventory: Map<String, Int> = json.decodeFromString(playerRepo.getOrCreatePlayer().inventory)
            val consumed = guildRepo.contributeFarmingQuest(questId, inventory)
            if (consumed > 0) {
                _extra.update { it.copy(snackbarMessage = context.withAppLocale().getString(R.string.guild_contributed_items, consumed)) }
            }
        }
    }

    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }

    fun toggleHideCompleted() {
        viewModelScope.launch {
            val flags = playerRepo.getFlags()
            val newValue = !_extra.value.hideCompleted
            playerRepo.updateFlags(flags.copy(hideCompletedQuests = newValue))
            _extra.update { it.copy(hideCompleted = newValue) }
        }
    }
}
