package com.fantasyidler.repository

import com.fantasyidler.data.model.PlayerFlags
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

sealed class UpgradeBuildingResult {
    object Success : UpgradeBuildingResult()
    object InsufficientLevel : UpgradeBuildingResult()
    object InsufficientCoins : UpgradeBuildingResult()
    object InsufficientMaterials : UpgradeBuildingResult()
    object AlreadyMaxed : UpgradeBuildingResult()
    object UnknownBuilding : UpgradeBuildingResult()
}

@Singleton
class TownRepository @Inject constructor(
    private val gameData: GameDataRepository,
    private val playerRepo: PlayerRepository,
    private val questRepo: QuestRepository,
    private val boostRepo: BoostRepository,
) {

    companion object {
        /**
         * Builder's discount: 0.5% off Builder's Workshop upgrade costs per Construction level
         * (49.5% at 99). Integer per-mille math so the transaction, the card display, and the
         * tests all agree exactly, with no float rounding drift.
         */
        fun builderDiscountPerMille(constructionLevel: Int, extraPerMille: Int = 0): Int =
            ((constructionLevel.coerceAtLeast(0) * 5).coerceAtMost(495) + extraPerMille).coerceAtMost(750)

        fun builderDiscount(constructionLevel: Int, extraPerMille: Int = 0): Float =
            builderDiscountPerMille(constructionLevel, extraPerMille) / 1000f

        fun discountedCoins(cost: Long, constructionLevel: Int, extraPerMille: Int = 0): Long =
            cost * (1000 - builderDiscountPerMille(constructionLevel, extraPerMille)) / 1000

        /** Rounds up, and a required material never discounts below one. */
        fun discountedQty(qty: Int, constructionLevel: Int, extraPerMille: Int = 0): Int {
            val remainingPerMille = qty.toLong() * (1000 - builderDiscountPerMille(constructionLevel, extraPerMille))
            return ((remainingPerMille + 999) / 1000).toInt().coerceAtLeast(1)
        }

        fun discountedMaterials(materials: Map<String, Int>, constructionLevel: Int, extraPerMille: Int = 0): Map<String, Int> =
            materials.mapValues { (_, qty) -> discountedQty(qty, constructionLevel, extraPerMille) }
    }

    // -------------------------------------------------------------------------
    // Bonus accessors — pure functions, safe to call from any context
    // -------------------------------------------------------------------------

    /** Calculates the multiplier for the worker XP for an individual building. 1.0 = no bonus. */
    fun workerXpMultiplier(building: String, tier: Int): Float {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses
        return 1.0f + (bonuses?.get("worker_xp")?.toFloat() ?: 0f)
    }

    /** Multiplier applied to worker XP. 1.0 = no bonus. */
    fun workerXpMultiplier(flags: PlayerFlags): Float {
        var multiplier = 1.0f
        flags.townBuildingTiers.forEach { buildingName, tier ->
            multiplier *= workerXpMultiplier(buildingName, tier)
        }
        return multiplier
    }

    /** Factor to multiply guild quest requirement amounts by. */
    fun guildQuestRequirementFactor(building: String, tier: Int): Float {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses
        return 1.0f - (bonuses?.get("guild_quest_reduction")?.toFloat() ?: 0f)
    }

    /** Factor to multiply guild quest requirement amounts by. */
    fun guildQuestRequirementFactor(flags: PlayerFlags): Float {
        var multiplier = 1.0f
        flags.townBuildingTiers.forEach { buildingName, tier ->
            multiplier *= guildQuestRequirementFactor(buildingName, tier)
        }
        return multiplier
    }

    /** Extra farm plots bonuses */
    fun extraFarmPlots(building: String, tier: Int): Int {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses ?: return 0
        return bonuses["farm_plots"]?.toInt() ?: 0
    }

    /** Extra farm plots from all builders (+1 per garden tier), plus the Monument's Foundation stage. */
    fun extraFarmPlots(flags: PlayerFlags): Int {
        var extraPlots = 0
        flags.townBuildingTiers.forEach { buildingName, tier ->
            extraPlots += extraFarmPlots(buildingName, tier)
        }
        if (flags.monumentTier >= 1) extraPlots += 1
        return extraPlots
    }

    /** Number of additional carnival minigames. */
    fun extraCarnivalGames(building: String, tier: Int): Int {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses ?: return 0
        return bonuses["extra_carnival_games"]?.toInt() ?: 0
    }

    /** Number of active carnival minigames (4 base + 1 at fairgrounds T1 + 1 at T2). */
    fun carnivalGameCount(flags: PlayerFlags): Int {
        var carnivalGames = 4
        flags.townBuildingTiers.forEach { buildingName, tier ->
            carnivalGames += extraCarnivalGames(buildingName, tier)
        }
        return carnivalGames.coerceIn(4, 6)
    }

    /** Carnival active game cooldown in ms. */
    fun carnivalCooldownFactor(building: String, tier: Int): Float {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses ?: return 1.0f
        return bonuses["carnival_cooldown_mult"]?.toFloat() ?: 1.0f
    }

    /** Carnival active game cooldown in ms (10 min base; T1→7.5 min; T3→5 min). */
    fun carnivalCooldownMs(flags: PlayerFlags): Long {
        var multiplier = 1.0f
        flags.townBuildingTiers.forEach { buildingName, tier ->
            multiplier *= carnivalCooldownFactor(buildingName, tier)
        }
        return (multiplier * 600_000).toLong()
    }

    /** Calculates the multiplier for the idle tickets in the fairgrounds */
    fun idleTicketBonusChance(building: String, tier: Int): Float {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses
        return (bonuses?.get("idle_ticket_bonus_chance")?.toFloat() ?: 0f)
    }

    /** Calculates the multiplier for the idle tickets in the fairgrounds */
    fun idleTicketBonusChance(flags: PlayerFlags): Float {
        var bonusChance = 0.0f
        flags.townBuildingTiers.forEach { buildingName, tier ->
            bonusChance += idleTicketBonusChance(buildingName, tier)
        }
        return bonusChance
    }

    /** Blessing duration in ms */
    fun extraBlessingDuration(building: String, tier: Int): Long {
        val hoursMs = 3_600_000L
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses ?: return 0
        return (bonuses["extra_blessing_hrs"]?.toInt() ?: 0) * hoursMs
    }

    /** Blessing duration in ms based on Church tier, plus the Monument's Statue stage bonus. */
    fun blessingDurationMs(flags: PlayerFlags): Long {
        var duration = 24 * 3_600_000L
        flags.townBuildingTiers.forEach { buildingName, tier ->
            duration += extraBlessingDuration(buildingName, tier)
        }
        if (flags.monumentTier >= 3) duration += MonumentRepository.BLESSING_BONUS_MS
        return duration
    }

    /** Extra action queue slots bonus. */
    fun extraQueueSlots(building: String, tier: Int): Int {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses ?: return 0
        return bonuses["queue_slots"]?.toInt() ?: 0
    }

    /** Secondary material preservation chance (Artisan's Workshop bonus). */
    fun secondaryMaterialSaveChance(building: String, tier: Int): Float {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses ?: return 0.0f
        return bonuses["secondary_material_save_chance"]?.toFloat() ?: 0.0f
    }

    /** Secondary material preservation chance based on Artisan's Workshop tier. */
    fun secondaryMaterialSaveChance(flags: PlayerFlags): Float {
        var chance = 0.0f
        flags.townBuildingTiers.forEach { buildingName, tier ->
            chance += secondaryMaterialSaveChance(buildingName, tier)
        }
        return chance
    }

    /** Player session speed reduction factor (Chronos Spire bonus). */
    fun playerSessionSpeedReduction(building: String, tier: Int): Float {
        val bonuses = gameData.townBuildings[building]?.tiers?.getOrNull(tier - 1)?.bonuses ?: return 0.0f
        return bonuses["player_session_speed_reduction"]?.toFloat() ?: 0.0f
    }

    /** Player session duration multiplier (e.g. 0.98 for 2% reduction). */
    fun playerSessionDurationMultiplier(flags: PlayerFlags): Float {
        var reduction = 0.0f
        flags.townBuildingTiers.forEach { buildingName, tier ->
            reduction += playerSessionSpeedReduction(buildingName, tier)
        }
        return (1.0f - reduction).coerceAtLeast(0.5f)
    }

    // -------------------------------------------------------------------------
    // Upgrade action
    // -------------------------------------------------------------------------

    suspend fun upgradeBuilding(buildingKey: String): UpgradeBuildingResult = playerRepo.withLock {
        val building = gameData.townBuildings[buildingKey]
            ?: return@withLock UpgradeBuildingResult.UnknownBuilding

        val flags = playerRepo.getFlagsUnlocked()
        val currentTier = flags.townBuildingTiers[buildingKey] ?: 0

        if (currentTier >= building.tiers.size) return@withLock UpgradeBuildingResult.AlreadyMaxed

        val tierDef = building.tiers[currentTier]
        val player = playerRepo.getOrCreatePlayer()
        val skillLevels: Map<String, Int> = Json.decodeFromString(player.skillLevels)
        val constructionLevel = skillLevels["construction"] ?: 1

        if (constructionLevel < tierDef.constructionLevelRequired) {
            return@withLock UpgradeBuildingResult.InsufficientLevel
        }

        val prestigePerMille = boostRepo.builderDiscountPerMille(playerRepo.getFlagsUnlocked())
        val coinCost  = discountedCoins(tierDef.coinCost, constructionLevel, prestigePerMille)
        val materials = discountedMaterials(tierDef.materials, constructionLevel, prestigePerMille)

        if (player.coins < coinCost) return@withLock UpgradeBuildingResult.InsufficientCoins

        val inventory: Map<String, Int> = Json.decodeFromString(player.inventory)
        for ((item, qty) in materials) {
            if ((inventory[item] ?: 0) < qty) return@withLock UpgradeBuildingResult.InsufficientMaterials
        }

        playerRepo.consumeItemsUnlocked(materials)
        playerRepo.spendCoinsUnlocked(coinCost)

        val newTiers = flags.townBuildingTiers.toMutableMap()
        newTiers[buildingKey] = currentTier + 1
        playerRepo.updateFlagsUnlocked(flags.copy(townBuildingTiers = newTiers))

        questRepo.recordBuildingUpgraded(buildingKey)

        UpgradeBuildingResult.Success
    }
}
