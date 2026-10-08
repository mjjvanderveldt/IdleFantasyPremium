package com.fantasyidler.simulator

import com.fantasyidler.data.json.ThievingNpcData
import com.fantasyidler.data.model.SessionFrame
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pre-simulates all 60 frames of a thieving session.
 *
 * Each frame the player attempts to pickpocket the NPC. If successful, coins
 * and loot are awarded and XP is scaled by the lockpick's efficiency. A failed
 * attempt earns nothing for that frame.
 *
 * success_chance = clamp(0.10, 0.50 + (thievingLevel - npcMinLevel) * 0.02 * lockpickEfficiency + bonus, 0.98)
 */
object ThievingSimulator {

    const val BASE_SUCCESS = 0.50
    const val SUCCESS_PER_LEVEL = 0.02
    const val MIN_SUCCESS = 0.10
    const val MAX_SUCCESS = 0.98

    fun successChance(thievingLevel: Int, npcLevelRequired: Int, toolEfficiency: Float, successBonus: Double): Double =
        (BASE_SUCCESS + (thievingLevel - npcLevelRequired) * SUCCESS_PER_LEVEL * toolEfficiency + successBonus)
            .coerceIn(MIN_SUCCESS, MAX_SUCCESS)

    /** XP for one successful pickpocket: base XP scaled by lockpick efficiency, then pet boost. */
    fun xpPerSuccess(npc: ThievingNpcData, toolEfficiency: Float, petBoostPct: Int): Int {
        val baseXp = (npc.baseXp * toolEfficiency).toInt()
        return if (petBoostPct > 0) (baseXp * (1.0 + petBoostPct / 100.0)).toInt() else baseXp
    }

    /** Expected XP over a full 60-frame session. */
    fun expectedSessionXp(npc: ThievingNpcData, thievingLevel: Int, toolEfficiency: Float, successBonus: Double, petBoostPct: Int): Double =
        60.0 * successChance(thievingLevel, npc.levelRequired, toolEfficiency, successBonus) *
            xpPerSuccess(npc, toolEfficiency, petBoostPct)

    data class Result(
        val frames: List<SessionFrame>,
        val durationMs: Long,
    )

    fun simulate(
        npcKey: String,
        npc: ThievingNpcData,
        startXp: Long,
        thievingLevel: Int,
        agilityLevel: Int = 1,
        floorReductionMin: Double = 0.0,
        petBoostPct: Int = 0,
        petDropKey: String? = null,
        petDropChance: Double = 0.0,
        toolEfficiency: Float = 1.0f,
        chronosMultiplier: Float = 1.0f,
        successBonus: Double = 0.0,
        random: Random = Random.Default,
    ): Result {
        val successChance = successChance(thievingLevel, npc.levelRequired, toolEfficiency, successBonus)
        val xpGain = xpPerSuccess(npc, toolEfficiency, petBoostPct)

        var currentXp = startXp
        val frames = mutableListOf<SessionFrame>()

        for (minute in 1..60) {
            val xpBefore = currentXp
            val levelBefore = XpTable.levelForXp(currentXp)

            val success = random.nextDouble() < successChance
            if (!success) {
                frames.add(
                    SessionFrame(
                        minute = minute,
                        xpGain = 0,
                        xpBefore = xpBefore,
                        xpAfter = xpBefore,
                        levelBefore = levelBefore,
                        levelAfter = levelBefore,
                        items = emptyMap(),
                        leveledUp = false,
                        success = false,
                    )
                )
                continue
            }

            currentXp += xpGain
            val levelAfter = XpTable.levelForXp(currentXp)

            val items = mutableMapOf<String, Int>()

            // Coins — random amount in npc's range
            val coins = npc.coinsMin + random.nextInt(npc.coinsMax - npc.coinsMin + 1)
            items["coins"] = coins

            // Loot table rolls
            for (entry in npc.lootTable) {
                if (random.nextDouble() < entry.chance) {
                    val qty = if (entry.minQty == entry.maxQty) entry.minQty
                    else entry.minQty + random.nextInt(entry.maxQty - entry.minQty + 1)
                    items[entry.item] = (items[entry.item] ?: 0) + qty
                }
            }

            // Pet drop
            if (petDropKey != null && petDropChance > 0.0 && random.nextDouble() < petDropChance) {
                items[petDropKey] = 1
            }

            frames.add(
                SessionFrame(
                    minute = minute,
                    xpGain = xpGain,
                    xpBefore = xpBefore,
                    xpAfter = currentXp,
                    levelBefore = levelBefore,
                    levelAfter = levelAfter,
                    items = items,
                    leveledUp = levelAfter > levelBefore,
                    success = true,
                )
            )
        }

        return Result(frames, SkillSimulator.sessionDurationMs(agilityLevel, floorReductionMin, chronosMultiplier))
    }
}
