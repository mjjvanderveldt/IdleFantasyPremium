package com.fantasyidler.repository

import com.fantasyidler.data.model.EquipSlot
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.Skills
import com.fantasyidler.data.model.SlayerTask
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

sealed class ForetelResult {
    data class Success(val task: SlayerTask) : ForetelResult()
    object QueueFull : ForetelResult()
    object NoEligibleTasks : ForetelResult()
    data class NotEnoughBones(val costUnits: Int) : ForetelResult()
}

@Singleton
class SlayerRepository @Inject constructor(
    private val boostRepo: BoostRepository,
    private val playerRepo: PlayerRepository,
    private val questRepo: QuestRepository,
    private val gameData: GameDataRepository,
    private val guildRepo: GuildRepository,
) {

    /** Pick and assign a random eligible task for the given Slayer level. Returns false if no eligible tasks exist. */
    suspend fun startForetelledTask(task: SlayerTask): Boolean = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        if (flags.activeSlayerTask != null) return@withLock false
        val newForetelled = flags.foretelledTasks.filterNot { it.enemyKey == task.enemyKey }
        if (newForetelled.size == flags.foretelledTasks.size) return@withLock false
        playerRepo.updateFlagsUnlocked(flags.copy(
            activeSlayerTask = task,
            foretelledTasks  = newForetelled,
        ))
        true
    }

    suspend fun assignTask(slayerLevel: Int, unlockedDungeons: Set<String> = emptySet()): Boolean = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        // Pop the first foretelled task if any exist
        if (flags.foretelledTasks.isNotEmpty()) {
            val next = flags.foretelledTasks.first()
            playerRepo.updateFlagsUnlocked(
                flags.copy(
                    activeSlayerTask = next,
                    foretelledTasks  = flags.foretelledTasks.drop(1),
                )
            )
            return@withLock true
        }

        val eligible = gameData.slayerTasks.filter { (enemyKey, cfg) ->
            if (cfg.slayerLevel > slayerLevel) return@filter false
            // Skip enemies that exclusively appear in expedition-locked dungeons the player hasn't unlocked.
            val dungeonKeys = gameData.dungeons.values
                .filter { d -> d.enemySpawns.any { it.enemy == enemyKey } }
                .map { it.name }
            if (dungeonKeys.isEmpty()) return@filter true
            dungeonKeys.any { it !in gameData.expeditionLockedDungeons || it in unlockedDungeons }
        }
        if (eligible.isEmpty()) return@withLock false

        val (enemyKey, cfg) = eligible.entries.random()
        val targetKills = Random.nextInt(cfg.minKills, cfg.maxKills + 1)
        val taskPoints  = maxOf(3, cfg.slayerLevel / 5)

        playerRepo.updateFlagsUnlocked(
            flags.copy(
                activeSlayerTask = SlayerTask(
                    enemyKey       = enemyKey,
                    targetKills    = targetKills,
                    killsCompleted = 0,
                    xpPerKill      = cfg.xpPerKill,
                    taskPoints     = taskPoints,
                )
            )
        )
        true
    }

    /**
     * Bone cost (in units, where 1 unit = 10 bone XP = 1 regular bone) for the next foretell slot.
     * Slot 0 (first queued) = 10, slot 1 = 25, slot 2+ = 50.
     */
    fun foretelCostUnits(queueSize: Int): Int = when (queueSize) {
        0    -> 10
        1    -> 25
        else -> 50
    }

    /** Foretell queue capacity: base 3, extended by Foresight prestige nodes. */
    fun maxForetellSlots(flags: PlayerFlags): Int = 3 + boostRepo.foretellSlotBonus(flags)

    private val BONE_XP = mapOf("dragon_bone" to 80, "giant_bones" to 40, "big_bones" to 20, "bones" to 10)
    private val BONE_TYPES_ORDERED = listOf("bones", "big_bones", "giant_bones", "dragon_bone")
    private val BASE_BONE_XP = 10
    private val SLAYER_SKIP_COST = 30

    private fun getEligibleEnemies() = gameData.slayerTasks

    private fun totalBoneXp(inventory: Map<String, Int>): Int =
        BONE_XP.entries.sumOf { (key, xp) -> (inventory[key] ?: 0) * xp }

    suspend fun foretelTask(slayerLevel: Int, unlockedDungeons: Set<String> = emptySet()): ForetelResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        if (flags.foretelledTasks.size >= maxForetellSlots(flags)) return@withLock ForetelResult.QueueFull

        val eligible = gameData.slayerTasks.filter { (enemyKey, cfg) ->
            if (cfg.slayerLevel > slayerLevel) return@filter false
            val dungeonKeys = gameData.dungeons.values
                .filter { d -> d.enemySpawns.any { it.enemy == enemyKey } }
                .map { it.name }
            if (dungeonKeys.isEmpty()) return@filter true
            dungeonKeys.any { it !in gameData.expeditionLockedDungeons || it in unlockedDungeons }
        }
        if (eligible.isEmpty()) return@withLock ForetelResult.NoEligibleTasks

        val costUnits = foretelCostUnits(flags.foretelledTasks.size)
        val costXp = costUnits * BASE_BONE_XP
        val inventory = playerRepo.getInventoryUnlocked()
        if (totalBoneXp(inventory) < costXp) return@withLock ForetelResult.NotEnoughBones(costUnits)

        // Consume bones greedily cheapest-first
        val toConsume = mutableMapOf<String, Int>()
        var remaining = costXp
        for (boneKey in BONE_TYPES_ORDERED) {
            if (remaining <= 0) break
            val xp   = BONE_XP[boneKey] ?: continue
            val have = inventory[boneKey] ?: 0
            if (have == 0) continue
            val needed  = (remaining + xp - 1) / xp
            val consume = minOf(have, needed)
            toConsume[boneKey] = consume
            remaining -= consume * xp
        }
        
        playerRepo.consumeItemsUnlocked(toConsume)

        val (enemyKey, cfg) = eligible.entries.random()
        val targetKills = Random.nextInt(cfg.minKills, cfg.maxKills + 1)
        val taskPoints  = maxOf(3, cfg.slayerLevel / 5)
        val task = SlayerTask(
            enemyKey       = enemyKey,
            targetKills    = targetKills,
            killsCompleted = 0,
            xpPerKill      = cfg.xpPerKill,
            taskPoints     = taskPoints,
        )

        if (flags.activeSlayerTask == null) {
            playerRepo.updateFlagsUnlocked(flags.copy(activeSlayerTask = task))
        } else {
            playerRepo.updateFlagsUnlocked(flags.copy(foretelledTasks = flags.foretelledTasks + task))
        }
        ForetelResult.Success(task)
    }

    /**
     * Record kills from a dungeon session.
     *
     * Returns Slayer XP earned (0 if no active task or enemy doesn't match).
     * Handles task completion: awards points and records quest progress.
     */
    suspend fun recordKills(enemyKey: String, count: Int): Long {
        if (count <= 0) return 0L
        val flags = playerRepo.getFlags()
        val task  = flags.activeSlayerTask ?: return 0L
        if (boostRepo.slayerMultiTaskActive(flags)) return recordKillsMultiTask(flags, enemyKey, count)
        if (task.enemyKey != enemyKey) return 0L

        val equippedCape   = playerRepo.getEquipped()[EquipSlot.CAPE]?.let { gameData.equipment[it] }
        val capeMult       = resolveCapeMultiplier(
            Skills.SLAYER, equippedCape, playerRepo.getInventory().keys,
            flags.townBuildingTiers, boostRepo.capeScalingBySkill(flags), gameData.equipment, flags.ironman,
        )

        // Order-strict chain walk: kills left over after completing the active task roll
        // into the next foretold task, but only while consecutive tasks are for the same
        // enemy (issue #1677). The multi-task prestige perk keeps its broader any-order
        // behavior in recordKillsMultiTask.
        val chain = (listOf(task) + flags.foretelledTasks).toMutableList()
        var remaining      = count
        var xpEarned       = 0L
        var applied        = 0
        var completedCount = 0
        for (idx in chain.indices) {
            if (remaining <= 0) break
            val t = chain[idx]
            if (t.enemyKey != enemyKey) break
            val added = minOf(remaining, t.targetKills - t.killsCompleted)
            if (added <= 0) break
            remaining -= added
            applied   += added
            xpEarned  += (added.toLong() * t.xpPerKill * capeMult).toLong()
            chain[idx] = t.copy(killsCompleted = t.killsCompleted + added)
            if (chain[idx].killsCompleted < t.targetKills) break
            completedCount++
        }
        if (applied <= 0) return 0L

        val pointsGained = chain.take(completedCount)
            .sumOf { (it.taskPoints * boostRepo.slayerPointsMultiplier(flags)).toInt() }
        playerRepo.updateFlags(
            flags.copy(
                activeSlayerTask = chain.getOrNull(completedCount),
                foretelledTasks  = chain.drop(completedCount + 1),
                slayerPoints     = flags.slayerPoints + pointsGained,
            )
        )
        repeat(completedCount) { questRepo.recordSlayerTaskCompleted() }
        if (completedCount > 0) playerRepo.recordWeeklyProgress("slayer_task", "any", completedCount)
        guildRepo.recordGuildSlayer(applied, completedCount)
        return xpEarned
    }

    /**
     * Foresight final tier: kills flow through the active task and then every matching
     * foretold task in queue order, so one dungeon can complete several tasks at once.
     */
    private suspend fun recordKillsMultiTask(flags: PlayerFlags, enemyKey: String, count: Int): Long {
        val active = flags.activeSlayerTask ?: return 0L
        val chain  = (listOf(active) + flags.foretelledTasks).toMutableList()

        val equippedCape = playerRepo.getEquipped()[EquipSlot.CAPE]?.let { gameData.equipment[it] }
        val capeMult     = resolveCapeMultiplier(
            Skills.SLAYER, equippedCape, playerRepo.getInventory().keys,
            flags.townBuildingTiers, boostRepo.capeScalingBySkill(flags), gameData.equipment, flags.ironman,
        )

        var remaining = count
        var xpEarned  = 0L
        for (idx in chain.indices) {
            if (remaining <= 0) break
            val t = chain[idx]
            if (t.enemyKey != enemyKey || t.killsCompleted >= t.targetKills) continue
            val added = minOf(remaining, t.targetKills - t.killsCompleted)
            remaining -= added
            xpEarned  += (added.toLong() * t.xpPerKill * capeMult).toLong()
            chain[idx] = t.copy(killsCompleted = t.killsCompleted + added)
        }
        val killsApplied = count - remaining
        if (killsApplied <= 0) return 0L

        val completedTasks = chain.filter { it.killsCompleted >= it.targetKills }
        val openTasks      = chain.filter { it.killsCompleted <  it.targetKills }
        val pointsGained   = completedTasks.sumOf { (it.taskPoints * boostRepo.slayerPointsMultiplier(flags)).toInt() }

        playerRepo.updateFlags(
            flags.copy(
                activeSlayerTask = openTasks.firstOrNull(),
                foretelledTasks  = openTasks.drop(1),
                slayerPoints     = flags.slayerPoints + pointsGained,
            )
        )
        repeat(completedTasks.size) { questRepo.recordSlayerTaskCompleted() }
        if (completedTasks.isNotEmpty()) playerRepo.recordWeeklyProgress("slayer_task", "any", completedTasks.size)
        guildRepo.recordGuildSlayer(killsApplied, completedTasks.size)
        return xpEarned
    }

    /**
     * Spend 30 Slayer points to skip the current task and immediately assign a new one.
     * Returns false if the player doesn't have enough points or there are no eligible tasks.
     */
    suspend fun skipTask(slayerLevel: Int, unlockedDungeons: Set<String> = emptySet()): Boolean {
        val skipped = playerRepo.withLock {
            val flags = playerRepo.getFlagsUnlocked()
            val task = flags.activeSlayerTask ?: return@withLock false
            if (flags.slayerPoints < SLAYER_SKIP_COST) return@withLock false
            playerRepo.updateFlagsUnlocked(flags.copy(
                slayerPoints     = flags.slayerPoints - SLAYER_SKIP_COST,
                activeSlayerTask = null,
            ))
            true
        }
        if (skipped) {
            return assignTask(slayerLevel, unlockedDungeons)
        }
        return false
    }

    /**
     * Deduct [cost] Slayer points and add [itemKey] to the player's inventory.
     * Returns false if insufficient points.
     */
    suspend fun spendPointsForItem(itemKey: String, cost: Int): Boolean = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        if (flags.slayerPoints < cost) return@withLock false
        playerRepo.updateFlagsUnlocked(flags.copy(slayerPoints = flags.slayerPoints - cost))
        playerRepo.addItemUnlocked(itemKey, 1)
        true
    }

    /**
     * Deduct [cost] Slayer points and add [xpAmount] XP to [skillKey].
     * Returns false if insufficient points.
     */
    data class SpendXpResult(val success: Boolean, val breakdown: PlayerRepository.FlatXpBreakdown? = null)

    suspend fun spendPointsForXp(skillKey: String, xpAmount: Long, cost: Int): SpendXpResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        if (flags.slayerPoints < cost) return@withLock SpendXpResult(false)
        playerRepo.updateFlagsUnlocked(flags.copy(slayerPoints = flags.slayerPoints - cost))
        val breakdown = playerRepo.previewFlatXpGrant(skillKey, xpAmount)
        playerRepo.applyMultiSkillResultsUnlocked(
            mapOf(skillKey to xpAmount),
            emptyMap(),
            0L,
        )
        SpendXpResult(true, breakdown)
    }
}
