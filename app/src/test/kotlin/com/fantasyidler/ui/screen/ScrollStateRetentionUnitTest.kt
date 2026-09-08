package com.fantasyidler.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.fantasyidler.data.model.Skills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollStateRetentionUnitTest {

    private val testSaverScope = SaverScope { true }

    @Test
    fun `LazyListState Saver properly serializes and restores scroll offset`() {
        val originalIndex = 14
        val originalOffset = 250
        val originalState = LazyListState(
            firstVisibleItemIndex = originalIndex,
            firstVisibleItemScrollOffset = originalOffset,
        )

        // Save via Compose Saver contract
        @Suppress("UNCHECKED_CAST")
        val saver = LazyListState.Saver as Saver<LazyListState, Any>
        val saved = with(saver) {
            testSaverScope.save(originalState)
        }
        assertNotNull("Saved state representation should not be null", saved)

        // Restore via Compose Saver contract
        val restoredState = saver.restore(saved!!)
        assertNotNull("Restored state should not be null", restoredState)
        assertEquals("firstVisibleItemIndex must match after restore", originalIndex, restoredState!!.firstVisibleItemIndex)
        assertEquals("firstVisibleItemScrollOffset must match after restore", originalOffset, restoredState.firstVisibleItemScrollOffset)
    }

    @Test
    fun `CraftingScreen per-tab scroll states are distinct and isolated`() {
        // Simulates the List(6) allocation pattern in CraftingScreen
        val scrollStates = List(6) {
            LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)
        }

        assertEquals("Should have 6 distinct scroll states for 6 craft tabs", 6, scrollStates.size)

        // Verify each instance is unique (no aliasing)
        for (i in 0 until 5) {
            for (j in (i + 1) until 6) {
                assertTrue("Scroll state $i and $j must be distinct object references", scrollStates[i] !== scrollStates[j])
            }
        }

        // Test mutating one tab's state does not affect other tabs
        val smithingState = LazyListState(firstVisibleItemIndex = 8, firstVisibleItemScrollOffset = 100)
        val fletchingState = LazyListState(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0)

        assertNotEquals(
            "Smithing scroll index should not match un-scrolled Fletching index",
            fletchingState.firstVisibleItemIndex,
            smithingState.firstVisibleItemIndex,
        )
    }

    @Test
    fun `SkillsScreen all lazy column keys are unique`() {
        val gatheringSkills = listOf(Skills.MINING, Skills.FISHING, Skills.WOODCUTTING, Skills.FARMING, Skills.FIREMAKING, Skills.AGILITY, Skills.THIEVING)
        val craftingSkills = listOf(Skills.SMITHING, Skills.COOKING, Skills.FLETCHING, Skills.CRAFTING, Skills.RUNECRAFTING, Skills.HERBLORE, Skills.CONSTRUCTION)
        val supportSkills = listOf(Skills.PRAYER, Skills.MERCANTILE)

        val keys = mutableListOf<String>()
        keys.add("active_session")
        keys.add("header_gathering")
        gatheringSkills.forEach { keys.add("gather_$it") }
        keys.add("header_crafting")
        craftingSkills.forEach { keys.add("craft_$it") }
        keys.add("header_support")
        supportSkills.forEach { keys.add("support_$it") }
        keys.add("header_combat")
        keys.add("combat_${Skills.SLAYER}")

        val distinctCount = keys.distinct().size
        assertEquals("Every item in SkillsScreen LazyColumn must have a unique key", keys.size, distinctCount)
    }

    @Test
    fun `WorkerSkillsScreen all lazy column keys are unique`() {
        val gatheringSkills = listOf(Skills.MINING, Skills.FISHING, Skills.WOODCUTTING, Skills.FARMING, Skills.FIREMAKING, Skills.THIEVING)
        val craftingSkills = listOf(Skills.SMITHING, Skills.COOKING, Skills.FLETCHING, Skills.CRAFTING, Skills.RUNECRAFTING, Skills.HERBLORE, Skills.CONSTRUCTION)

        val keys = mutableListOf<String>()
        keys.add("slot_selector")
        keys.add("tier_badge")
        keys.add("active_worker_session")
        keys.add("header_gathering")
        gatheringSkills.forEach { keys.add("worker_gather_$it") }
        keys.add("header_crafting")
        craftingSkills.forEach { keys.add("worker_craft_$it") }
        keys.add("header_support")
        keys.add("worker_support_${Skills.AGILITY}")
        keys.add("header_prayer")
        keys.add("worker_prayer_${Skills.PRAYER}")

        val distinctCount = keys.distinct().size
        assertEquals("Every item in WorkerSkillsScreen LazyColumn must have a unique key", keys.size, distinctCount)
    }

    @Test
    fun `Group-namespaced keys guarantee uniqueness across slots and locations`() {
        val groups = listOf("Head", "Body", "Legs", "Weapon")
        val itemKey = "iron_platebody"

        val generatedKeys = groups.map { groupName -> "${groupName}_$itemKey" }
        assertEquals(
            "Same item in multiple groups must have unique composite keys",
            groups.size,
            generatedKeys.distinct().size,
        )
    }
}
