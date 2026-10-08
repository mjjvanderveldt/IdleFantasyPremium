package com.fantasyidler.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.QueuedAction
import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.data.model.Skills
import com.fantasyidler.ui.viewmodel.isSkillSessionStillEligible
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A prestige between queueing and collecting must not pay out the pre-prestige plan's XP
 * at level 1 (Discord report: six queued mercantile tasks collected after a prestige took
 * the skill from 1 to 57). The queue-time level is stamped on the action and floors the
 * session's levelAtStart, so the existing void check catches it.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class QueueLevelStampTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var playerRepo: PlayerRepository
    private lateinit var gameData: GameDataRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val json = Json { ignoreUnknownKeys = true }
        gameData = GameDataRepository(context, json)
        val boostRepo = BoostRepository(gameData)
        playerRepo = PlayerRepository(
            db.playerDao(),
            db.questProgressDao(),
            db.farmingPatchDao(),
            json,
            DailyQuestRepository(gameData),
            WeeklyQuestRepository(gameData),
            BuffNotificationScheduler(context),
            gameData,
            boostRepo,
            db,
        )
        runBlocking { playerRepo.getOrCreatePlayer() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun action(skill: String) = QueuedAction(
        skillName = skill,
        activityKey = "any",
        skillDisplayName = skill,
    )

    @Test
    fun `enqueue stamps the current skill level on the action`() = runBlocking {
        playerRepo.applyMultiSkillResults(mapOf(Skills.MERCANTILE to 10_000_000L), emptyMap())
        val level = playerRepo.getSkillLevels()[Skills.MERCANTILE] ?: 1
        assertTrue("test premise: leveled well above 1", level > 50)

        assertTrue(playerRepo.enqueueAction(action(Skills.MERCANTILE)))

        assertEquals(level, playerRepo.getFlags().sessionQueue.first().levelAtQueue)
    }

    @Test
    fun `session started from a pre-prestige queue stamp is voided at collect`() {
        // The starters floor levelAtStart at the action's levelAtQueue, so a session begun
        // after the prestige still carries the pre-prestige level and fails eligibility.
        val session = SkillSession(
            sessionId = "s",
            skillName = Skills.MERCANTILE,
            startedAt = 0L,
            endsAt = 1L,
            activityKey = "any",
            levelAtStart = 80,
        )
        assertFalse(isSkillSessionStillEligible(session, mapOf(Skills.MERCANTILE to 1), gameData))
        assertTrue(isSkillSessionStillEligible(session, mapOf(Skills.MERCANTILE to 80), gameData))
    }

    // Issue #1970: queued isle sessions were dropped when the mainland level (here 1 after a
    // prestige) was below the requirement, even though the isle level qualified.
    @Test
    fun `queued isle action gates on the isle level`() {
        val mainland = mapOf(Skills.FISHING to 1)
        val isle = mapOf(Skills.FISHING to 67)
        val isleAction = action(Skills.FISHING).copy(activityKey = "raw_grove_bass", isElderSession = true)
        assertEquals(67, queuedActionCurrentLevel(isleAction, mainland, isle, gameData))
        assertEquals(1, queuedActionCurrentLevel(isleAction.copy(isElderSession = false), mainland, isle, gameData))
    }

    @Test
    fun `queued isle dungeon gates on the isle combat level`() {
        val combatSkills = listOf(Skills.ATTACK, Skills.STRENGTH, Skills.DEFENSE, Skills.HITPOINTS, Skills.RANGED, Skills.MAGIC, Skills.PRAYER)
        val mainland = combatSkills.associateWith { 1 }
        val isle = combatSkills.associateWith { 60 }
        val dungeon = action("combat").copy(activityKey = "ancient_forest", isElderSession = true)
        assertTrue(queuedActionCurrentLevel(dungeon, mainland, isle, gameData) > queuedActionCurrentLevel(dungeon.copy(isElderSession = false), mainland, isle, gameData))
    }

    @Test
    fun `isle session is never voided by mainland levels at collect`() {
        val session = SkillSession(
            sessionId = "s",
            skillName = Skills.FISHING,
            startedAt = 0L,
            endsAt = 1L,
            activityKey = "raw_grove_bass",
            levelAtStart = 67,
            isElderSession = true,
        )
        assertTrue(isSkillSessionStillEligible(session, mapOf(Skills.FISHING to 1), gameData))
    }
}
