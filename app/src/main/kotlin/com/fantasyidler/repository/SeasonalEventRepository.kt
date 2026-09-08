package com.fantasyidler.repository

import android.content.Context
import com.fantasyidler.data.json.SeasonalBountyTaskData
import com.fantasyidler.data.json.SeasonalEventData
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.SeasonalBannerEarned
import com.fantasyidler.util.GameStrings
import com.fantasyidler.util.withAppLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

data class SeasonalBountyTaskWithProgress(
    val task: SeasonalBountyTaskData,
    /** For "turn_in" tasks this is how many of the target the player currently holds. */
    val progress: Int,
    /** Non-null while this slot is waiting for a new task to rotate in after a claim. */
    val cooldownUntilMs: Long?,
)

sealed class SeasonalMinigameResult {
    object NoActiveEvent : SeasonalMinigameResult()
    data class OnCooldown(val resumesAtMs: Long) : SeasonalMinigameResult()
    data class Success(val resumesAtMs: Long) : SeasonalMinigameResult()
    data class Failure(val resumesAtMs: Long) : SeasonalMinigameResult()
}

@Singleton
class SeasonalEventRepository @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val gameData: GameDataRepository,
    private val dailyQuestRepo: DailyQuestRepository,
    @ApplicationContext private val context: Context,
) {

    /** Returns the event whose date window currently contains "now", or null between events. */
    fun activeEvent(): SeasonalEventData? =
        gameData.seasonalEvents.values.firstOrNull { it.isActiveAt(System.currentTimeMillis()) }

    fun bountyTasksWithProgress(
        event: SeasonalEventData,
        flags: PlayerFlags,
        inventory: Map<String, Int> = emptyMap(),
    ): List<SeasonalBountyTaskWithProgress> {
        val byId = event.bountyTasks.associateBy { it.id }
        return flags.seasonalBountySlots.mapIndexedNotNull { index, taskId ->
            val task = byId[taskId] ?: return@mapIndexedNotNull null
            SeasonalBountyTaskWithProgress(
                task            = task,
                progress        = if (task.type == "turn_in") inventory[task.target] ?: 0
                                  else flags.seasonalBountyProgress[taskId] ?: 0,
                cooldownUntilMs = flags.seasonalBountySlotCooldownUntil[index.toString()],
            )
        }
    }

    /** Returns currently active bounty tasks (excluding slots on cooldown), or empty if no event or bounty pillar is active. */
    fun getActiveBounties(
        flags: PlayerFlags,
        inventory: Map<String, Int> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): List<SeasonalBountyTaskWithProgress> {
        val event = activeEvent() ?: return emptyList()
        if ("bounty" !in event.pillars) return emptyList()
        return bountyTasksWithProgress(event, flags, inventory).filter { bp ->
            val cooldown = bp.cooldownUntilMs
            cooldown == null || now >= cooldown
        }
    }

    // -------------------------------------------------------------------------
    // Bounty Board — 3 slots (one per task type), each independently rotating to
    // a new same-type task [SeasonalEventData.bountyRotationMs] after it's claimed.
    // -------------------------------------------------------------------------

    suspend fun ensureBountySlotsRefreshed() = playerRepo.playerMutex.withLock { ensureBountySlotsRefreshedUnlocked() }

    private val combatSkillNames = listOf("attack", "strength", "defense", "ranged", "magic", "hitpoints")

    /** Level the player needs before [task]'s activity is even available, or null when unknown. */
    private fun requiredLevelFor(event: SeasonalEventData, task: SeasonalBountyTaskData): Pair<String, Int>? {
        if (task.type == "kill") {
            // Event kill targets only spawn in the event expedition; gate on its recommended level.
            val rec = event.expeditionKeys().mapNotNull { gameData.dungeons[it]?.recommendedLevel }.minOrNull()
            return rec?.let { "combat" to it }
        }
        val skill = task.skill ?: return null
        val level = when (skill) {
            "woodcutting"  -> gameData.trees.values.firstOrNull { it.logName == task.target }?.levelRequired
            "mining"       -> gameData.ores[task.target]?.levelRequired
            "fishing"      -> gameData.fish[task.target]?.levelRequired
            "farming"      -> gameData.crops[task.target]?.levelRequired
            "herblore"     -> gameData.herbloreRecipes[task.target]?.levelRequired
            "fletching"    -> gameData.fletchingRecipes[task.target]?.levelRequired
            "smithing"     -> gameData.smithingRecipes[task.target]?.levelRequired
            "crafting"     -> gameData.craftingRecipes[task.target]?.levelRequired
            "runecrafting" -> gameData.runes[task.target]?.levelRequired
            "cooking"      -> gameData.cookingRecipes.values
                .firstOrNull { it.cookedItem == task.target || it.rawItem == task.target }?.levelRequired
            else           -> null
        }
        return level?.let { skill to it }
    }

    private fun taskReachable(event: SeasonalEventData, task: SeasonalBountyTaskData, skillLevels: Map<String, Int>): Boolean {
        val (skill, required) = requiredLevelFor(event, task) ?: return true
        val playerLevel = if (skill == "combat") combatSkillNames.maxOf { skillLevels[it] ?: 1 }
                          else skillLevels[skill] ?: 1
        return playerLevel >= required
    }

    /** Picks a task the player can actually work on; falls back to the full pool so a slot is never empty. */
    private fun pickTask(
        event: SeasonalEventData,
        candidates: List<SeasonalBountyTaskData>,
        skillLevels: Map<String, Int>,
        excludeId: String? = null,
    ): SeasonalBountyTaskData? {
        val fresh = candidates.filter { it.id != excludeId }
        val reachable = fresh.filter { taskReachable(event, it, skillLevels) }
        return (reachable.ifEmpty { fresh }).randomOrNull()
            ?: candidates.firstOrNull { it.id == excludeId }
    }

    private suspend fun ensureBountySlotsRefreshedUnlocked(): PlayerFlags {
        val flags = playerRepo.getFlags()
        val event = activeEvent() ?: return flags
        val byType = event.bountyTasks.groupBy { it.type }
        val validIds = event.bountyTasks.map { it.id }.toSet()
        val player = playerRepo.getOrCreatePlayer()
        val skillLevels: Map<String, Int> = Json.decodeFromString(player.skillLevels)
        val inventory:   Map<String, Int> = Json.decodeFromString(player.inventory)
        val now = System.currentTimeMillis()

        val slotsValid = flags.seasonalBountyEventId == event.id &&
            flags.seasonalBountySlots.size == byType.size &&
            flags.seasonalBountySlots.all { it in validIds }

        if (!slotsValid) {
            val freshSlots = byType.values.mapNotNull { pickTask(event, it, skillLevels)?.id }
            val reseeded = flags.copy(
                seasonalBountyEventId           = event.id,
                seasonalBountySlots             = freshSlots,
                seasonalBountyProgress          = emptyMap(),
                seasonalBountySlotCooldownUntil = emptyMap(),
                seasonalBountyDailyStamp        = now,
            )
            playerRepo.updateFlagsUnlocked(reseeded)
            return reseeded
        }

        val slots = flags.seasonalBountySlots.toMutableList()
        var progress = flags.seasonalBountyProgress
        var cooldowns = flags.seasonalBountySlotCooldownUntil
        var dailyStamp = flags.seasonalBountyDailyStamp
        var changed = false

        // Claimed slots rotate once their post-claim cooldown expires. Replacements come
        // from the WHOLE pool, not the outgoing task's type: a slot chained to one type
        // (kill, say) was permanently useless to players who can't do that type at all
        // (discussion #1662). Tasks already in other slots are excluded so no duplicates.
        for ((index, taskId) in flags.seasonalBountySlots.withIndex()) {
            val cooldownUntil = cooldowns[index.toString()] ?: continue
            if (now < cooldownUntil) continue
            val nextTask = pickTask(event, event.bountyTasks.filterNot { it.id in slots }, skillLevels, excludeId = taskId)
                ?: event.bountyTasks.first { it.id == taskId }
            slots[index] = nextTask.id
            progress = progress - taskId
            cooldowns = cooldowns - index.toString()
            changed = true
        }

        // Daily 6am rotation: untouched slots re-roll so an out-of-reach bounty never
        // squats for the whole event. Slots with any progress (or a pending post-claim
        // cooldown) are left alone to protect in-flight work. Pool-wide like the
        // post-claim rotation above.
        if (dailyQuestRepo.shouldRefresh(dailyStamp, flags.dailyResetHour)) {
            for ((index, taskId) in slots.withIndex()) {
                if (cooldowns.containsKey(index.toString())) continue
                if ((progress[taskId] ?: 0) > 0) continue
                // A turn-in tithe tracks no progress; its "progress" is the stockpile in the
                // player's inventory. A fully stocked one is a claim waiting to happen, so it
                // must not be re-rolled out from under them (pool-wide rotation made that loss
                // permanent instead of cycling back to another tithe). Holding merely SOME of
                // the target does not protect: common items would make the slot squat forever.
                val current = event.bountyTasks.first { it.id == taskId }
                if (current.type == "turn_in" && (inventory[current.target] ?: 0) >= current.amount) continue
                val nextTask = pickTask(event, event.bountyTasks.filterNot { it.id in slots }, skillLevels, excludeId = taskId) ?: continue
                if (nextTask.id == taskId) continue
                slots[index] = nextTask.id
                progress = progress - taskId
                changed = true
            }
            dailyStamp = now
            changed = true
        }

        if (!changed) return flags
        val rotated = flags.copy(
            seasonalBountySlots             = slots,
            seasonalBountyProgress          = progress,
            seasonalBountySlotCooldownUntil = cooldowns,
            seasonalBountyDailyStamp        = dailyStamp,
        )
        playerRepo.updateFlagsUnlocked(rotated)
        return rotated
    }

    /** Called when a gathering session is collected. */
    suspend fun recordGathering(items: Map<String, Int>) = recordBountyProgress("gather", items)

    /** Called when a crafting session is collected. */
    suspend fun recordCrafting(items: Map<String, Int>) = recordBountyProgress("craft", items)

    /** Called when a combat session (dungeon, boss, or tower) is collected. */
    suspend fun recordCombat(killsByEnemy: Map<String, Int>) = recordBountyProgress("kill", killsByEnemy)

    private suspend fun recordBountyProgress(type: String, counts: Map<String, Int>) = playerRepo.playerMutex.withLock {
        val event = activeEvent() ?: return@withLock
        if ("bounty" !in event.pillars) return@withLock
        val flags = ensureBountySlotsRefreshedUnlocked()
        val activeTaskIds = flags.seasonalBountySlots.toSet()
        val updated = flags.seasonalBountyProgress.toMutableMap()
        var changed = false
        for (task in event.bountyTasks) {
            if (task.type != type) continue
            if (task.id !in activeTaskIds) continue
            val count = (counts[task.target] ?: 0) + (counts["enhanced_${task.target}"] ?: 0)
            if (count <= 0) continue
            val cur = updated[task.id] ?: 0
            if (cur >= task.amount) continue
            updated[task.id] = minOf(cur + count, task.amount)
            changed = true
        }
        if (changed) playerRepo.updateFlagsUnlocked(flags.copy(seasonalBountyProgress = updated))
    }

    /**
     * Claims a completed Bounty Board task, awarding one token and starting that slot's rotation
     * cooldown. "turn_in" tasks have no tracked progress — the asked-for items are consumed from
     * the player's inventory here instead.
     */
    suspend fun claimBountyTask(taskId: String): Boolean = playerRepo.playerMutex.withLock {
        val event = activeEvent() ?: return@withLock false
        if ("bounty" !in event.pillars) return@withLock false
        val task = event.bountyTasks.firstOrNull { it.id == taskId } ?: return@withLock false
        val flags = ensureBountySlotsRefreshedUnlocked()
        val slotIndex = flags.seasonalBountySlots.indexOf(taskId)
        if (slotIndex < 0 || flags.seasonalBountySlotCooldownUntil.containsKey(slotIndex.toString())) return@withLock false
        if (task.type == "turn_in") {
            if (!playerRepo.consumeItemsUnlocked(mapOf(task.target to task.amount))) return@withLock false
        } else {
            val progress = flags.seasonalBountyProgress[taskId] ?: 0
            if (progress < task.amount) return@withLock false
        }
        val claimedFlags = flags.copy(
            seasonalBountySlotCooldownUntil = flags.seasonalBountySlotCooldownUntil +
                (slotIndex.toString() to (System.currentTimeMillis() + event.bountyRotationMs)),
        )
        playerRepo.updateFlagsUnlocked(awardTokenUnlocked(claimedFlags, event))
        true
    }

    enum class RerollResult { SUCCESS, NOT_ENOUGH_COINS, UNAVAILABLE }

    /**
     * Instantly replaces the bounty in [taskId]'s slot with a fresh pool-wide pick for
     * [BOUNTY_REROLL_COST] coins, discarding its progress. The instant swap is what the
     * fee buys; the free roads stay the post-claim rotation and the daily re-roll
     * (discussion #1662). Slots in their post-claim cooldown can't be rerolled.
     */
    suspend fun rerollBountyTask(taskId: String): RerollResult = playerRepo.playerMutex.withLock {
        val event = activeEvent() ?: return@withLock RerollResult.UNAVAILABLE
        if ("bounty" !in event.pillars) return@withLock RerollResult.UNAVAILABLE
        val flags = ensureBountySlotsRefreshedUnlocked()
        val slotIndex = flags.seasonalBountySlots.indexOf(taskId)
        if (slotIndex < 0 || flags.seasonalBountySlotCooldownUntil.containsKey(slotIndex.toString())) {
            return@withLock RerollResult.UNAVAILABLE
        }
        val skillLevels: Map<String, Int> =
            Json.decodeFromString(playerRepo.getOrCreatePlayer().skillLevels)
        val nextTask = pickTask(
            event,
            event.bountyTasks.filterNot { it.id in flags.seasonalBountySlots },
            skillLevels,
            excludeId = taskId,
        ) ?: return@withLock RerollResult.UNAVAILABLE
        if (!playerRepo.spendCoinsUnlocked(BOUNTY_REROLL_COST)) return@withLock RerollResult.NOT_ENOUGH_COINS
        val slots = flags.seasonalBountySlots.toMutableList().also { it[slotIndex] = nextTask.id }
        playerRepo.updateFlagsUnlocked(flags.copy(
            seasonalBountySlots    = slots,
            seasonalBountyProgress = flags.seasonalBountyProgress - taskId,
        ))
        RerollResult.SUCCESS
    }

    // -------------------------------------------------------------------------
    // Expedition / Raid Boss — the underlying session is the existing dungeon/boss
    // engine; these just award a token when the completed key matches the active event.
    // -------------------------------------------------------------------------

    suspend fun recordExpeditionCompletion(activityKey: String) = playerRepo.playerMutex.withLock {
        val event = activeEvent() ?: return@withLock
        if ("expedition" !in event.pillars || activityKey !in event.expeditionKeys()) return@withLock
        playerRepo.updateFlagsUnlocked(awardTokenUnlocked(playerRepo.getFlags(), event))
    }

    suspend fun recordBossDefeat(bossKey: String) = playerRepo.playerMutex.withLock {
        val event = activeEvent() ?: return@withLock
        if ("boss" !in event.pillars || event.bossKey != bossKey) return@withLock
        val flags = playerRepo.getFlags()
        val day = playerRepo.gameDay(flags.dailyResetHour)
        val earnedToday = if (flags.seasonalBossTokenDay == day) flags.seasonalBossTokensToday else 0
        if (earnedToday >= BOSS_TOKENS_PER_DAY) return@withLock
        playerRepo.updateFlagsUnlocked(
            awardTokenUnlocked(flags, event).copy(
                seasonalBossTokenDay    = day,
                seasonalBossTokensToday = earnedToday + 1,
            )
        )
    }

    // -------------------------------------------------------------------------
    // Reward tiers — claimable payouts along the token track. The final banner +
    // title tier stays on the automatic path in awardTokenUnlocked.
    // -------------------------------------------------------------------------

    /** Claims the reward tier at [tierTokens] tokens: grants its payload and records the claim. */
    suspend fun claimRewardTier(tierTokens: Int): Boolean = playerRepo.playerMutex.withLock {
        val event = activeEvent() ?: return@withLock false
        val tier = event.rewardTiers.firstOrNull { it.tokens == tierTokens } ?: return@withLock false
        if (tier.coins <= 0 && tier.items.isEmpty() && tier.petId == null && !tier.xpBoost) return@withLock false
        val flags = playerRepo.getFlagsUnlocked()
        val claimed = flags.seasonalRewardTiersClaimed[event.id].orEmpty()
        if ((flags.seasonalTokensByEvent[event.id] ?: 0) < tier.tokens || tier.tokens in claimed) return@withLock false

        if (tier.coins > 0) playerRepo.addCoinsUnlocked(tier.coins)
        if (tier.items.isNotEmpty()) playerRepo.addItemsUnlocked(tier.items)
        tier.petId?.let { petId ->
            playerRepo.addPetIfNewUnlocked(petId, gameData.pets[petId]?.boostPercent ?: 0)
        }
        // Ironman characters never receive the XP boost component (boosts are inert for them);
        // the tier's other rewards are still granted.
        if (tier.xpBoost && !flags.ironman) playerRepo.grantXpBoostUnlocked(PlayerRepository.XP_BOOST_DURATION_MS)

        // The grants above rewrite the flags column (seen items, boost expiry) — re-read before recording the claim.
        val latest = playerRepo.getFlagsUnlocked()
        playerRepo.updateFlagsUnlocked(latest.copy(
            seasonalRewardTiersClaimed = latest.seasonalRewardTiersClaimed + (event.id to (claimed + tier.tokens)),
        ))
        true
    }

    // -------------------------------------------------------------------------
    // Night Market — coin-priced item bundles and utility effects.
    // -------------------------------------------------------------------------

    /** Buys a Night Market offer: spends coins, then grants its items or applies its effect. */
    suspend fun purchaseMarketOffer(offerId: String): Boolean = playerRepo.playerMutex.withLock {
        val event = activeEvent() ?: return@withLock false
        val offer = event.nightMarket.firstOrNull { it.id == offerId } ?: return@withLock false
        val flags = playerRepo.getFlagsUnlocked()
        val purchaseKey = "${event.id}:${offer.id}"
        val bought = flags.seasonalMarketPurchases[purchaseKey] ?: 0
        if (offer.limit != null && bought >= offer.limit) return@withLock false
        if (!playerRepo.spendCoinsUnlocked(offer.coinCost)) return@withLock false

        if (offer.items.isNotEmpty()) playerRepo.addItemsUnlocked(offer.items)

        val latest = playerRepo.getFlagsUnlocked()
        var updated = when (offer.effect) {
            // Mark every waiting slot as already expired (not cleared — clearing would leave the
            // claimed task in place, re-claimable); the refresh below then rotates new tasks in.
            "skip_bounty_cooldowns"  -> latest.copy(seasonalBountySlotCooldownUntil = latest.seasonalBountySlotCooldownUntil.mapValues { 0L })
            "skip_minigame_cooldown" -> latest.copy(seasonalMinigameCooldownAt = 0L)
            else                     -> latest
        }
        updated = updated.copy(seasonalMarketPurchases = updated.seasonalMarketPurchases + (purchaseKey to bought + 1))
        playerRepo.updateFlagsUnlocked(updated)
        if (offer.effect == "skip_bounty_cooldowns") ensureBountySlotsRefreshedUnlocked()
        true
    }

    // -------------------------------------------------------------------------
    // Minigame — the Hub screen runs the actual whack-a-mole rounds and reports
    // whether the player landed enough hits to win. Either way the cooldown applies.
    // -------------------------------------------------------------------------

    suspend fun submitMinigameAttempt(won: Boolean): SeasonalMinigameResult = playerRepo.playerMutex.withLock {
        val event = activeEvent() ?: return@withLock SeasonalMinigameResult.NoActiveEvent
        val minigame = event.minigame
        if ("minigame" !in event.pillars || minigame == null) return@withLock SeasonalMinigameResult.NoActiveEvent
        val flags = playerRepo.getFlags()
        val now = System.currentTimeMillis()
        if (flags.seasonalMinigameCooldownAt > now) return@withLock SeasonalMinigameResult.OnCooldown(flags.seasonalMinigameCooldownAt)
        val resumesAt = now + (if (flags.seasonalMinigameEasyMode) minigame.cooldownMsEasy else minigame.cooldownMs)
        val flagsWithCooldown = flags.copy(seasonalMinigameCooldownAt = resumesAt)
        if (won) {
            playerRepo.updateFlagsUnlocked(awardTokenUnlocked(flagsWithCooldown, event))
            SeasonalMinigameResult.Success(resumesAt)
        } else {
            playerRepo.updateFlagsUnlocked(flagsWithCooldown)
            SeasonalMinigameResult.Failure(resumesAt)
        }
    }

    // -------------------------------------------------------------------------
    // Shared token award — must only be called while already holding playerMutex.
    // -------------------------------------------------------------------------

    companion object {
        /** Daily soft cap on tokens from the event boss, resetting at the daily reset hour. */
        const val BOSS_TOKENS_PER_DAY = 25
        const val BOUNTY_REROLL_COST = 50_000L
    }

    private fun awardTokenUnlocked(flags: PlayerFlags, event: SeasonalEventData): PlayerFlags {
        val newCount = (flags.seasonalTokensByEvent[event.id] ?: 0) + 1
        var updated = flags.copy(seasonalTokensByEvent = flags.seasonalTokensByEvent + (event.id to newCount))
        if (newCount >= event.tokenGoal && event.id !in flags.seasonalBannersEarned.map { it.eventId }) {
            val localeContext = context.withAppLocale()
            updated = updated.copy(
                seasonalBannersEarned = updated.seasonalBannersEarned + SeasonalBannerEarned(
                    eventId          = event.id,
                    displayText      = GameStrings.seasonalEventBanner(localeContext, event.id, event.bannerText),
                    completedAtMs    = System.currentTimeMillis(),
                    bannerIcon       = event.bannerIcon,
                    eventDisplayName = GameStrings.seasonalEventName(localeContext, event.id, event.displayName),
                )
            )
        }
        return updated
    }
}
