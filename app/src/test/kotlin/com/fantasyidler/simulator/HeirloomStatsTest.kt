package com.fantasyidler.simulator

import com.fantasyidler.data.json.EquipmentData
import com.fantasyidler.data.json.HeirloomBase
import com.fantasyidler.data.model.EquipSlot
import com.fantasyidler.data.model.Skills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeirloomStatsTest {

    private val blade = EquipmentData(
        name          = "heirloom_blade",
        displayName   = "Heirloom Blade",
        slot          = "weapon",
        combatStyle   = "attack",
        attackBonus   = 128,
        strengthBonus = 143,
        heirloomSkill = "attack",
        heirloomBase  = HeirloomBase(attackBonus = 9, strengthBonus = 12),
    )

    private val pickaxe = EquipmentData(
        name             = "heirloom_pickaxe",
        displayName      = "Heirloom Pickaxe",
        slot             = "pickaxe",
        miningEfficiency = 5.25f,
        heirloomSkill    = "mining",
        heirloomBase     = HeirloomBase(efficiency = 1.25f),
    )

    private val maxXp = XpTable.xpForLevel(99)

    @Test
    fun `level 1 item at gate level equals base stats`() {
        val eq = HeirloomStats.resolve(blade, 0L, 85)
        assertEquals(9, eq.attackBonus)
        assertEquals(12, eq.strengthBonus)
    }

    @Test
    fun `level 99 item at gate level equals max stats`() {
        val eq = HeirloomStats.resolve(blade, maxXp, 85)
        assertEquals(128, eq.attackBonus)
        assertEquals(143, eq.strengthBonus)
    }

    @Test
    fun `prestige nerf collapses a maxed item back toward base`() {
        val eq = HeirloomStats.resolve(blade, maxXp, 1)
        assertTrue("attack should be near base after prestige, was ${eq.attackBonus}", eq.attackBonus <= 12)
        assertTrue(eq.attackBonus >= 9)
    }

    @Test
    fun `gate is flat above 85`() {
        val at85 = HeirloomStats.resolve(blade, maxXp, 85)
        val at99 = HeirloomStats.resolve(blade, maxXp, 99)
        assertEquals(at85.attackBonus, at99.attackBonus)
        assertEquals(at85.strengthBonus, at99.strengthBonus)
    }

    @Test
    fun `item xp is capped at the level 99 threshold`() {
        assertEquals(99, HeirloomStats.level(Long.MAX_VALUE))
        val capped   = HeirloomStats.resolve(blade, Long.MAX_VALUE, 85)
        val exactMax = HeirloomStats.resolve(blade, maxXp, 85)
        assertEquals(exactMax.attackBonus, capped.attackBonus)
    }

    @Test
    fun `tool efficiency lerps between base and max`() {
        assertEquals(1.25f, HeirloomStats.resolve(pickaxe, 0L, 85).miningEfficiency!!, 0.001f)
        assertEquals(5.25f, HeirloomStats.resolve(pickaxe, maxXp, 85).miningEfficiency!!, 0.001f)
        val mid = HeirloomStats.resolve(pickaxe, XpTable.xpForLevel(50), 85).miningEfficiency!!
        assertTrue(mid > 1.25f && mid < 5.25f)
    }

    @Test
    fun `non heirloom items pass through resolve untouched`() {
        val plain = blade.copy(heirloomSkill = null, heirloomBase = null)
        assertEquals(plain, HeirloomStats.resolve(plain, 0L, 1))
    }

    @Test
    fun `resolveAll only replaces heirloom entries`() {
        val plain = blade.copy(name = "iron_scimitar", heirloomSkill = null, heirloomBase = null, attackBonus = 9)
        val resolved = HeirloomStats.resolveAll(
            mapOf("iron_scimitar" to plain, "heirloom_blade" to blade),
            levels     = mapOf(Skills.ATTACK to 85),
            heirloomXp = mapOf("heirloom_blade" to maxXp),
        )
        assertEquals(9, resolved["iron_scimitar"]!!.attackBonus)
        assertEquals(128, resolved["heirloom_blade"]!!.attackBonus)
    }

    @Test
    fun `slotForSkill maps every heirloom skill and nothing else`() {
        assertEquals(EquipSlot.PICKAXE, HeirloomStats.slotForSkill(Skills.MINING))
        assertEquals(EquipSlot.HOE, HeirloomStats.slotForSkill(Skills.FARMING))
        assertEquals(EquipSlot.WEAPON_ATK, HeirloomStats.slotForSkill(Skills.ATTACK))
        assertEquals(EquipSlot.WEAPON_STR, HeirloomStats.slotForSkill(Skills.STRENGTH))
        assertEquals(EquipSlot.WEAPON_RANGED, HeirloomStats.slotForSkill(Skills.RANGED))
        assertEquals(EquipSlot.WEAPON_MAGIC, HeirloomStats.slotForSkill(Skills.MAGIC))
        assertNull(HeirloomStats.slotForSkill(Skills.DEFENSE))
        assertNull(HeirloomStats.slotForSkill(Skills.PRAYER))
        assertNull(HeirloomStats.slotForSkill(Skills.FLETCHING))
    }

    @Test
    fun `ownedHeirloomKeys picks only owned heirlooms`() {
        val equipment = mapOf(
            "heirloom_blade" to blade,
            "heirloom_pickaxe" to pickaxe,
            "iron_scimitar" to blade.copy(name = "iron_scimitar", heirloomSkill = null),
        )
        val owned = HeirloomStats.ownedHeirloomKeys(equipment, mapOf("heirloom_blade" to 1, "iron_scimitar" to 3))
        assertEquals(setOf("heirloom_blade"), owned)
    }
}
