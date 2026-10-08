package com.fantasyidler.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Idle Fantasy+ parallel lanes: the mainland (slot 0) and the isle (slot -1) eat from
 * one shared bag, so the uncollected-backlog food reservation must count both lanes
 * no matter where the player is standing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LaneFoodReservationTest {

    private lateinit var db: AppDatabase
    private lateinit var playerRepo: PlayerRepository
    private lateinit var sessionRepo: SessionRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val framesSerializer = ListSerializer(SessionFrame.serializer())

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val gameData = GameDataRepository(context, json)
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
        sessionRepo = SessionRepository(db.skillSessionDao(), context, json, gameData, db.playerDao(), playerRepo)
        runBlocking { playerRepo.getOrCreatePlayer() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun finishedFight(id: String, slot: Int, foodEaten: Int, completed: Boolean = true) = SkillSession(
        sessionId = id,
        skillName = "combat",
        startedAt = 1_000L,
        endsAt = 3_601_000L,
        frames = json.encodeToString(
            framesSerializer,
            listOf(
                SessionFrame(
                    minute = 0, xpGain = 0, xpBefore = 0L, xpAfter = 0L,
                    levelBefore = 0, levelAfter = 0,
                    foodConsumed = mapOf("shark" to foodEaten),
                ),
            ),
        ),
        completed = completed,
        activityKey = "beach_and_cliffs",
        isElderSession = slot == SessionRepository.ISLE_SLOT,
        workerSlot = slot,
    )

    @Test
    fun `backlog reservation sums the mainland and isle lanes from either location`() = runBlocking {
        db.skillSessionDao().insert(finishedFight("mainland", SessionRepository.PLAYER_SLOT, foodEaten = 100))
        db.skillSessionDao().insert(finishedFight("isle", SessionRepository.ISLE_SLOT, foodEaten = 50))
        // A worker's fight never bills the player's bag, and a running fight is not backlog.
        db.skillSessionDao().insert(finishedFight("worker", 1, foodEaten = 1_000))
        db.skillSessionDao().insert(finishedFight("running", SessionRepository.PLAYER_SLOT, foodEaten = 1_000, completed = false))

        playerRepo.updateFlags(playerRepo.getFlags().copy(onElderIsle = false))
        assertEquals(mapOf("shark" to 150), sessionRepo.pendingFoodConsumed())

        playerRepo.updateFlags(playerRepo.getFlags().copy(onElderIsle = true))
        assertEquals(mapOf("shark" to 150), sessionRepo.pendingFoodConsumed())
    }
}
