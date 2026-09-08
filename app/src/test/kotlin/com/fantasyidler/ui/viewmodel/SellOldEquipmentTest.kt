package com.fantasyidler.ui.viewmodel

import com.fantasyidler.data.json.EquipmentData
import com.fantasyidler.data.model.EquipSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ShopViewModel.computeOldEquipmentToSell].
 *
 * Sell Old Gear sells every unequipped equippable: since the keep-one-of-each toggle
 * exists, gear no longer has to be strictly outclassed to be sellable. Protected always:
 * equipped copies, reserved copies, and skill capes (issue #821 — rare level-99 rewards).
 */
class SellOldEquipmentTest {

    private fun cape(
        key: String,
        capeSkill: String?,
        defense: Int = 5,
        capeBonus: Float = 0.1f,
    ) = EquipmentData(
        name = key,
        displayName = key.replace("_", " ").replaceFirstChar { it.uppercase() },
        slot = EquipSlot.CAPE,
        defenseBonus = defense,
        capeSkill = capeSkill,
        capeBonus = capeBonus,
    )

    private fun body(
        key: String,
        attack: Int = 0,
        strength: Int = 0,
        defense: Int = 0,
    ) = EquipmentData(
        name = key,
        displayName = key.replace("_", " ").replaceFirstChar { it.uppercase() },
        slot = EquipSlot.BODY,
        attackBonus = attack,
        strengthBonus = strength,
        defenseBonus = defense,
    )

    @Test
    fun `skill capes are never sold`() {
        val allEquip = mapOf(
            "prayer_cape"   to cape("prayer_cape", capeSkill = "prayer"),
            "mining_cape"   to cape("mining_cape", capeSkill = "mining"),
            "attack_cape"   to cape("attack_cape", capeSkill = "attack", defense = 10),
        )
        val equipped = mapOf(EquipSlot.CAPE to "attack_cape")
        val inventory = mapOf("prayer_cape" to 1, "mining_cape" to 1, "attack_cape" to 1)

        val toSell = ShopViewModel.computeOldEquipmentToSell(equipped, inventory, allEquip)

        assertFalse("prayer_cape should NOT be sold", "prayer_cape" in toSell)
        assertFalse("mining_cape should NOT be sold", "mining_cape" in toSell)
        assertFalse("attack_cape should NOT be sold", "attack_cape" in toSell)
    }

    @Test
    fun `non-skill cape is sold like any other gear`() {
        val allEquip = mapOf(
            "regular_cape" to EquipmentData(
                name = "regular_cape", displayName = "Regular Cape",
                slot = EquipSlot.CAPE, defenseBonus = 1,
            ),
            "spotted_cape" to EquipmentData(
                name = "spotted_cape", displayName = "Spotted Cape",
                slot = EquipSlot.CAPE, defenseBonus = 10,
            ),
        )
        val equipped = mapOf(EquipSlot.CAPE to "spotted_cape")
        val inventory = mapOf("regular_cape" to 1, "spotted_cape" to 1)

        val toSell = ShopViewModel.computeOldEquipmentToSell(equipped, inventory, allEquip)

        assertTrue("regular_cape should be sold", "regular_cape" in toSell)
        assertFalse("equipped spotted_cape should NOT be sold", "spotted_cape" in toSell)
    }

    @Test
    fun `unequipped gear sells even when it is not outclassed`() {
        // Incomparable stats: neither dominates the other. The old dominance rule kept
        // ranged_body forever; the keep-one toggle makes selling it safe now.
        val allEquip = mapOf(
            "melee_body"  to body("melee_body", strength = 10),
            "ranged_body" to body("ranged_body", defense = 10),
        )
        val equipped = mapOf(EquipSlot.BODY to "melee_body")
        val inventory = mapOf("melee_body" to 1, "ranged_body" to 1)

        val toSell = ShopViewModel.computeOldEquipmentToSell(equipped, inventory, allEquip)

        assertEquals(1, toSell["ranged_body"])
        assertFalse("melee_body is equipped and should NOT be sold", "melee_body" in toSell)
    }

    @Test
    fun `equipped copies are kept and extras are sold`() {
        val allEquip = mapOf("bronze_platebody" to body("bronze_platebody", defense = 3))
        val equipped = mapOf(EquipSlot.BODY to "bronze_platebody")
        val inventory = mapOf("bronze_platebody" to 3)

        val toSell = ShopViewModel.computeOldEquipmentToSell(equipped, inventory, allEquip)

        assertEquals(2, toSell["bronze_platebody"])
    }

    @Test
    fun `keep one of each keeps a keeper when nothing is equipped`() {
        val allEquip = mapOf("bronze_platebody" to body("bronze_platebody", defense = 3))
        val inventory = mapOf("bronze_platebody" to 2)

        val toSell = ShopViewModel.computeOldEquipmentToSell(
            emptyMap(), inventory, allEquip, keepOneOfEach = true)

        assertEquals(1, toSell["bronze_platebody"])
    }

    @Test
    fun `keep one of each uses the equipped copy as the keeper`() {
        val allEquip = mapOf("bronze_platebody" to body("bronze_platebody", defense = 3))
        val equipped = mapOf(EquipSlot.BODY to "bronze_platebody")
        val inventory = mapOf("bronze_platebody" to 2)

        val toSell = ShopViewModel.computeOldEquipmentToSell(
            equipped, inventory, allEquip, keepOneOfEach = true)

        assertEquals(1, toSell["bronze_platebody"])
    }

    @Test
    fun `gear referenced by a queued session snapshot is kept`() {
        // Issue #1630: a staff waiting in a queued dungeon's gear snapshot must not be sold.
        val allEquip = mapOf("bronze_platebody" to body("bronze_platebody", defense = 3))
        val inventory = mapOf("bronze_platebody" to 2)

        val toSell = ShopViewModel.computeOldEquipmentToSell(
            emptyMap(), inventory, allEquip, queuedGearKeys = setOf("bronze_platebody"))

        assertEquals(1, toSell["bronze_platebody"])
    }

    @Test
    fun `reserved copies are not sold`() {
        val allEquip = mapOf("bronze_platebody" to body("bronze_platebody", defense = 3))
        val inventory = mapOf("bronze_platebody" to 2)

        val toSell = ShopViewModel.computeOldEquipmentToSell(
            emptyMap(), inventory, allEquip, reserved = mapOf("bronze_platebody" to 2))

        assertFalse("reserved copies should NOT be sold", "bronze_platebody" in toSell)
    }

    @Test
    fun `gear remembered in a non-active loadout is kept`() {
        // Issue #1597: boots saved in the strength loadout were sold while ranged was active.
        val allEquip = mapOf(
            "dragon_boots" to EquipmentData(
                name = "dragon_boots", displayName = "Dragon Boots",
                slot = EquipSlot.BOOTS, defenseBonus = 12,
            ),
        )
        val equipped = mapOf(EquipSlot.BOOTS to null)
        val inventory = mapOf("dragon_boots" to 2)
        val loadouts = mapOf(
            "strength" to mapOf(EquipSlot.BOOTS to "dragon_boots"),
            "ranged"   to mapOf(EquipSlot.BOOTS to "dragon_boots"),
        )

        val toSell = ShopViewModel.computeOldEquipmentToSell(
            equipped, inventory, allEquip, armorLoadouts = loadouts)

        // Two loadouts share the single physical copy: exactly one is protected.
        assertEquals(1, toSell["dragon_boots"])
    }

    @Test
    fun `non-equipment inventory is ignored`() {
        val allEquip = mapOf("bronze_platebody" to body("bronze_platebody", defense = 3))
        val inventory = mapOf("iron_ore" to 500, "bronze_platebody" to 1)

        val toSell = ShopViewModel.computeOldEquipmentToSell(emptyMap(), inventory, allEquip)

        assertFalse("iron_ore is not equipment", "iron_ore" in toSell)
        assertEquals(1, toSell["bronze_platebody"])
    }
}
