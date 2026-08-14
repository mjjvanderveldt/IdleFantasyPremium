package com.fantasyidler.repository

import com.fantasyidler.data.db.dao.FarmingPatchDao
import com.fantasyidler.data.db.dao.PlayerDao
import com.fantasyidler.data.db.dao.QuestProgressDao
import com.fantasyidler.data.json.EquipmentData
import com.fantasyidler.data.model.*
import com.fantasyidler.simulator.PrestigeBoosts
import com.fantasyidler.simulator.PrestigePoints
import com.fantasyidler.simulator.SkillSimulator
import com.fantasyidler.simulator.XpTable
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// Helpers — use explicit two-arg encodeToString(serializer, value) to avoid
// Kotlin 2.0 extension/member resolution ambiguity with the single-arg form.
// ---------------------------------------------------------------------------

private inline fun <reified T> Json.encode(value: T): String =
    encodeToString(serializersModule.serializer<T>(), value)

internal fun capeKeyForSkill(skill: String): String? = when (skill) {
    Skills.HITPOINTS -> "hp_cape"
    else             -> "${skill}_cape"
}

private fun PlayerFlags.plusSeen(keys: Collection<String>): PlayerFlags =
    if (keys.isEmpty()) this else copy(seenItemKeys = seenItemKeys + keys)

enum class XpBoostPurchaseResult { SUCCESS, NOT_ENOUGH_COINS, ALREADY_ACTIVE, WEEKLY_LIMIT_REACHED }

enum class PrestigeActionResult { SUCCESS, NOT_ENOUGH_POINTS, LOCKED, COOLDOWN, INVALID, CANT_AFFORD }

@Singleton
class PlayerRepository @Inject constructor(
    private val playerDao: PlayerDao,
    private val questProgressDao: QuestProgressDao,
    private val farmingPatchDao: FarmingPatchDao,
    private val json: Json,
    private val dailyQuestRepo: DailyQuestRepository,
    private val weeklyQuestRepo: WeeklyQuestRepository,
    private val buffNotifScheduler: BuffNotificationScheduler,
    private val gameData: GameDataRepository,
    private val boostRepo: BoostRepository,
) {
    val playerMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Emits the raw [Player] entity whenever the DB row changes.
     * Creates the default player on first access so observers never stall on null.
     */
    val playerFlow: Flow<Player?> = flow {
        getOrCreatePlayer()
        emitAll(playerDao.observePlayer())
    }

    /** Returns the player, creating a default profile if none exists. */
    suspend fun getOrCreatePlayer(): Player {
        val player = playerDao.getPlayer() ?: createDefaultPlayer().also { playerDao.upsert(it) }
        return if (player.skillXp.contains("\"hp\":")) migrateHpKey(player) else player
    }

    private suspend fun migrateHpKey(player: Player): Player {
        val xpMap: MutableMap<String, Long> = json.decodeFromString(player.skillXp)
        val hpXp = xpMap.remove("hp") ?: return player
        val levels: MutableMap<String, Int> = json.decodeFromString(player.skillLevels)
        levels.remove("hp")
        val newHpXp = (xpMap[Skills.HITPOINTS] ?: 0L) + hpXp
        xpMap[Skills.HITPOINTS] = newHpXp
        levels[Skills.HITPOINTS] = XpTable.levelForXp(newHpXp)
        val migrated = player.copy(
            skillXp     = json.encode<Map<String, Long>>(xpMap),
            skillLevels = json.encode<Map<String, Int>>(levels),
        )
        playerDao.upsert(migrated)
        return migrated
    }

    suspend fun getSkillLevels(): Map<String, Int> =
        json.decodeFromString(getOrCreatePlayer().skillLevels)

    suspend fun getSkillXp(): Map<String, Long> =
        json.decodeFromString(getOrCreatePlayer().skillXp)

    suspend fun getInventory(): Map<String, Int> =
        json.decodeFromString(getOrCreatePlayer().inventory)

    internal suspend fun getInventoryUnlocked(): Map<String, Int> =
        json.decodeFromString(getOrCreatePlayer().inventory)

    suspend fun getEquipped(): Map<String, String?> =
        json.decodeFromString(getOrCreatePlayer().equipped)

    suspend fun getFlags(): PlayerFlags =
        json.decodeFromString(getOrCreatePlayer().flags)

    internal suspend fun getFlagsUnlocked(): PlayerFlags =
        json.decodeFromString(getOrCreatePlayer().flags)

    suspend fun getOwnedPets(): List<OwnedPet> =
        json.decodeFromString(getOrCreatePlayer().pets)

    // ------------------------------------------------------------------
    // Write operations
    // ------------------------------------------------------------------

    /**
     * Apply completed session results to the player: add XP (doubled if boost active),
     * recalculate level, and merge loot into inventory.
     * Returns the keys of any skill capes awarded (level 99 reached for the first time).
     */
    private fun prayerCapeMult(player: Player, flags: PlayerFlags): Float =
        blessingPrayerCapeMult(
            flags,
            json.decodeFromString(player.equipped),
            json.decodeFromString<Map<String, Int>>(player.inventory).keys,
            gameData,
        )

    suspend fun applySessionResults(
        skillName: String,
        xpGained: Long,
        itemsGained: Map<String, Int>,
        efficiencyMultiplier: Float = 1.0f,
    ): List<String> = playerMutex.withLock {
        val player    = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val ratedXp = xpGained * BASE_XP_RATE_MULTIPLIER
        val scaledXp = if (efficiencyMultiplier == 1.0f) ratedXp else (ratedXp * efficiencyMultiplier).toLong()
        // 2x boost, blessing, and prestige xp nodes combined in one place (ironman-inert).
        val boostedXp = (scaledXp * boostRepo.xpMultiplier(skillName, flags, prayerCapeMult(player, flags))).toLong()
        val scaledItems = if (efficiencyMultiplier == 1.0f) itemsGained
            else itemsGained.mapValues { (_, v) -> (v * efficiencyMultiplier).roundToInt().coerceAtLeast(1) }

        val levels: MutableMap<String, Int>  = json.decodeFromString(player.skillLevels)
        val xpMap: MutableMap<String, Long>  = json.decodeFromString(player.skillXp)
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)

        val oldLevel = XpTable.levelForXp(xpMap[skillName] ?: 0L)
        val newXp = (xpMap[skillName] ?: 0L) + boostedXp
        xpMap[skillName] = newXp
        levels[skillName] = XpTable.levelForXp(newXp)

        val awardedCapes = mutableListOf<String>()
        if (oldLevel < 99 && levels[skillName]!! >= 99) {
            val capeKey = capeKeyForSkill(skillName)
            if (capeKey != null && !inventory.containsKey(capeKey)) {
                inventory[capeKey] = 1
                awardedCapes += capeKey
            }
        }

        for ((item, qty) in scaledItems) {
            inventory[item] = (inventory[item] ?: 0) + qty
        }

        playerDao.upsert(
            player.copy(
                skillLevels = json.encode<Map<String, Int>>(levels),
                skillXp     = json.encode<Map<String, Long>>(xpMap),
                inventory   = json.encode<Map<String, Int>>(inventory),
                flags       = json.encode<PlayerFlags>(flags.plusSeen(scaledItems.keys + awardedCapes)),
            )
        )
        return awardedCapes
    }

    /** Subtract XP from a skill, flooring at 0. Recalculates level. */
    suspend fun deductSkillXp(skillName: String, amount: Long) {
        val player = getOrCreatePlayer()
        val levels: MutableMap<String, Int> = json.decodeFromString(player.skillLevels)
        val xpMap:  MutableMap<String, Long> = json.decodeFromString(player.skillXp)
        val newXp = ((xpMap[skillName] ?: 0L) - amount).coerceAtLeast(0L)
        xpMap[skillName]    = newXp
        levels[skillName]   = XpTable.levelForXp(newXp)
        playerDao.upsert(player.copy(
            skillLevels = json.encode<Map<String, Int>>(levels),
            skillXp     = json.encode<Map<String, Long>>(xpMap),
        ))
    }

    /** Add XP to a skill with no boosts or multipliers. Recalculates level. */
    suspend fun debugAddSkillXp(skillName: String, amount: Long) {
        if (amount <= 0L) return
        val player = getOrCreatePlayer()
        val levels: MutableMap<String, Int> = json.decodeFromString(player.skillLevels)
        val xpMap: MutableMap<String, Long> = json.decodeFromString(player.skillXp)
        val newXp = (xpMap[skillName] ?: 0L) + amount
        xpMap[skillName] = newXp
        levels[skillName] = XpTable.levelForXp(newXp)
        playerDao.upsert(player.copy(
            skillLevels = json.encode<Map<String, Int>>(levels),
            skillXp     = json.encode<Map<String, Long>>(xpMap),
        ))
    }

    data class BuryBonesResult(val buried: Int, val xpGained: Long, val awardedCape: String?)

    /**
     * Atomically consume up to [count] of [boneKey] from inventory and award [xpToAward]
     * prayer XP (scaled down proportionally if fewer bones were available). One DB write
     * regardless of [count] — the Bone Altar accumulates rapid taps into batches.
     */
    suspend fun buryBonesAtomic(boneKey: String, count: Int, xpToAward: Long): BuryBonesResult {
        val player    = getOrCreatePlayer()
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        val available = inventory[boneKey] ?: 0
        val buried    = minOf(count, available)
        if (buried <= 0) return BuryBonesResult(0, 0L, null)
        val xpGained  = (if (buried == count) xpToAward else xpToAward * buried / count) * BASE_XP_RATE_MULTIPLIER

        val newQty = available - buried
        if (newQty <= 0) inventory.remove(boneKey) else inventory[boneKey] = newQty

        val levels: MutableMap<String, Int> = json.decodeFromString(player.skillLevels)
        val xpMap:  MutableMap<String, Long> = json.decodeFromString(player.skillXp)
        val oldLevel = XpTable.levelForXp(xpMap[Skills.PRAYER] ?: 0L)
        val newXp    = (xpMap[Skills.PRAYER] ?: 0L) + xpGained
        xpMap[Skills.PRAYER]  = newXp
        levels[Skills.PRAYER] = XpTable.levelForXp(newXp)

        var awardedCape: String? = null
        if (oldLevel < 99 && levels[Skills.PRAYER]!! >= 99) {
            val capeKey = capeKeyForSkill(Skills.PRAYER)
            if (capeKey != null && !inventory.containsKey(capeKey)) {
                inventory[capeKey] = 1
                awardedCape = capeKey
            }
        }

        playerDao.upsert(player.copy(
            inventory   = json.encode<Map<String, Int>>(inventory),
            skillLevels = json.encode<Map<String, Int>>(levels),
            skillXp     = json.encode<Map<String, Long>>(xpMap),
        ))
        return BuryBonesResult(buried, xpGained, awardedCape)
    }

    /**
     * Remove items from the player's inventory.
     * Returns false (and makes no change) if any item is in insufficient quantity.
     */
    suspend fun consumeItems(items: Map<String, Int>): Boolean = playerMutex.withLock { consumeItemsUnlocked(items) }

    internal suspend fun consumeItemsUnlocked(items: Map<String, Int>): Boolean {
        val player = getOrCreatePlayer()
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)

        for ((item, qty) in items) {
            require(qty >= 0) { "Cannot consume negative quantity" }
            if ((inventory[item] ?: 0) < qty) return false
        }
        for ((item, qty) in items) {
            val newQty = (inventory[item] ?: 0) - qty
            if (newQty <= 0) inventory.remove(item) else inventory[item] = newQty
        }
        playerDao.upsert(player.copy(inventory = json.encode<Map<String, Int>>(inventory)))
        return true
    }

    suspend fun addCoins(amount: Long) = playerMutex.withLock { addCoinsUnlocked(amount) }

    internal suspend fun addCoinsUnlocked(amount: Long) {
        require(amount >= 0) { "Cannot add negative coins" }
        val player = getOrCreatePlayer()
        val newCoins = (player.coins + amount).coerceAtMost(Long.MAX_VALUE)
        playerDao.upsert(player.copy(coins = newCoins))
    }

    /** Awards capes for any skill already at 99 that doesn't have one yet (retroactive fix). */
    suspend fun awardMissingCapes() = playerMutex.withLock { awardMissingCapesUnlocked() }

    private suspend fun awardMissingCapesUnlocked() {
        val player    = getOrCreatePlayer()
        val levels:    MutableMap<String, Int> = json.decodeFromString(player.skillLevels)
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        var changed = false
        for ((skill, level) in levels) {
            if (level >= 99) {
                val capeKey = capeKeyForSkill(skill) ?: continue
                if (!inventory.containsKey(capeKey)) {
                    inventory[capeKey] = 1
                    changed = true
                }
            }
        }
        if (changed) playerDao.upsert(player.copy(inventory = json.encode<Map<String, Int>>(inventory)))
    }

    /** Adds qty of item to the player's inventory at no coin cost (prize/drop grant). */
    suspend fun grantItem(key: String, qty: Int = 1) = playerMutex.withLock { grantItemUnlocked(key, qty) }

    private suspend fun grantItemUnlocked(key: String, qty: Int = 1) {
        require(qty >= 0) { "Cannot grant negative quantity" }
        val player = getOrCreatePlayer()
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        inventory[key] = ((inventory[key] ?: 0).toLong() + qty).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        playerDao.upsert(player.copy(
            inventory = json.encode<Map<String, Int>>(inventory),
            flags     = json.encode<PlayerFlags>(flags.plusSeen(listOf(key))),
        ))
    }

    /** Returns false if the player has insufficient coins. */
    suspend fun spendCoins(amount: Long): Boolean = playerMutex.withLock { spendCoinsUnlocked(amount) }

    internal suspend fun spendCoinsUnlocked(amount: Long): Boolean {
        require(amount >= 0) { "Cannot spend negative coins" }
        val player = getOrCreatePlayer()
        if (player.coins < amount) return false
        playerDao.upsert(player.copy(coins = player.coins - amount))
        return true
    }

    /**
     * Rolls the daily boss coin soft cap for one victorious kill: the first
     * [BOSS_FULL_COIN_KILLS_PER_DAY] kills of each boss each day pay full coins, later ones pay
     * [BOSS_COIN_SOFT_CAP_MULT]. Increments that boss's counter and returns this kill's multiplier.
     */
    suspend fun rollBossCoinSoftCap(bossKey: String): Double = playerMutex.withLock {
        val flags = getFlagsUnlocked()
        val today = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.YEAR) * 10000 + it.get(java.util.Calendar.MONTH) * 100 + it.get(java.util.Calendar.DAY_OF_MONTH)
        }
        val counts = if (flags.bossCoinDay == today) flags.bossCoinKillsByBoss else emptyMap()
        val count = counts[bossKey] ?: 0
        updateFlagsUnlocked(flags.copy(bossCoinDay = today, bossCoinKillsByBoss = counts + (bossKey to count + 1)))
        if (count < BOSS_FULL_COIN_KILLS_PER_DAY) 1.0 else BOSS_COIN_SOFT_CAP_MULT
    }

    /** Toggles [itemKey]'s sell-lock. Returns true if the item is now locked. */
    suspend fun toggleItemLock(itemKey: String): Boolean = playerMutex.withLock {
        val flags = getFlagsUnlocked()
        val wasLocked = itemKey in flags.lockedItems
        updateFlagsUnlocked(flags.copy(
            lockedItems = if (wasLocked) flags.lockedItems - itemKey else flags.lockedItems + itemKey,
        ))
        !wasLocked
    }

    /** Full-coin kills still available today for [bossKey], for display. */
    fun bossFullCoinKillsLeft(flags: PlayerFlags, bossKey: String): Int {
        val today = java.util.Calendar.getInstance().let {
            it.get(java.util.Calendar.YEAR) * 10000 + it.get(java.util.Calendar.MONTH) * 100 + it.get(java.util.Calendar.DAY_OF_MONTH)
        }
        val count = if (flags.bossCoinDay == today) flags.bossCoinKillsByBoss[bossKey] ?: 0 else 0
        return (BOSS_FULL_COIN_KILLS_PER_DAY - count).coerceAtLeast(0)
    }

    suspend fun updateFlags(flags: PlayerFlags) = playerMutex.withLock { updateFlagsUnlocked(flags) }

    suspend fun updateFlagsAtomically(block: (PlayerFlags) -> PlayerFlags) = playerMutex.withLock {
        val current = getFlagsUnlocked()
        updateFlagsUnlocked(block(current))
    }

    /** Stops an in-progress boss repeat run (e.g. on abandon) so it doesn't leave stale "N/M" progress behind. */
    suspend fun clearActiveBossRepeat() = playerMutex.withLock { clearActiveBossRepeatUnlocked() }

    internal suspend fun clearActiveBossRepeatUnlocked() {
        val flags = getFlagsUnlocked()
        if (flags.activeBossRepeatTotal > 0) {
            updateFlagsUnlocked(flags.copy(activeBossRepeatIndex = 0, activeBossRepeatTotal = 0, activeBossRepeatSnapshot = null))
        }
    }

    /** Stops an in-progress dungeon repeat run (e.g. on abandon) so it doesn't leave stale "N/M" progress behind. */
    suspend fun clearActiveDungeonRepeat() = playerMutex.withLock { clearActiveDungeonRepeatUnlocked() }

    internal suspend fun clearActiveDungeonRepeatUnlocked() {
        val flags = getFlagsUnlocked()
        if (flags.activeDungeonRepeatTotal > 0) {
            updateFlagsUnlocked(flags.copy(activeDungeonRepeatIndex = 0, activeDungeonRepeatTotal = 0, activeDungeonRepeatSnapshot = null))
        }
    }

    /** Called after a dungeon [QueuedAction] is freshly dequeued and started, to (re)initialise repeat progress. */
    internal suspend fun stampDungeonRepeatStartUnlocked(action: QueuedAction) {
        if (action.repeatCount > 1) {
            updateFlagsUnlocked(getFlagsUnlocked().copy(
                activeDungeonRepeatIndex    = 1,
                activeDungeonRepeatTotal    = action.repeatCount,
                activeDungeonRepeatSnapshot = action,
            ))
        } else {
            clearActiveDungeonRepeatUnlocked()
        }
    }

    /** Called after a boss [QueuedAction] is freshly dequeued and started, to (re)initialise repeat progress. */
    internal suspend fun stampBossRepeatStartUnlocked(action: QueuedAction) {
        if (action.repeatCount > 1) {
            updateFlagsUnlocked(getFlagsUnlocked().copy(
                activeBossRepeatIndex    = 1,
                activeBossRepeatTotal    = action.repeatCount,
                activeBossRepeatSnapshot = action,
            ))
        } else {
            clearActiveBossRepeatUnlocked()
        }
    }

    suspend fun <T> withLock(block: suspend () -> T): T = playerMutex.withLock { block() }

    internal suspend fun updateFlagsUnlocked(flags: PlayerFlags) {
        val player = getOrCreatePlayer()
        playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(flags)))
    }

    suspend fun getQueue(): List<QueuedAction> = getFlags().sessionQueue

    /** Base queue size (8) plus any Queue Master town building bonus, plus the Monument's Gilded stage. */
    fun maxQueueSize(flags: PlayerFlags): Int {
        var extraSlots = boostRepo.extraQueueSlots(flags)
        flags.townBuildingTiers.forEach { (buildingName, tier) ->
            val bonuses = gameData.townBuildings[buildingName]?.tiers?.getOrNull(tier - 1)?.bonuses
            extraSlots += bonuses?.get("queue_slots")?.toInt() ?: 0
        }
        if (flags.monumentTier >= 4) extraSlots += 1
        return 8 + extraSlots
    }

    /** Appends an action to the queue. Returns false (no change) if the queue is already full. */
    suspend fun enqueueAction(action: QueuedAction): Boolean = playerMutex.withLock { enqueueActionUnlocked(action) }

    private suspend fun enqueueActionUnlocked(action: QueuedAction): Boolean {
        val flags = getFlags()
        if (flags.sessionQueue.size >= maxQueueSize(flags)) return false
        updateFlagsUnlocked(flags.copy(sessionQueue = flags.sessionQueue + action))
        return true
    }

    /** Creates and enqueues a combat (dungeon) session for a Slayer task's auto-advance. Returns false if queue is full. */
    suspend fun enqueueCombatSession(dungeonKey: String, dungeonDisplayName: String): Boolean = playerMutex.withLock {
        val flags = getFlags()
        if (flags.sessionQueue.size >= maxQueueSize(flags)) return@withLock false
        val player = getOrCreatePlayer()
        val levels: Map<String, Int> = json.decodeFromString(player.skillLevels)
        val agility = levels[Skills.AGILITY] ?: 1
        val agilityFloorReduction = boostRepo.sessionFloorReductionMin(flags)
        val equipped: Map<String, String?> = json.decodeFromString(player.equipped)
        val weaponSlot = flags.activeWeaponSlot
            ?: EquipSlot.WEAPON_SLOTS.firstOrNull { equipped[it] != null }
            ?: EquipSlot.WEAPON_ATK
        val chronosReduction = flags.townBuildingTiers.entries.sumOf { (b, t) ->
            gameData.townBuildings[b]?.tiers?.getOrNull(t - 1)?.bonuses?.get("player_session_speed_reduction")?.toDouble() ?: 0.0
        }.toFloat()
        val chronosMult = (1.0f - chronosReduction).coerceAtLeast(0.5f)
        enqueueActionUnlocked(QueuedAction(
            skillName           = "combat",
            activityKey         = dungeonKey,
            skillDisplayName    = dungeonDisplayName,
            estimatedDurationMs = SkillSimulator.sessionDurationMs(agility, agilityFloorReduction, chronosMult),
            equippedSnapshot    = player.equipped,
            arrowsKey           = flags.equippedArrows,
            spellName           = flags.activeSpell,
            potionKey           = flags.activePotionKey,
            weaponSlot          = weaponSlot,
        ))
    }

    /** Removes and returns the first item in the queue, or null if empty. */
    suspend fun dequeueNextAction(): QueuedAction? = playerMutex.withLock { dequeueNextActionUnlocked() }

    internal suspend fun dequeueNextActionUnlocked(): QueuedAction? {
        val flags = getFlags()
        val queue = flags.sessionQueue
        if (queue.isEmpty()) return null
        updateFlagsUnlocked(flags.copy(sessionQueue = queue.drop(1)))
        return queue.first()
    }

    suspend fun requeueActionAtFront(action: QueuedAction) = playerMutex.withLock { requeueActionAtFrontUnlocked(action) }

    internal suspend fun requeueActionAtFrontUnlocked(action: QueuedAction) {
        val flags = getFlags()
        updateFlagsUnlocked(flags.copy(sessionQueue = listOf(action) + flags.sessionQueue))
    }

    private fun PlayerFlags.workerForSlot(slot: Int) = if (slot == 2) hiredWorker2 else hiredWorker
    private fun PlayerFlags.withWorkerForSlot(slot: Int, w: HiredWorker?) =
        if (slot == 2) copy(hiredWorker2 = w) else copy(hiredWorker = w)

    /** Appends an action to the given worker slot's queue. Returns false if full (1 item) or no worker hired. */
    suspend fun enqueueWorkerAction(slot: Int, action: QueuedAction): Boolean = playerMutex.withLock { enqueueWorkerActionUnlocked(slot, action) }

    internal suspend fun enqueueWorkerActionUnlocked(slot: Int, action: QueuedAction): Boolean {
        val flags = getFlags()
        val worker = flags.workerForSlot(slot) ?: return false
        if (worker.sessionQueue.size >= 1) return false
        updateFlagsUnlocked(flags.withWorkerForSlot(slot, worker.copy(sessionQueue = worker.sessionQueue + action)))
        return true
    }

    /** Removes and returns the first item in the given slot's queue, or null if empty/no worker. */
    suspend fun dequeueNextWorkerAction(slot: Int): QueuedAction? = playerMutex.withLock { dequeueNextWorkerActionUnlocked(slot) }

    internal suspend fun dequeueNextWorkerActionUnlocked(slot: Int): QueuedAction? {
        val flags = getFlags()
        val worker = flags.workerForSlot(slot) ?: return null
        val queue = worker.sessionQueue
        if (queue.isEmpty()) return null
        updateFlagsUnlocked(flags.withWorkerForSlot(slot, worker.copy(sessionQueue = queue.drop(1))))
        return queue.first()
    }

    suspend fun requeueWorkerActionAtFront(slot: Int, action: QueuedAction) = playerMutex.withLock { requeueWorkerActionAtFrontUnlocked(slot, action) }

    internal suspend fun requeueWorkerActionAtFrontUnlocked(slot: Int, action: QueuedAction) {
        val flags = getFlags()
        val worker = flags.workerForSlot(slot) ?: return
        updateFlagsUnlocked(flags.withWorkerForSlot(slot, worker.copy(sessionQueue = listOf(action) + worker.sessionQueue)))
    }

    suspend fun clearHiredWorker(slot: Int) = playerMutex.withLock {
        val flags = getFlags()
        updateFlagsUnlocked(flags.withWorkerForSlot(slot, null))
    }

    /** Removes and returns the queued item at [index], or null if out of range. */
    suspend fun removeFromQueue(index: Int): QueuedAction? {
        val flags = getFlags()
        val queue = flags.sessionQueue
        if (index < 0 || index >= queue.size) return null
        val removed = queue[index]
        val newQueue = queue.toMutableList().apply { removeAt(index) }
        updateFlags(flags.copy(sessionQueue = renumberTowerQueue(newQueue, flags.towerCurrentFloor)))
        return removed
    }

    /**
     * Keeps queued Infinite Tower floors contiguous after a cancellation, so a player can't
     * skip floors by cancelling low entries while a higher one survives in the queue.
     */
    private fun renumberTowerQueue(queue: List<QueuedAction>, currentFloor: Int): List<QueuedAction> {
        var nextFloor = currentFloor + 1
        return queue.map { action ->
            if (action.skillName != "tower") return@map action
            val renumbered = action.copy(
                activityKey      = "tower_floor_$nextFloor",
                skillDisplayName = "Infinite Tower: Floor $nextFloor",
            )
            nextFloor++
            renumbered
        }
    }

    suspend fun evictQueueForSkill(skillName: String): List<QueuedAction> {
        val flags = getFlags()
        val (evicted, remaining) = flags.sessionQueue.partition { it.skillName == skillName }
        if (evicted.isNotEmpty()) updateFlags(flags.copy(sessionQueue = remaining))
        return evicted
    }

    suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val flags = getFlags()
        val queue = flags.sessionQueue.toMutableList()
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= queue.size || toIndex >= queue.size) return
        val item = queue.removeAt(fromIndex)
        queue.add(toIndex, item)
        updateFlags(flags.copy(sessionQueue = queue))
    }

    suspend fun incrementDungeonRun(activityKey: String) {
        val flags = getFlags()
        val updated = flags.dungeonRuns.toMutableMap()
        updated[activityKey] = (updated[activityKey] ?: 0) + 1
        updateFlags(flags.copy(dungeonRuns = updated))
    }

    suspend fun markWhatsNewSeen(versionCode: Int) {
        updateFlags(getFlags().copy(lastSeenVersionCode = versionCode))
    }

    /**
     * [ironman] is only non-null from the first-time creation sheet; edits never change it.
     * Post-setup race changes are rejected here: they cost a token or coins and go through
     * [changeCharacterRace]. New ironman characters get their race locked at creation.
     */
    suspend fun updateCharacterProfile(name: String, gender: String, race: String, ironman: Boolean? = null): PrestigeActionResult = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val nowIronman = ironman ?: flags.ironman
        val raceChanged = flags.characterSetupDone &&
            race.lowercase() != PrestigeBoosts.playerRace(flags)
        // Post-setup race changes cost a token or coins and go through [changeCharacterRace];
        // this path only handles first-time setup plus name and gender edits.
        if (raceChanged) return@withLock PrestigeActionResult.INVALID
        val updated = flags.copy(
            characterName = name,
            characterGender = gender,
            characterRace = race,
            characterSetupDone = true,
            ironman = nowIronman,
            // New ironman characters can never change race; the choice is final at creation.
            ironmanRaceLocked = flags.ironmanRaceLocked || (nowIronman && !flags.characterSetupDone),
        )
        playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(updated)))
        PrestigeActionResult.SUCCESS
    }

    /**
     * Race-change bookkeeping: refunds purchased nodes locked to other races
     * (their point cost returns as unspent) and stamps the change time.
     */
    private fun applyRaceChange(flags: PlayerFlags, race: String, now: Long): PlayerFlags {
        val newRace = race.lowercase()
        val prunedNodes = flags.prestigeNodes.mapValues { (skill, ids) ->
            val nodesById = gameData.prestigeTrees[skill]?.paths
                ?.flatMap { it.nodes }?.associateBy { it.id }.orEmpty()
            ids.filter { id -> nodesById[id]?.races.let { r -> r == null || newRace in r } }
        }.filterValues { it.isNotEmpty() }
        return flags.copy(characterRace = race, raceLastChangedAt = now, prestigeNodes = prunedNodes)
    }

    /**
     * Appearance-sheet race change. Costs one Race Change Token (rare boss drop) or
     * [RACE_CHANGE_COST_COINS], chosen via [useToken]. Ironman characters cannot change
     * race at all, except one free legacy change while [PlayerFlags.ironmanRaceLocked]
     * is still false, after which it locks permanently. Same-race saves are free.
     */
    suspend fun changeCharacterRace(race: String, useToken: Boolean = false): PrestigeActionResult = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        if (race.lowercase() == PrestigeBoosts.playerRace(flags)) {
            playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(flags.copy(characterRace = race))))
            return@withLock PrestigeActionResult.SUCCESS
        }
        if (flags.ironman) {
            if (flags.ironmanRaceLocked) return@withLock PrestigeActionResult.LOCKED
            val updated = applyRaceChange(flags, race, System.currentTimeMillis())
            playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(updated.copy(ironmanRaceLocked = true))))
            return@withLock PrestigeActionResult.SUCCESS
        }
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        if (useToken) {
            if ((inventory[RACE_CHANGE_TOKEN_ITEM] ?: 0) < 1) return@withLock PrestigeActionResult.CANT_AFFORD
        } else {
            if (player.coins < RACE_CHANGE_COST_COINS) return@withLock PrestigeActionResult.CANT_AFFORD
        }
        val updated = applyRaceChange(flags, race, System.currentTimeMillis())
        if (useToken) {
            val left = (inventory[RACE_CHANGE_TOKEN_ITEM] ?: 0) - 1
            if (left <= 0) inventory.remove(RACE_CHANGE_TOKEN_ITEM) else inventory[RACE_CHANGE_TOKEN_ITEM] = left
            playerDao.upsert(player.copy(
                inventory = json.encode<Map<String, Int>>(inventory),
                flags     = json.encode<PlayerFlags>(updated),
            ))
        } else {
            playerDao.upsert(player.copy(
                coins = player.coins - RACE_CHANGE_COST_COINS,
                flags = json.encode<PlayerFlags>(updated),
            ))
        }
        PrestigeActionResult.SUCCESS
    }

    suspend fun debugChangeRaceFree(race: String) {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val prunedNodes = flags.prestigeNodes.mapValues { (skill, ids) ->
            val nodesById = gameData.prestigeTrees[skill]?.paths
                ?.flatMap { it.nodes }?.associateBy { it.id }.orEmpty()
            ids.filter { id -> nodesById[id]?.races.let { r -> r == null || race.lowercase() in r } }
        }.filterValues { it.isNotEmpty() }
        val updatedFlags = flags.copy(characterRace = race, prestigeNodes = prunedNodes)
        playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(updatedFlags)))
    }

    suspend fun dismissCharacterSetup() {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(flags.copy(characterSetupDone = true))))
    }

    suspend fun updateEquipped(equipped: Map<String, String?>) {
        val player = getOrCreatePlayer()
        playerDao.upsert(player.copy(equipped = json.encode<Map<String, String?>>(equipped)))
    }

    /**
     * Re-applies [style]'s remembered loadout: armor (EquipSlot.ARMOR_SLOTS; weapons are
     * untouched, since each style already has its own persistent weapon slot), plus the
     * remembered arrow (ranged) or spell (magic). Slots/values never recorded for this style are
     * left exactly as currently equipped, and the applied result is then snapshotted back as the
     * style's complete loadout so the next switch is deterministic. Entries referencing an item
     * the player no longer owns, or doesn't meet the level requirement for, are skipped silently.
     */
    suspend fun applyLoadout(style: String, equipment: Map<String, EquipmentData>) {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val inventory: Map<String, Int> = json.decodeFromString(player.inventory)
        val skillLevels: Map<String, Int> = json.decodeFromString(player.skillLevels)
        val currentEquipped: Map<String, String?> = json.decodeFromString(player.equipped)
        val newEquipped = currentEquipped.toMutableMap()

        val loadout = flags.armorLoadouts[style]
        if (!loadout.isNullOrEmpty()) {
            // If this style's own weapon is two-handed, SHIELD must stay off -- mirrors the existing
            // two-handed/shield exclusivity rule in InventoryViewModel.equip(), which never fires here
            // since applying a loadout never touches a weapon slot.
            val weaponSlotForStyle = EquipSlot.WEAPON_SLOTS.firstOrNull { EquipSlot.combatStyleForSlot(it) == style }
            val twoHanded = equipment[currentEquipped[weaponSlotForStyle]]?.twoHanded == true

            for (slot in EquipSlot.ARMOR_SLOTS) {
                if (!loadout.containsKey(slot)) continue
                if (slot == EquipSlot.SHIELD && twoHanded) continue
                val configuredKey = loadout[slot]
                if (configuredKey == null) {
                    newEquipped[slot] = null
                } else {
                    val item = equipment[configuredKey]
                    val owned = (inventory[configuredKey] ?: 0) > 0
                    val levelOk = item != null && item.requirements.all { (skill, lvl) -> (skillLevels[skill] ?: 1) >= lvl }
                    if (item != null && owned && levelOk) newEquipped[slot] = configuredKey
                    // else: skip -- leave whatever's currently there
                }
            }
        }
        if (newEquipped != currentEquipped) updateEquipped(newEquipped)

        var newFlags = flags
        // Snapshot the applied result as this style's complete loadout. Legacy sparse
        // loadouts only pinned explicitly-changed slots, so the rest inherited the
        // previous tab's gear and switching was path-dependent (issue #1224).
        val snapshot = EquipSlot.ARMOR_SLOTS.associateWith { newEquipped[it] }
        if (flags.armorLoadouts[style] != snapshot) {
            newFlags = newFlags.copy(armorLoadouts = flags.armorLoadouts + (style to snapshot))
        }
        if (style == "ranged") {
            val arrowKey = flags.rangedLoadoutArrowKey
            if (arrowKey != null && (inventory[arrowKey] ?: 0) > 0) newFlags = newFlags.copy(equippedArrows = arrowKey)
        } else if (style == "magic") {
            val spellName = flags.magicLoadoutSpellName
            if (spellName != null) newFlags = newFlags.copy(activeSpell = spellName)
        }
        if (newFlags != flags) updateFlags(newFlags)
    }

    suspend fun updatePets(pets: List<OwnedPet>) {
        val player = getOrCreatePlayer()
        playerDao.upsert(player.copy(pets = json.encode<List<OwnedPet>>(pets)))
    }

    /** Buy [qty] of [itemKey] at [priceEach] coins. Returns false if insufficient coins. */
    suspend fun buyItem(itemKey: String, qty: Int, priceEach: Int): Boolean {
        val player = getOrCreatePlayer()
        val total  = priceEach.toLong() * qty
        if (player.coins < total) return false
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        inventory[itemKey] = (inventory[itemKey] ?: 0) + qty
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        playerDao.upsert(
            player.copy(
                coins     = player.coins - total,
                inventory = json.encode<Map<String, Int>>(inventory),
                flags     = json.encode<PlayerFlags>(flags.plusSeen(listOf(itemKey))),
            )
        )
        return true
    }

    /** Sell [qty] of [itemKey] for [priceEach] coins each. Returns false if not enough in inventory. Unequips the item if no copies remain. */
    suspend fun sellItem(itemKey: String, qty: Int, priceEach: Int): Boolean {
        val player = getOrCreatePlayer()
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        if ((inventory[itemKey] ?: 0) < qty) return false
        val remaining = (inventory[itemKey] ?: 0) - qty
        if (remaining <= 0) inventory.remove(itemKey) else inventory[itemKey] = remaining

        val equipped: MutableMap<String, String?> = json.decodeFromString(player.equipped)
        if (!inventory.containsKey(itemKey)) {
            equipped.entries.forEach { if (it.value == itemKey) it.setValue(null) }
        }

        playerDao.upsert(
            player.copy(
                coins     = player.coins + priceEach.toLong() * qty,
                inventory = json.encode<Map<String, Int>>(inventory),
                equipped  = json.encode<Map<String, String?>>(equipped),
            )
        )
        return true
    }

    /**
     * Apply combat session results: XP distributed across multiple skills (doubled if
     * boost active), loot added to inventory, coins added to the coins field.
     * Returns the keys of any skill capes awarded (level 99 reached for the first time).
     */
    suspend fun applyMultiSkillResults(
        xpPerSkill: Map<String, Long>,
        itemsGained: Map<String, Int>,
        coinsGained: Long = 0L,
        efficiencyMultiplier: Float = 1.0f,
        perSkillPetBoostPct: Map<String, Int> = emptyMap(),
    ): List<String> = playerMutex.withLock {
        applyMultiSkillResultsUnlocked(xpPerSkill, itemsGained, coinsGained, efficiencyMultiplier, perSkillPetBoostPct)
    }

    internal suspend fun applyMultiSkillResultsUnlocked(
        xpPerSkill: Map<String, Long>,
        itemsGained: Map<String, Int>,
        coinsGained: Long = 0L,
        efficiencyMultiplier: Float = 1.0f,
        perSkillPetBoostPct: Map<String, Int> = emptyMap(),
    ): List<String> {
        val player    = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val capeMult = prayerCapeMult(player, flags)
        val coinBlessingMult = if (flags.ironman) 1.0f else ChurchRepository.coinMultiplier(flags, capeMult) *
            gooseCoinMultiplier(json.decodeFromString(player.pets)).toFloat()
        val scaledItems = if (efficiencyMultiplier == 1.0f) itemsGained
            else itemsGained.mapValues { (_, v) -> (v * efficiencyMultiplier).roundToInt().coerceAtLeast(1) }

        val levels:    MutableMap<String, Int>  = json.decodeFromString(player.skillLevels)
        val xpMap:     MutableMap<String, Long> = json.decodeFromString(player.skillXp)
        val inventory: MutableMap<String, Int>  = json.decodeFromString(player.inventory)

        val awardedCapes = mutableListOf<String>()
        for ((skill, xp) in xpPerSkill) {
            val oldLevel = XpTable.levelForXp(xpMap[skill] ?: 0L)
            val ratedXp = xp * BASE_XP_RATE_MULTIPLIER
            val scaledXp = if (efficiencyMultiplier == 1.0f) ratedXp else (ratedXp * efficiencyMultiplier).toLong()
            val petPct = if (flags.ironman) 0 else perSkillPetBoostPct[skill] ?: 0
            val withPet = if (petPct > 0) (scaledXp * (1.0 + petPct / 100.0)).toLong() else scaledXp
            val finalXp = (withPet * boostRepo.xpMultiplier(skill, flags, capeMult)).toLong()
            val newXp = (xpMap[skill] ?: 0L) + finalXp
            xpMap[skill]  = newXp
            levels[skill] = XpTable.levelForXp(newXp)
            if (oldLevel < 99 && levels[skill]!! >= 99) {
                val capeKey = capeKeyForSkill(skill)
                if (capeKey != null && !inventory.containsKey(capeKey)) {
                    inventory[capeKey] = 1
                    awardedCapes += capeKey
                }
            }
        }
        for ((item, qty) in scaledItems) {
            inventory[item] = (inventory[item] ?: 0) + qty
        }

        playerDao.upsert(
            player.copy(
                skillLevels = json.encode<Map<String, Int>>(levels),
                skillXp     = json.encode<Map<String, Long>>(xpMap),
                inventory   = json.encode<Map<String, Int>>(inventory),
                coins       = player.coins + (coinsGained * coinBlessingMult).toLong(),
                flags       = json.encode<PlayerFlags>(flags.plusSeen(scaledItems.keys + awardedCapes)),
            )
        )
        return awardedCapes
    }

    data class FlatXpBreakdown(
        val baseXp: Long,
        val finalXp: Long,
        val boostActive: Boolean,
        val blessingMult: Float,
        val prestigeXpPct: Int,
    )

    /**
     * Read-only preview of what a flat XP grant (quest/guild-quest/XP lamp reward) to [skillName]
     * will actually total once boost/blessing/prestige are applied, mirroring the math in
     * [applySessionResults]/[applyMultiSkillResultsUnlocked]. Used so confirmation UI can disclose
     * the real credited XP instead of the pre-multiplier flat amount.
     */
    suspend fun previewFlatXpGrant(skillName: String, baseXp: Long): FlatXpBreakdown {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val capeMult = prayerCapeMult(player, flags)
        val boostActive = boostRepo.xpBoostActive(skillName, flags)
        val blessingMult = if (flags.ironman) 1.0f else ChurchRepository.xpMultiplier(flags, capeMult)
        val prestigeXpPct = boostRepo.prestigeXpPct(skillName, flags)
        // Mirror the mod's doubled base rate so the disclosed amount matches what is credited.
        val ratedXp = baseXp * BASE_XP_RATE_MULTIPLIER
        val finalXp = (ratedXp * boostRepo.xpMultiplier(skillName, flags, capeMult)).toLong()
        return FlatXpBreakdown(ratedXp, finalXp, boostActive, blessingMult, prestigeXpPct)
    }

    /**
     * Activates the 2× XP boost for [durationMs]. Refused while a boost is already running
     * (no stacking) and limited to one purchase per weekly reset (Monday 6am, same clock as
     * weekly quests). Deducts [cost] coins on success.
     */
    suspend fun activateXpBoost(durationMs: Long, cost: Long = XP_BOOST_COST): XpBoostPurchaseResult {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val now = System.currentTimeMillis()

        if (flags.xpBoostExpiresAt > now) return XpBoostPurchaseResult.ALREADY_ACTIVE
        if (flags.xpBoostLastPurchaseAt > 0 && now < weeklyQuestRepo.nextResetMs(flags.xpBoostLastPurchaseAt, flags.dailyResetHour)) {
            return XpBoostPurchaseResult.WEEKLY_LIMIT_REACHED
        }
        if (player.coins < cost) return XpBoostPurchaseResult.NOT_ENOUGH_COINS

        val newExpiry = now + durationMs
        playerDao.upsert(
            player.copy(
                coins = player.coins - cost,
                flags = json.encode<PlayerFlags>(flags.copy(
                    xpBoostExpiresAt      = newExpiry,
                    xpBoostLastPurchaseAt = now,
                )),
            )
        )
        buffNotifScheduler.cancelXpBoostExpiry()
        buffNotifScheduler.scheduleXpBoostExpiry(newExpiry)
        return XpBoostPurchaseResult.SUCCESS
    }

    /**
     * Grants [durationMs] of 2× XP boost as a reward (seasonal event tiers). No cost, exempt
     * from the purchase limits, and extends any boost already running so the reward is never lost.
     */
    suspend fun grantXpBoost(durationMs: Long) {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val now        = System.currentTimeMillis()
        val currentEnd = if (flags.xpBoostExpiresAt > now) flags.xpBoostExpiresAt else now
        val newExpiry  = currentEnd + durationMs

        playerDao.upsert(
            player.copy(flags = json.encode<PlayerFlags>(flags.copy(xpBoostExpiresAt = newExpiry)))
        )
        buffNotifScheduler.cancelXpBoostExpiry()
        buffNotifScheduler.scheduleXpBoostExpiry(newExpiry)
    }

    /** Bronze/starter fallback item granted to a slot if prestige invalidates its gear and nothing else in inventory qualifies. */
    private val prestigeStarterGearForSlot = mapOf(
        EquipSlot.WEAPON_ATK    to "bronze_sword",
        EquipSlot.WEAPON_STR    to "bronze_warhammer",
        EquipSlot.WEAPON_RANGED to "wooden_bow",
        EquipSlot.WEAPON_MAGIC  to "basic_staff",
        EquipSlot.HEAD          to "bronze_full_helmet",
        EquipSlot.BODY          to "bronze_platebody",
        EquipSlot.LEGS          to "bronze_platelegs",
        EquipSlot.SHIELD        to "bronze_kiteshield",
        EquipSlot.BOOTS         to "bronze_boots",
        EquipSlot.PICKAXE       to "bronze_pickaxe",
        EquipSlot.AXE           to "bronze_axe",
        EquipSlot.FISHING_ROD   to "bronze_fishing_rod",
        EquipSlot.HOE           to "bronze_hoe",
    )

    /**
     * Resets [skillName] back to level 1, increments its prestige count, and awards
     * prestige points ([PrestigePoints.pointsForXp]: 2 at level 99, more for banked
     * XP past 99). Guards: level 99+, not ironman, and lifetime earned points below
     * the tree's cap for the player's race.
     *
     * Also re-validates every equipped slot against the new (lower) levels: gear that no
     * longer meets its requirements is swapped for the best valid item in inventory, or a
     * bronze/starter fallback, or unequipped if neither is available.
     */
    suspend fun prestigeSkill(skillName: String) = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val levels: MutableMap<String, Int>  = json.decodeFromString(player.skillLevels)
        val xpMap:  MutableMap<String, Long> = json.decodeFromString(player.skillXp)
        val flags: PlayerFlags               = json.decodeFromString(player.flags)

        val currentPrestige = flags.skillPrestige[skillName] ?: 0
        if ((levels[skillName] ?: 1) < 99) return@withLock
        val cap = PrestigeBoosts.pointCapForRace(gameData.prestigeTrees[skillName], PrestigeBoosts.playerRace(flags))
        val earnedSoFar = flags.prestigePointsEarned[skillName] ?: 0
        if (cap in 1..earnedSoFar) return@withLock

        val pointsAwarded = PrestigePoints.pointsForXp(xpMap[skillName] ?: 0L)
        val newEarned = flags.prestigePointsEarned.toMutableMap()
        newEarned[skillName] = (earnedSoFar + pointsAwarded).let { if (cap > 0) it.coerceAtMost(cap) else it }

        levels[skillName] = 1
        xpMap[skillName]  = 0L
        val newPrestige = flags.skillPrestige.toMutableMap()
        newPrestige[skillName] = currentPrestige + 1

        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        val equipped: MutableMap<String, String?> = json.decodeFromString(player.equipped)
        val allEquip = gameData.equipment

        for (slot in EquipSlot.ALL) {
            val currentKey = equipped[slot]
            val currentValid = currentKey == null ||
                (allEquip[currentKey]?.requirements?.all { (skill, lvl) -> (levels[skill] ?: 1) >= lvl } == true)
            if (currentValid) continue

            val style = EquipSlot.combatStyleForSlot(slot)
            val bestFromInv = inventory.keys
                .mapNotNull { k -> allEquip[k]?.let { eq -> k to eq } }
                .filter { (_, eq) -> if (style != null) eq.slot == "weapon" && eq.combatStyle == style else eq.slot == slot }
                .filter { (_, eq) -> eq.requirements.all { (skill, lvl) -> (levels[skill] ?: 1) >= lvl } }
                .maxByOrNull { (_, eq) -> eq.attackBonus + eq.strengthBonus + eq.defenseBonus }
                ?.first

            val starterKey = prestigeStarterGearForSlot[slot]
            when {
                bestFromInv != null -> equipped[slot] = bestFromInv
                starterKey != null && allEquip[starterKey] != null -> {
                    inventory[starterKey] = (inventory[starterKey] ?: 0) + 1
                    equipped[slot] = starterKey
                }
                else -> equipped[slot] = null
            }
        }

        var newFlags = flags.copy(
            skillPrestige        = newPrestige,
            prestigePointsEarned = newEarned,
            // Compensation for the banked-dailies reset below: a 48h 2x XP boost for just
            // this skill, so the fast climb back is a feature instead of an exploit.
            prestigeXpBoosts     = flags.prestigeXpBoosts +
                (skillName to System.currentTimeMillis() + PRESTIGE_XP_BOOST_DURATION_MS),
        )
        // Completed-but-unclaimed dailies would otherwise bank their flat XP across the
        // reset and cash it in at level 1 for an outsized jump, so unclaimed progress on
        // dailies paying this skill's XP is cleared.
        val bankedDailyIds = gameData.guildDailyPool
            .filter { it.rewards.xpSkill == skillName && it.id !in flags.guildDailyClaimed }
            .map { it.id }
            .toSet()
        if (bankedDailyIds.isNotEmpty()) {
            newFlags = newFlags.copy(guildDailyProgress = newFlags.guildDailyProgress - bankedDailyIds)
        }
        if (skillName == Skills.MAGIC) {
            val magicLevel = levels[Skills.MAGIC] ?: 1
            val activeSpell = newFlags.activeSpell?.let { gameData.spells[it] }
            if (activeSpell != null && activeSpell.magicLevelRequired > magicLevel) {
                val fallback = gameData.spells.values
                    .filter { it.magicLevelRequired <= magicLevel }
                    .maxByOrNull { it.magicLevelRequired }
                newFlags = newFlags.copy(activeSpell = fallback?.name)
            }
        }
        if (skillName == Skills.PRAYER) {
            val prayerLevel = levels[Skills.PRAYER] ?: 1
            val activeBlessing = ChurchRepository.activeBlessing(newFlags)
            if (activeBlessing != null && activeBlessing.prayerLevelRequired > prayerLevel) {
                // The bones are already paid, so the blessing downgrades (keeping its expiry)
                // to the strongest same-type blessing the reset level allows instead of ending.
                val fallback = ChurchRepository.ALL_BLESSINGS
                    .filter { it.type == activeBlessing.type && it.prayerLevelRequired <= prayerLevel }
                    .maxByOrNull { it.prayerLevelRequired }
                newFlags = if (fallback != null) {
                    newFlags.copy(activeBlessingKey = fallback.key)
                } else {
                    buffNotifScheduler.cancelBlessingExpiry()
                    newFlags.copy(activeBlessingKey = "", activeBlessingExpiresAt = 0L)
                }
            }
        }

        playerDao.upsert(
            player.copy(
                skillLevels = json.encode<Map<String, Int>>(levels),
                skillXp     = json.encode<Map<String, Long>>(xpMap),
                flags       = json.encode<PlayerFlags>(newFlags),
                inventory   = json.encode<Map<String, Int>>(inventory),
                equipped    = json.encode<Map<String, String?>>(equipped),
            )
        )
    }

    /**
     * Buys a prestige tree node with unspent points. Validates race lock, the
     * preceding tier in the same path, and available points.
     */
    suspend fun purchasePrestigeNode(skillName: String, nodeId: String): PrestigeActionResult = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val tree = gameData.prestigeTrees[skillName] ?: return@withLock PrestigeActionResult.INVALID
        val path = tree.paths.firstOrNull { p -> p.nodes.any { it.id == nodeId } }
            ?: return@withLock PrestigeActionResult.INVALID
        val index = path.nodes.indexOfFirst { it.id == nodeId }
        val node = path.nodes[index]
        val owned = flags.prestigeNodes[skillName].orEmpty()
        if (nodeId in owned) return@withLock PrestigeActionResult.INVALID
        val race = PrestigeBoosts.playerRace(flags)
        if (!PrestigeBoosts.isNodeAvailableToRace(node, race)) return@withLock PrestigeActionResult.LOCKED
        // Prerequisite: the closest preceding node in this path that this race can use.
        val prereq = path.nodes.take(index).lastOrNull { PrestigeBoosts.isNodeAvailableToRace(it, race) }
        if (prereq != null && prereq.id !in owned) return@withLock PrestigeActionResult.LOCKED
        if (PrestigeBoosts.unspentPoints(gameData.prestigeTrees, flags, skillName) < node.cost) {
            return@withLock PrestigeActionResult.NOT_ENOUGH_POINTS
        }
        playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(
            flags.copy(prestigeNodes = flags.prestigeNodes + (skillName to (owned + nodeId)))
        )))
        PrestigeActionResult.SUCCESS
    }

    /** Refunds every purchased node of [skillName] (points return automatically). 24h cooldown per skill. */
    suspend fun respecPrestige(skillName: String): PrestigeActionResult = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        if (flags.prestigeNodes[skillName].orEmpty().isEmpty()) return@withLock PrestigeActionResult.INVALID
        val now = System.currentTimeMillis()
        if (now - (flags.prestigeLastRespecAt[skillName] ?: 0L) < PRESTIGE_RESPEC_COOLDOWN_MS) {
            return@withLock PrestigeActionResult.COOLDOWN
        }
        playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(flags.copy(
            prestigeNodes        = flags.prestigeNodes - skillName,
            prestigeLastRespecAt = flags.prestigeLastRespecAt + (skillName to now),
        ))))
        PrestigeActionResult.SUCCESS
    }

    /**
     * One-time v1.14.0 migration: convert legacy prestige levels (which used to grant
     * automatic bonuses) into unspent prestige points, 2 per level, for free allocation.
     */
    suspend fun migrateLegacyPrestigePointsIfNeeded() = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        if (flags.prestigePointsMigrated) return@withLock
        val granted = flags.skillPrestige.filterValues { it > 0 }
            .mapValues { (skill, lvl) ->
                (flags.prestigePointsEarned[skill] ?: 0) + lvl * PrestigePoints.LEGACY_POINTS_PER_LEVEL
            }
        playerDao.upsert(player.copy(flags = json.encode<PlayerFlags>(flags.copy(
            prestigePointsEarned   = flags.prestigePointsEarned + granted,
            prestigePointsMigrated = true,
        ))))
    }

    companion object {
        /**
         * Global base XP multiplier applied to every source of skill XP before any other
         * bonuses (boost/blessing/prestige/pet). Set to 2 to double the base exp rate everywhere.
         */
        const val BASE_XP_RATE_MULTIPLIER = 2L

        const val XP_BOOST_COST = 2_500_000L
        const val XP_BOOST_DURATION_MS = 48 * 3_600_000L   // 48 hours

        /** Duration of the skill-specific 2x XP boost granted by each prestige. */
        const val PRESTIGE_XP_BOOST_DURATION_MS = 48 * 3_600_000L

        const val PRESTIGE_RESPEC_COOLDOWN_MS = 24 * 3_600_000L
        const val RACE_CHANGE_TOKEN_ITEM = "race_change_token"
        const val RACE_CHANGE_COST_COINS = 10_000_000L

        /** HMAC key for save-file signatures. Public by nature (open source), deterrence only. */
        private const val SAVE_SIG_KEY = "ekEhdMIDo9B63HQSU80U7hvuqVd1HYcciv5Na5d7gEKdaudR4Voa8jkF"

        /** Kills of each boss per day that pay full coin drops; kills beyond pay [BOSS_COIN_SOFT_CAP_MULT]. */
        const val BOSS_FULL_COIN_KILLS_PER_DAY = 3
        const val BOSS_COIN_SOFT_CAP_MULT = 0.25

        /** Coin-drop multiplier from the Golden Goose pet (Monument stage 4); applies wherever Fortune blessings do. */
        fun gooseCoinMultiplier(pets: List<OwnedPet>): Double =
            1.0 + (pets.firstOrNull { it.id == MonumentRepository.GOLDEN_GOOSE_PET_ID }?.boostPercent ?: 0) / 100.0
    }

    /**
     * Atomically craft [quantity] of a recipe:
     *   1. Verify and consume [materialsPerItem] × [quantity]
     *   2. Add [outputKey] × ([outputQtyPerItem] × [quantity]) to inventory
     *   3. Award [xpPerItem] × [quantity] XP to [skillName]
     *
     * Returns false (no changes) if the player lacks any required material.
     */
    suspend fun applyCraftingResult(
        skillName: String,
        quantity: Int,
        xpPerItem: Double,
        materialsPerItem: Map<String, Int>,
        outputKey: String,
        outputQtyPerItem: Int,
    ): Boolean {
        val player    = getOrCreatePlayer()
        val inventory: MutableMap<String, Int>  = json.decodeFromString(player.inventory)
        val levels:    MutableMap<String, Int>  = json.decodeFromString(player.skillLevels)
        val xpMap:     MutableMap<String, Long> = json.decodeFromString(player.skillXp)

        // Check all materials are available
        for ((item, needed) in materialsPerItem) {
            if ((inventory[item] ?: 0) < needed * quantity) return false
        }

        // Consume materials; input-save prestige nodes refund a fraction of them.
        val flagsForSave: PlayerFlags = json.decodeFromString(player.flags)
        val saveFraction = boostRepo.inputSaveFraction(skillName, flagsForSave)
        for ((item, needed) in materialsPerItem) {
            val consumed = needed * quantity
            val refunded = (consumed * saveFraction).toInt()
            val remaining = (inventory[item] ?: 0) - consumed + refunded
            if (remaining <= 0) inventory.remove(item) else inventory[item] = remaining
        }

        // Add output
        val totalOut = outputQtyPerItem * quantity
        inventory[outputKey] = (inventory[outputKey] ?: 0) + totalOut

        // Add XP and recalculate level
        val xpGained = (xpPerItem * quantity).toLong() * BASE_XP_RATE_MULTIPLIER
        val newXp    = (xpMap[skillName] ?: 0L) + xpGained
        xpMap[skillName]    = newXp
        levels[skillName]   = XpTable.levelForXp(newXp)

        playerDao.upsert(
            player.copy(
                inventory   = json.encode<Map<String, Int>>(inventory),
                skillLevels = json.encode<Map<String, Int>>(levels),
                skillXp     = json.encode<Map<String, Long>>(xpMap),
            )
        )
        return true
    }

    /**
     * Pre-1.8.6 bug: pet drops went into inventory instead of the pet list because
     * pet keys were absent from pets.json. Moves any matching inventory items into
     * the OwnedPet list and removes them from inventory.
     */
    suspend fun migratePetsFromInventory(petKeys: Set<String>) {
        val player = getOrCreatePlayer()
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        val pets: MutableList<OwnedPet> = json.decodeFromString(player.pets)
        val ownedIds = pets.map { it.id }.toSet()
        val toMigrate = petKeys.filter { it in inventory && it !in ownedIds }
        if (toMigrate.isEmpty()) return
        toMigrate.forEach { key ->
            inventory.remove(key)
            pets.add(OwnedPet(id = key, boostPercent = 0))
        }
        playerDao.upsert(player.copy(
            inventory = json.encode<Map<String, Int>>(inventory),
            pets      = json.encode<List<OwnedPet>>(pets),
        ))
    }

    /**
     * Adds [petId] to the player's pet list if not already owned.
     * Returns true if the pet was newly added, false if already owned.
     */
    suspend fun addPetIfNew(petId: String, boostPercent: Int = 0): Boolean = playerMutex.withLock {
        addPetIfNewUnlocked(petId, boostPercent)
    }

    internal suspend fun addPetIfNewUnlocked(petId: String, boostPercent: Int = 0): Boolean {
        val player = getOrCreatePlayer()
        val pets: MutableList<OwnedPet> = json.decodeFromString(player.pets)
        if (pets.any { it.id == petId }) return false
        pets.add(OwnedPet(id = petId, boostPercent = boostPercent))
        playerDao.upsert(player.copy(pets = json.encode<List<OwnedPet>>(pets)))
        return true
    }

    /**
     * Removes [materialsPerItem] × [quantity] from inventory.
     * Returns false (no changes) if the player lacks any required material.
     */
    suspend fun consumeMaterials(
        materialsPerItem: Map<String, Int>,
        quantity: Int,
    ): Boolean {
        val player    = getOrCreatePlayer()
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)

        for ((item, needed) in materialsPerItem) {
            if ((inventory[item] ?: 0) < needed * quantity) return false
        }
        for ((item, needed) in materialsPerItem) {
            val remaining = (inventory[item] ?: 0) - needed * quantity
            if (remaining <= 0) inventory.remove(item) else inventory[item] = remaining
        }
        playerDao.upsert(player.copy(inventory = json.encode<Map<String, Int>>(inventory)))
        return true
    }

    /** Returns a JSON string capturing the full player save including quest progress and sessions. */
    suspend fun exportSave(sessions: List<com.fantasyidler.data.model.SkillSessionExport> = emptyList()): String {
        val player = getOrCreatePlayer()
        val export = PlayerExport(
            skillLevels    = player.skillLevels,
            skillXp        = player.skillXp,
            inventory      = player.inventory,
            equipped       = player.equipped,
            flags          = player.flags,
            pets           = player.pets,
            coins          = player.coins,
            questProgress  = questProgressDao.getAllProgress(),
            farmingPatches = farmingPatchDao.getAllPatches(),
            sessions       = sessions,
            exportedAt     = System.currentTimeMillis(),
        )
        return json.encode<PlayerExport>(export.copy(sig = saveSignature(export)))
    }

    /** Result of [importSave]: the applied export, and whether an edited ironman save was demoted. */
    data class ImportedSave(val export: PlayerExport, val ironmanDemoted: Boolean)

    /**
     * Overwrites the current save with data from a previously exported JSON string.
     * An ironman save whose signature is missing or does not match its core fields was edited
     * outside the game; it still imports, but as a regular (non-ironman) character.
     */
    suspend fun importSave(jsonString: String): ImportedSave {
        var export = json.decodeFromString<PlayerExport>(stripJsonGarbage(jsonString))
        var ironmanDemoted = false
        val importedFlags = try { json.decodeFromString<PlayerFlags>(export.flags) } catch (_: Exception) { null }
        if (importedFlags?.ironman == true && export.sig != saveSignature(export)) {
            export = export.copy(flags = json.encode<PlayerFlags>(importedFlags.copy(ironman = false)))
            ironmanDemoted = true
        }
        val player = getOrCreatePlayer()
        playerDao.upsert(
            player.copy(
                skillLevels = export.skillLevels,
                skillXp     = export.skillXp,
                inventory   = export.inventory,
                equipped    = export.equipped,
                flags       = export.flags,
                pets        = export.pets,
                coins       = export.coins,
            )
        )
        questProgressDao.deleteAll()
        export.questProgress.forEach { questProgressDao.upsert(it) }
        farmingPatchDao.clearAll()
        export.farmingPatches.forEach { farmingPatchDao.upsert(it) }
        return ImportedSave(export, ironmanDemoted)
    }

    /**
     * HMAC-SHA256 over the seven core player fields, joined by newlines. The raw JSON strings
     * round-trip byte-for-byte through export parsing, and later PlayerExport schema additions
     * don't affect the canonical form, so old signed saves stay valid across app versions.
     * Deterrence against hand-editing only: the key is public in this open-source app.
     */
    private fun saveSignature(export: PlayerExport): String {
        val canonical = listOf(
            export.skillLevels, export.skillXp, export.inventory,
            export.equipped, export.flags, export.pets, export.coins.toString(),
        ).joinToString("\n")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(SAVE_SIG_KEY.toByteArray(), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // Finds the end of the root JSON object and drops any trailing garbage.
    // Guards against files that were written twice without truncation.
    private fun stripJsonGarbage(s: String): String {
        var depth = 0
        var inString = false
        var escape = false
        for (i in s.indices) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(0, i + 1) }
            }
        }
        return s
    }

    suspend fun resetProgression(ironman: Boolean = false) {
        playerDao.upsert(createDefaultPlayer(ironman))
    }

    // ------------------------------------------------------------------
    // Daily quest helpers
    // ------------------------------------------------------------------

    /**
     * Runs the 6am daily/weekly refresh plus [transform] on the freshest flags under a single
     * lock hold. The previous flows read flags, transformed, and wrote back across separate
     * lock acquisitions, so a concurrent flags writer (e.g. the prestige flow while a session
     * collect was still recording progress) could clobber either side's update — most visibly
     * resurrecting already-claimed dailies.
     */
    private suspend fun updateRefreshedDailyFlagsAtomically(transform: (PlayerFlags) -> PlayerFlags) = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val original: PlayerFlags = json.decodeFromString(player.flags)
        var flags = original
        if (dailyQuestRepo.shouldRefresh(flags.dailyQuestGeneratedAt, flags.dailyResetHour) ||
            weeklyQuestRepo.shouldRefresh(flags.weeklyQuestGeneratedAt, flags.dailyResetHour)
        ) {
            val skillLevels: Map<String, Int> = json.decodeFromString(player.skillLevels)
            if (dailyQuestRepo.shouldRefresh(flags.dailyQuestGeneratedAt, flags.dailyResetHour)) flags = dailyQuestRepo.refreshFlags(flags, skillLevels)
            if (weeklyQuestRepo.shouldRefresh(flags.weeklyQuestGeneratedAt, flags.dailyResetHour)) flags = weeklyQuestRepo.refreshFlags(flags, skillLevels)
        }
        flags = transform(flags)
        if (flags != original) updateFlagsUnlocked(flags)
    }

    /** Refresh daily and weekly quests if past 6am, then record progress for a gather session. */
    suspend fun recordDailyGathering(items: Map<String, Int>) = updateRefreshedDailyFlagsAtomically { refreshed ->
        var flags = refreshed
        for ((target, amount) in items) {
            flags = dailyQuestRepo.recordProgress(flags, "gather", target, amount)
            flags = weeklyQuestRepo.recordProgress(flags, "gather", target, amount)
        }
        flags
    }

    /** Refresh daily and weekly quests if past 6am, then record progress for a crafting session. */
    suspend fun recordDailyCrafting(items: Map<String, Int>) = updateRefreshedDailyFlagsAtomically { refreshed ->
        var flags = refreshed
        for ((target, amount) in items) {
            flags = dailyQuestRepo.recordProgress(flags, "craft", target, amount)
            flags = weeklyQuestRepo.recordProgress(flags, "craft", target, amount)
        }
        flags
    }

    /** Refresh daily and weekly quests if past 6am, then record progress for combat kills. */
    suspend fun recordDailyKills(killsByEnemy: Map<String, Int>) = updateRefreshedDailyFlagsAtomically { refreshed ->
        var flags = refreshed
        for ((enemy, count) in killsByEnemy) {
            flags = dailyQuestRepo.recordProgress(flags, "kill_enemy", enemy, count)
            flags = weeklyQuestRepo.recordProgress(flags, "kill_enemy", enemy, count)
        }
        if (killsByEnemy.isNotEmpty()) {
            val updated = flags.enemyKills.toMutableMap()
            for ((enemy, count) in killsByEnemy) updated[enemy] = (updated[enemy] ?: 0) + count
            flags = flags.copy(enemyKills = updated)
        }
        flags
    }

    /** Refresh daily quests if past 6am, then record bones buried. */
    suspend fun recordDailyPrayer(amount: Int) = updateRefreshedDailyFlagsAtomically { refreshed ->
        var flags = refreshed
        flags = dailyQuestRepo.recordPrayerProgress(flags, amount)
        flags = weeklyQuestRepo.recordPrayerProgress(flags, amount)
        flags
    }

    /** Record arbitrary weekly progress (for new weekly quest types). */
    suspend fun recordWeeklyProgress(type: String, target: String, amount: Int) = updateRefreshedDailyFlagsAtomically { refreshed ->
        weeklyQuestRepo.recordProgress(refreshed, type, target, amount)
    }

    /** Returns current flags after refreshing daily and weekly quests if the boundary has passed. */
    suspend fun getRefreshedDailyFlags(): PlayerFlags = playerMutex.withLock {
        val player = getOrCreatePlayer()
        var flags: PlayerFlags = json.decodeFromString(player.flags)
        var changed = false
        val skillLevels: Map<String, Int> by lazy { json.decodeFromString(player.skillLevels) }

        if (dailyQuestRepo.shouldRefresh(flags.dailyQuestGeneratedAt, flags.dailyResetHour)) {
            flags = dailyQuestRepo.refreshFlags(flags, skillLevels)
            changed = true
        }

        if (weeklyQuestRepo.shouldRefresh(flags.weeklyQuestGeneratedAt, flags.dailyResetHour)) {
            flags = weeklyQuestRepo.refreshFlags(flags, skillLevels)
            changed = true
        }

        if (changed) {
            updateFlagsUnlocked(flags)
        }
        flags
    }

    /**
     * Atomically claims a completed daily quest: the flags read, the claimed marker, and any
     * Dwarven item grant all happen under one lock hold and land in one DB upsert. The previous
     * flow read flags in the ViewModel and wrote them back afterwards, so a concurrent flags
     * writer (e.g. the prestige flow) could clobber the claim and let it be claimed again.
     *
     * Returns the reward, or null when the quest isn't complete or is already claimed (double
     * tap). Coin rewards are still credited by the caller — coins don't touch this row.
     */
    suspend fun claimDailyQuest(templateId: String): DailyReward? = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        val equipped: Map<String, String?> = json.decodeFromString(player.equipped)
        val ownedItems = inventory.keys + equipped.values.filterNotNull()
        val (newFlags, reward) = try {
            dailyQuestRepo.claimQuest(flags, templateId, ownedItems)
        } catch (_: IllegalStateException) {
            return@withLock null
        }
        val grantedKey = (reward as? DailyReward.DwarvenItemReward)?.itemKey
        if (grantedKey != null) inventory[grantedKey] = (inventory[grantedKey] ?: 0) + 1

        playerDao.upsert(
            player.copy(
                flags     = json.encode<PlayerFlags>(newFlags.plusSeen(listOfNotNull(grantedKey))),
                inventory = json.encode<Map<String, Int>>(inventory),
            )
        )
        reward
    }

    /** Atomically claims a completed weekly quest (same lost-update guard as [claimDailyQuest]).
     *  Returns the coin reward, or null when it isn't complete or is already claimed. */
    suspend fun claimWeeklyQuest(templateId: String): Long? = playerMutex.withLock {
        val flags = getFlagsUnlocked()
        val (newFlags, rewardCoins) = try {
            weeklyQuestRepo.claimQuest(flags, templateId)
        } catch (_: IllegalStateException) {
            return@withLock null
        }
        updateFlagsUnlocked(newFlags)
        rewardCoins
    }

    /**
     * Atomically claims the weekly bonus: flags (weeklyBonusClaimed = true) and any Divine item
     * grant land in one DB upsert under one lock hold (same lost-update guard as
     * [claimDailyQuest]). Returns the reward, or null when not all weeklies are claimed or the
     * bonus was already taken. Coin rewards are credited by the caller.
     */
    suspend fun claimWeeklyBonus(): WeeklyBonusReward? = playerMutex.withLock {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        if (flags.weeklyQuestClaimed.size < 5 || flags.weeklyBonusClaimed) return@withLock null
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        val equipped: Map<String, String?> = json.decodeFromString(player.equipped)
        val ownedItems = inventory.keys + equipped.values.filterNotNull()
        val (newFlags, reward) = weeklyQuestRepo.claimWeeklyBonus(flags, ownedItems)
        val grantedKey = (reward as? WeeklyBonusReward.DivineItemReward)?.itemKey
        if (grantedKey != null) inventory[grantedKey] = (inventory[grantedKey] ?: 0) + 1

        playerDao.upsert(
            player.copy(
                flags     = json.encode<PlayerFlags>(newFlags.plusSeen(listOfNotNull(grantedKey))),
                inventory = json.encode<Map<String, Int>>(inventory),
            )
        )
        reward
    }

    /** Adds [qty] of [itemKey] to inventory. */
    suspend fun addItem(itemKey: String, amount: Int = 1) = playerMutex.withLock { addItemUnlocked(itemKey, amount) }

    internal suspend fun addItemUnlocked(itemKey: String, amount: Int = 1) {
        if (amount <= 0) return
        val player = getOrCreatePlayer()
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        inventory[itemKey] = ((inventory[itemKey] ?: 0).toLong() + amount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        playerDao.upsert(player.copy(
            inventory = json.encode<Map<String, Int>>(inventory),
            flags     = json.encode<PlayerFlags>(flags.plusSeen(listOf(itemKey))),
        ))
    }

    /** Adds multiple items to inventory in a single DB write. */
    suspend fun addItems(items: Map<String, Int>) = playerMutex.withLock { addItemsUnlocked(items) }

    internal suspend fun addItemsUnlocked(items: Map<String, Int>) {
        if (items.isEmpty()) return
        val player = getOrCreatePlayer()
        val inventory: MutableMap<String, Int> = json.decodeFromString(player.inventory)
        for ((key, qty) in items) {
            require(qty >= 0) { "Cannot add negative quantity" }
            inventory[key] = ((inventory[key] ?: 0).toLong() + qty).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        playerDao.upsert(player.copy(
            inventory = json.encode<Map<String, Int>>(inventory),
            flags     = json.encode<PlayerFlags>(flags.plusSeen(items.keys)),
        ))
    }


    /** Seeds seenItemKeys from current inventory + equipped; always ensures starting items are present. */
    suspend fun migrateSeenItems() {
        val player = getOrCreatePlayer()
        val flags: PlayerFlags = json.decodeFromString(player.flags)
        val startingItems = setOf("bronze_pickaxe", "bronze_axe", "bronze_fishing_rod", "bronze_boots")
        if (flags.seenItemKeys.isNotEmpty()) {
            val missing = startingItems - flags.seenItemKeys
            if (missing.isNotEmpty()) {
                playerDao.upsert(player.copy(
                    flags = json.encode<PlayerFlags>(flags.copy(seenItemKeys = flags.seenItemKeys + missing))
                ))
            }
            return
        }
        val inventory: Map<String, Int> = json.decodeFromString(player.inventory)
        val equipped: Map<String, String?> = json.decodeFromString(player.equipped)
        val allCurrentKeys = inventory.keys + equipped.values.filterNotNull() + startingItems
        playerDao.upsert(player.copy(
            flags = json.encode<PlayerFlags>(flags.copy(seenItemKeys = allCurrentKeys.toSet()))
        ))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun createDefaultPlayer(ironman: Boolean = false): Player {
        val defaultEquipped: Map<String, String?> = EquipSlot.ALL.associateWith { null } +
            mapOf(
                EquipSlot.PICKAXE     to "bronze_pickaxe",
                EquipSlot.AXE         to "bronze_axe",
                EquipSlot.FISHING_ROD to "bronze_fishing_rod",
                EquipSlot.BOOTS       to "bronze_boots",
            )
        val defaultInventory: Map<String, Int> = mapOf(
            "bronze_pickaxe"     to 1,
            "bronze_axe"         to 1,
            "bronze_fishing_rod" to 1,
            "bronze_boots"       to 1,
        )
        return Player(
            skillLevels = json.encode<Map<String, Int>>(Skills.DEFAULT_LEVELS),
            skillXp     = json.encode<Map<String, Long>>(Skills.DEFAULT_XP),
            inventory   = json.encode<Map<String, Int>>(defaultInventory),
            equipped    = json.encode<Map<String, String?>>(defaultEquipped),
            flags       = json.encode<PlayerFlags>(
                PlayerFlags(ironman = ironman, characterCreatedAt = System.currentTimeMillis())
            ),
        )
    }

    /**
     * One-time backfill for characters that predate [PlayerFlags.characterCreatedAt]: their
     * oldest quest completion is the earliest record that survives (sessions are deleted on
     * collect). Characters with no completed quests stay unstamped and show no creation line.
     */
    suspend fun ensureCharacterCreatedAt() {
        val flags = getFlags()
        if (flags.characterCreatedAt > 0L) return
        val oldest = questProgressDao.getAllProgress().mapNotNull { it.completedAt }.minOrNull() ?: return
        updateFlags(getFlags().copy(characterCreatedAt = oldest))
    }
}

internal fun isGuildCapeForSkill(capeSkill: String, skillName: String): Boolean {
    return when (capeSkill) {
        "warriors" -> skillName in setOf("attack", "strength", "defense")
        "archers" -> skillName == "ranged"
        "mages" -> skillName == "magic"
        else -> false
    }
}

internal fun resolveOwnedCapeKeysForSkill(skillName: String): List<String> {
    return when (skillName) {
        "attack" -> listOf("attack_cape", "warriors_guild_cape")
        "strength" -> listOf("strength_cape", "warriors_guild_cape")
        "defense" -> listOf("defense_cape", "warriors_guild_cape")
        "ranged" -> listOf("ranged_cape", "archers_guild_cape")
        "magic" -> listOf("magic_cape", "mages_guild_cape")
        "hitpoints", "hp" -> listOf("hp_cape")
        else -> listOf("${skillName}_cape", "${skillName}_guild_cape")
    }
}

/** Prayer cape multiplier that scales church blessing strength (issue #1491). */
fun blessingPrayerCapeMult(
    flags: PlayerFlags,
    equipped: Map<String, String?>,
    inventoryKeys: Set<String>,
    gameData: GameDataRepository,
): Float {
    if (flags.ironman) return 1f
    val equippedCape = equipped[EquipSlot.CAPE]?.let { gameData.equipment[it] }
    return resolveCapeMultiplier(
        Skills.PRAYER, equippedCape, inventoryKeys, flags.townBuildingTiers,
        com.fantasyidler.simulator.PrestigeBoosts.capeScalingBySkill(gameData.prestigeTrees, flags),
        gameData.equipment, flags.ironman,
    )
}

/** [blessingPrayerCapeMult] convenience for call sites holding a raw [Player] row. */
fun blessingPrayerCapeMult(player: Player, flags: PlayerFlags, gameData: GameDataRepository): Float {
    if (flags.ironman) return 1f
    return blessingPrayerCapeMult(
        flags,
        kotlinx.serialization.json.Json.decodeFromString(player.equipped),
        kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(player.inventory).keys,
        gameData,
    )
}

fun resolveCapeMultiplier(
    skillName: String,
    equippedCape: EquipmentData?,
    inventoryKeys: Set<String>,
    townBuildingTiers: Map<String, Int>,
    capeScaling: Map<String, Int>,
    allEquipment: Map<String, EquipmentData>,
    ironman: Boolean = false,
): Float {
    if (ironman) return 1.0f
    val normSkill = if (skillName == Skills.HITPOINTS) "hp" else skillName
    val rackTier = townBuildingTiers["cape_rack"] ?: 0
    val isCategoryUnlocked = when (normSkill) {
        in Skills.GATHERING -> rackTier >= 1
        in Skills.CRAFTING_SKILLS -> rackTier >= 2
        else -> rackTier >= 3
    }

    var bestSkillCapeBonus = 0f
    var bestGuildCapeBonus = 0f

    fun considerCape(capeDef: EquipmentData?) {
        if (capeDef == null || capeDef.capeBonus <= 0f) return
        val capeSkill = capeDef.capeSkill ?: return
        val isMatch = capeSkill == normSkill || isGuildCapeForSkill(capeSkill, normSkill)
        if (!isMatch) return

        val isGuildCape = capeDef.name.endsWith("_guild_cape") || capeSkill in setOf("warriors", "archers", "mages")
        if (isGuildCape) {
            bestGuildCapeBonus = maxOf(bestGuildCapeBonus, capeDef.capeBonus)
        } else {
            bestSkillCapeBonus = maxOf(bestSkillCapeBonus, capeDef.capeBonus)
        }
    }

    // Check equipped cape
    considerCape(equippedCape)

    // Check passive capes in inventory
    if (isCategoryUnlocked) {
        val candidateKeys = resolveOwnedCapeKeysForSkill(normSkill)
        for (key in candidateKeys) {
            if (inventoryKeys.contains(key)) {
                considerCape(allEquipment[key])
            }
        }
    }

    val totalBonus = bestSkillCapeBonus + bestGuildCapeBonus
    if (totalBonus <= 0f) return 1.0f

    // Cape Mastery prestige nodes scale non-combat cape bonuses (was prestige level + 1).
    val scaling = capeScaling[normSkill] ?: 1
    val isCombatSkill = normSkill in setOf("attack", "strength", "defense", "ranged", "magic", "hp", "slayer")
    return if (isCombatSkill) {
        1.0f + totalBonus
    } else {
        1.0f + totalBonus * scaling
    }
}
