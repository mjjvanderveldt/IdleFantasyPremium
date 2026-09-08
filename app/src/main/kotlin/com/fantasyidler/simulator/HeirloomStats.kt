package com.fantasyidler.simulator

import com.fantasyidler.data.json.EquipmentData
import com.fantasyidler.data.json.HeirloomBase
import com.fantasyidler.data.model.EquipSlot
import com.fantasyidler.data.model.Skills
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Heirloom items level from 1 to 99 on their own XP (mirrored from the skill XP earned while
 * they are equipped) and are additionally gated by the wielder's level in their governing
 * skill: full effect at [GATE_LEVEL], scaled down below it. Prestiging the skill therefore
 * temporarily nerfs the heirloom without touching its item level.
 *
 * The equipment.json entry holds the item-level-99 stats; [EquipmentData.heirloomBase] holds
 * the item-level-1 stats. Effective stats lerp between the two by itemFraction * gateFraction.
 */
object HeirloomStats {

    const val MAX_LEVEL = 99
    const val GATE_LEVEL = 85

    /** Item XP is capped at the level-99 threshold. */
    val XP_CAP: Long get() = XpTable.xpForLevel(MAX_LEVEL)

    fun level(itemXp: Long): Int = XpTable.levelForXp(itemXp.coerceAtMost(XP_CAP))

    /** Fraction of the base-to-max stat range currently unlocked. */
    fun scaleFraction(itemXp: Long, skillLevel: Int): Float {
        val itemFraction = (level(itemXp) - 1) / 98f
        val gateFraction = min(skillLevel.coerceAtLeast(1), GATE_LEVEL) / GATE_LEVEL.toFloat()
        return itemFraction * gateFraction
    }

    /** Equip slot whose item mirrors XP from [skill], or null if the skill has no heirloom slot. */
    fun slotForSkill(skill: String): String? = when (skill) {
        Skills.MINING      -> EquipSlot.PICKAXE
        Skills.WOODCUTTING -> EquipSlot.AXE
        Skills.FISHING     -> EquipSlot.FISHING_ROD
        Skills.FARMING     -> EquipSlot.HOE
        Skills.SMITHING    -> EquipSlot.HAMMER
        Skills.FIREMAKING  -> EquipSlot.TINDERBOX
        Skills.AGILITY     -> EquipSlot.GRAPPLING_HOOK
        Skills.COOKING     -> EquipSlot.FRYING_PAN
        Skills.THIEVING    -> EquipSlot.LOCKPICK
        Skills.ATTACK      -> EquipSlot.WEAPON_ATK
        Skills.STRENGTH    -> EquipSlot.WEAPON_STR
        Skills.RANGED      -> EquipSlot.WEAPON_RANGED
        Skills.MAGIC       -> EquipSlot.WEAPON_MAGIC
        // The Midas Band: hitpoints XP flows in every combat session, so a ring heirloom
        // levels from all combat without colliding with the per-style weapon heirlooms.
        Skills.HITPOINTS   -> EquipSlot.RING
        else               -> null
    }

    /** Returns [eq] with every scalable stat lerped between heirloomBase and its max values. */
    fun resolve(eq: EquipmentData, itemXp: Long, skillLevel: Int): EquipmentData {
        if (eq.heirloomSkill == null) return eq
        val base = eq.heirloomBase ?: HeirloomBase()
        val f = scaleFraction(itemXp, skillLevel)
        fun lerp(b: Int, m: Int): Int = (b + (m - b) * f).roundToInt()
        fun lerpF(b: Float, m: Float): Float = b + (m - b) * f
        return eq.copy(
            attackBonus           = lerp(base.attackBonus, eq.attackBonus),
            strengthBonus         = lerp(base.strengthBonus, eq.strengthBonus),
            defenseBonus          = lerp(base.defenseBonus, eq.defenseBonus),
            rangedAttackBonus     = eq.rangedAttackBonus?.let { lerp(base.rangedAttackBonus, it) },
            rangedStrengthBonus   = eq.rangedStrengthBonus?.let { lerp(base.rangedStrengthBonus, it) },
            magicAttackBonus      = eq.magicAttackBonus?.let { lerp(base.magicAttackBonus, it) },
            magicDamageBonus      = eq.magicDamageBonus?.let { lerp(base.magicDamageBonus, it) },
            miningEfficiency      = eq.miningEfficiency?.let { lerpF(base.efficiency, it) },
            woodcuttingEfficiency = eq.woodcuttingEfficiency?.let { lerpF(base.efficiency, it) },
            fishingEfficiency     = eq.fishingEfficiency?.let { lerpF(base.efficiency, it) },
            farmingEfficiency     = eq.farmingEfficiency?.let { lerpF(base.efficiency, it) },
            smithingEfficiency    = eq.smithingEfficiency?.let { lerpF(base.efficiency, it) },
            firemakingEfficiency  = eq.firemakingEfficiency?.let { lerpF(base.efficiency, it) },
            agilityEfficiency     = eq.agilityEfficiency?.let { lerpF(base.efficiency, it) },
            cookingEfficiency     = eq.cookingEfficiency?.let { lerpF(base.efficiency, it) },
            thievingEfficiency    = eq.thievingEfficiency?.let { lerpF(base.efficiency, it) },
        )
    }

    /** Heirloom item keys the player already owns; a boss must not roll these as rare drops again. */
    fun ownedHeirloomKeys(equipment: Map<String, EquipmentData>, inventory: Map<String, Int>): Set<String> =
        inventory.keys.filterTo(mutableSetOf()) { equipment[it]?.heirloomSkill != null }

    /**
     * Copy of [equipment] with every heirloom entry replaced by its effective stats for this
     * player. Non-heirloom entries pass through untouched.
     */
    fun resolveAll(
        equipment: Map<String, EquipmentData>,
        levels: Map<String, Int>,
        heirloomXp: Map<String, Long>,
    ): Map<String, EquipmentData> {
        if (equipment.values.none { it.heirloomSkill != null }) return equipment
        return equipment.mapValues { (key, eq) ->
            val skill = eq.heirloomSkill ?: return@mapValues eq
            resolve(eq, heirloomXp[key] ?: 0L, levels[skill] ?: 1)
        }
    }
}
