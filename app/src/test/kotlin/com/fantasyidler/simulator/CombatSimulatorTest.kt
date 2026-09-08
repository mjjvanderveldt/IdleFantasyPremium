package com.fantasyidler.simulator

import com.fantasyidler.data.json.BossCombatStats
import com.fantasyidler.data.json.BossCommonLoot
import com.fantasyidler.data.json.BossData
import com.fantasyidler.data.json.BossDefensiveStats
import com.fantasyidler.data.json.DungeonData
import com.fantasyidler.data.json.EnemyCombatStats
import com.fantasyidler.data.json.EnemyData
import com.fantasyidler.data.json.EnemyDefensiveStats
import com.fantasyidler.data.json.EnemySpawn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Tests for the most complex simulator, [CombatSimulator.simulateDungeon], made
 * deterministic through the injected `random` seam. Combat has too many moving
 * parts to assert exact per-tick damage, so this pins structural invariants
 * (frame count, non-negative HP, XP accrual) plus seeded reproducibility.
 */
class CombatSimulatorTest {

    private fun weakEnemy() = EnemyData(
        name = "rat",
        displayName = "Rat",
        hp = 1,
        combatStats = EnemyCombatStats(
            attackLevel = 1, strengthLevel = 1, defenseLevel = 1,
            attackBonus = 0, strengthBonus = 0,
        ),
        defensiveStats = EnemyDefensiveStats(
            attackDefense = 0, strengthDefense = 0, rangedDefense = 0, magicDefense = 0,
        ),
        // Combat XP per kill is read from the "combat" key (see CombatSimulator).
        xpDrops = mapOf("combat" to 20),
    )

    private fun dungeon(spawns: List<EnemySpawn>) = DungeonData(
        name = "farm",
        displayName = "Farm",
        description = "",
        recommendedLevel = 1,
        encounterRate = 1.0,
        enemySpawns = spawns,
    )

    /** A player strong enough to one-shot the weak enemy and never die. */
    private fun runStrongPlayer(seed: Int) = CombatSimulator.simulateDungeon(
        dungeon = dungeon(listOf(EnemySpawn("rat", 1))),
        enemies = mapOf("rat" to weakEnemy()),
        playerAttack = 99,
        playerStrength = 99,
        playerDefence = 99,
        playerHp = 99,
        weaponStrengthBonus = 64,
        random = Random(seed),
    )

    /** An unkillable wall so every melee swing targets a living enemy. */
    private fun tankyEnemy(defense: Int) = EnemyData(
        name = "golem",
        displayName = "Golem",
        hp = 1_000_000,
        combatStats = EnemyCombatStats(
            attackLevel = 1, strengthLevel = 1, defenseLevel = 1,
            attackBonus = 0, strengthBonus = 0,
        ),
        defensiveStats = EnemyDefensiveStats(
            attackDefense = defense, strengthDefense = defense, rangedDefense = defense, magicDefense = defense,
        ),
        xpDrops = mapOf("combat" to 20),
    )

    private fun totalMeleeDamage(seed: Int, defense: Int = 0, doubleHitChance: Double = 0.0, secondChance: Boolean = false) =
        CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("golem", 1))),
            enemies = mapOf("golem" to tankyEnemy(defense)),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 999,
            weaponStrengthBonus = 64,
            doubleHitChance = doubleHitChance,
            secondChance = secondChance,
            random = Random(seed),
        ).frames.sumOf { it.playerHits.sum() }

    @Test
    fun `a survivable dungeon run produces 60 frames`() {
        assertEquals(60, runStrongPlayer(seed = 1).frames.size)
    }

    @Test
    fun `guaranteed double hit roughly doubles melee damage`() {
        val base    = totalMeleeDamage(seed = 11)
        val doubled = totalMeleeDamage(seed = 11, doubleHitChance = 1.0)
        assertTrue("expected ~2x damage, got base=$base doubled=$doubled", doubled > base * 1.6)
    }

    @Test
    fun `second chance raises accuracy against a high-defense enemy`() {
        // Hit chance clamps to 0.15 here; one reroll lifts it to 1 - 0.85^2 = 0.2775.
        val base     = totalMeleeDamage(seed = 13, defense = 100_000)
        val rerolled = totalMeleeDamage(seed = 13, defense = 100_000, secondChance = true)
        assertTrue("expected ~1.85x damage, got base=$base rerolled=$rerolled", rerolled > base * 1.4)
    }

    @Test
    fun `the same seed reproduces an identical run`() {
        assertEquals(runStrongPlayer(seed = 42).frames, runStrongPlayer(seed = 42).frames)
    }

    @Test
    fun `a strong player accrues combat XP, scores kills, and never dies`() {
        val frames = runStrongPlayer(seed = 7).frames
        val totalXp = frames.sumOf { frame -> frame.xpBySkill.values.sum() }
        val totalKills = frames.sumOf { it.kills }

        assertTrue("expected positive combat XP", totalXp > 0)
        assertTrue("expected at least one kill", totalKills > 0)
        assertFalse("strong player should not die", frames.any { it.died })
    }

    @Test
    fun `recorded HP is never negative`() {
        assertTrue(runStrongPlayer(seed = 3).frames.all { it.hpAfter >= 0 })
    }

    @Test
    fun `the default attack speed still yields 25-tick frames`() {
        assertTrue(runStrongPlayer(seed = 5).frames.all { it.playerHits.size == 25 })
    }

    @Test
    fun `a fast ranged weapon consumes more than the legacy 1500-arrow cap`() {
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("rat", 1))),
            enemies = mapOf("rat" to weakEnemy()),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 999,
            combatStyle = "ranged",
            playerRanged = 99,
            availableArrows = mapOf("bronze_arrow" to 10_000),
            attackSpeedSec = 1.5,
            random = Random(1),
        )
        assertEquals(60, result.frames.size)
        assertTrue(result.frames.all { it.playerHits.size == 40 })
        val totalArrows = result.frames.sumOf { it.arrowsConsumed.values.sum() }
        assertEquals(2_400, totalArrows)
    }

    @Test
    fun `a fast magic weapon consumes runes at the scaled tick rate`() {
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("rat", 1))),
            enemies = mapOf("rat" to weakEnemy()),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 999,
            combatStyle = "magic",
            playerMagic = 99,
            spellMaxHit = 10,
            runeKey = "fire_rune",
            runeCostPerAttack = 2,
            availableRunes = 1_000_000,
            attackSpeedSec = 1.5,
            random = Random(2),
        )
        assertEquals(60, result.frames.size)
        val totalRunes = result.frames.sumOf { it.runesConsumed.values.sum() }
        assertEquals(60 * 40 * 2, totalRunes)
    }

    @Test
    fun `an empty spawn pool yields no frames but a valid duration`() {
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(emptyList()),
            enemies = emptyMap(),
            playerAttack = 10,
            playerStrength = 10,
            playerDefence = 10,
            agilityLevel = 1,
        )
        assertTrue(result.frames.isEmpty())
        assertEquals(SkillSimulator.sessionDurationMs(1), result.durationMs)
    }

    // ------------------------------------------------------------------
    // Raids: mercenary parties vs raid-tier bosses
    // ------------------------------------------------------------------

    /** The real raid bosses from the shipped data file, so tuning and tests cannot drift apart. */
    private val raidBosses: List<BossData> by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val text = File("src/main/assets/data/raid_bosses.json").readText()
        json.decodeFromString<Map<String, BossData>>(text).values.filter { it.raid }
    }

    /** Stats mirroring the elite roster tier in mercenaries.json (99+100 atk, effStr 209 with +110 gear, hp 380). */
    private fun eliteMerc(i: Int) = CombatSimulator.MercCombatant(
        id = "elite_$i", style = "melee", effAttack = 199, maxHit = 57, defense = 95, hpLevel = 380,
    )

    /** BIS-league solo player: level 99s plus prestige stat nodes, strong gear bonuses, best food supply. */
    private fun runRaid(
        boss: BossData,
        seed: Int,
        mercs: List<CombatSimulator.MercCombatant>,
        maxedOut: Boolean = false,
    ) = CombatSimulator.simulateBoss(
        boss = boss, bossKey = boss.id,
        // maxedOut adds combat potions on top and enables the prestige combat specials.
        playerAttack = if (maxedOut) 132 else 122,
        playerStrength = if (maxedOut) 132 else 122,
        playerDefence = if (maxedOut) 132 else 122,
        playerHp = 99,
        weaponAttackBonus = 85, weaponStrBonus = 85,
        equippedFood = mapOf("cooked_shark" to 300),
        foodHealValues = mapOf("cooked_shark" to 20),
        attackSpeedSec = 1.5,
        doubleHitChance = if (maxedOut) 0.15 else 0.0,
        secondChance = maxedOut,
        mercenaries = mercs,
        random = Random(seed),
    )

    @Test
    fun `no raid boss can be beaten solo even fully maxed with prestige specials`() {
        assertTrue("expected raid bosses in data", raidBosses.isNotEmpty())
        for (boss in raidBosses) for (seed in listOf(1, 7, 42, 99, 1234)) {
            val frames = runRaid(boss, seed, mercs = emptyList(), maxedOut = true)
            assertEquals("${boss.id} seed $seed: maxed solo player must not win", 0, frames.last().kills)
        }
    }

    @Test
    fun `every raid boss falls to a player with three elite mercenaries`() {
        for (boss in raidBosses) for (seed in listOf(1, 7, 42, 99, 1234)) {
            val frames = runRaid(boss, seed, mercs = (1..3).map { eliteMerc(it) })
            assertEquals("${boss.id} seed $seed: full elite party must win", 1, frames.last().kills)
            val allyDamage = frames.sumOf { it.allyHits.sum() }
            assertTrue("${boss.id} seed $seed: mercs must contribute damage", allyDamage > 0)
        }
    }

    @Test
    fun `the boss spreads its attacks across the party`() {
        // Deterministic per seed: with 3 extra targets the player soaks far fewer hits.
        val boss = raidBosses.first()
        val soloTaken  = runRaid(boss, seed = 5, mercs = emptyList()).sumOf { it.enemyHits.sum() }
        val partyTaken = runRaid(boss, seed = 5, mercs = (1..3).map { eliteMerc(it) }).sumOf { it.enemyHits.sum() }
        assertTrue("expected party player to take less damage (solo=$soloTaken party=$partyTaken)", partyTaken < soloTaken)
    }

    @Test
    fun `spawns follow their weights across a full run`() {
        // Two equal-weight one-hit enemies: kills must land near 50/50 rather than one
        // type dominating whole frames or chaining across them (issue #1557).
        val result = CombatSimulator.simulateDungeon(
            dungeon = dungeon(listOf(EnemySpawn("rat", 1), EnemySpawn("bat", 1))),
            enemies = mapOf("rat" to weakEnemy(), "bat" to weakEnemy().copy(name = "bat", displayName = "Bat")),
            playerAttack = 99,
            playerStrength = 99,
            playerDefence = 99,
            playerHp = 99,
            weaponStrengthBonus = 64,
            random = Random(7),
        )
        val killsByEnemy = mutableMapOf<String, Int>()
        result.frames.forEach { f ->
            f.killsByEnemy.forEach { (k, v) -> killsByEnemy[k] = (killsByEnemy[k] ?: 0) + v }
        }
        val total = killsByEnemy.values.sum()
        assertTrue("expected a large kill sample, got $total", total > 200)
        val ratShare = (killsByEnemy["rat"] ?: 0).toDouble() / total
        assertTrue("expected both types near 50/50, got $killsByEnemy", ratShare in 0.40..0.60)
    }
}
