package com.fantasyidler.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.QueuedAction
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Idle Fantasy+ parallel lanes: the mainland (slot 0) and the isle (slot -1) eat from
 * one shared bag, so the food reservation must count both lanes no matter where the
 * player is standing -- the finished, uncollected backlog and any fight still running.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LaneFoodReservationTest {

    private lateinit var db: AppDatabase
    private lateinit var playerRepo: PlayerRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var starter: QueuedSessionStarter

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
        val questRepo = QuestRepository(db.questProgressDao(), gameData)
        val townRepo = TownRepository(gameData, playerRepo, questRepo, boostRepo)
        starter = QueuedSessionStarter(
            boostRepo, context, playerRepo, sessionRepo, townRepo, gameData, MercenaryRepository(playerRepo, gameData), json,
        )
        runBlocking { playerRepo.getOrCreatePlayer() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fight(
        id: String,
        slot: Int,
        foodEaten: Int,
        completed: Boolean = true,
        startedAt: Long = 1_000L,
        food: String = "shark",
    ) = SkillSession(
        sessionId = id,
        skillName = "combat",
        startedAt = startedAt,
        endsAt = startedAt + 3_600_000L,
        frames = json.encodeToString(
            framesSerializer,
            listOf(
                SessionFrame(
                    minute = 0, xpGain = 0, xpBefore = 0L, xpAfter = 0L,
                    levelBefore = 0, levelAfter = 0,
                    foodConsumed = mapOf(food to foodEaten),
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
        db.skillSessionDao().insert(fight("mainland", SessionRepository.PLAYER_SLOT, foodEaten = 100))
        db.skillSessionDao().insert(fight("isle", SessionRepository.ISLE_SLOT, foodEaten = 50))
        // A worker's fight never bills the player's bag.
        db.skillSessionDao().insert(fight("worker", 1, foodEaten = 1_000))

        playerRepo.updateFlags(playerRepo.getFlags().copy(onElderIsle = false))
        assertEquals(mapOf("shark" to 150), sessionRepo.pendingFoodConsumed())

        playerRepo.updateFlags(playerRepo.getFlags().copy(onElderIsle = true))
        assertEquals(mapOf("shark" to 150), sessionRepo.pendingFoodConsumed())
    }

    @Test
    fun `a fight still running in either lane is reserved too, and only once`() = runBlocking {
        db.skillSessionDao().insert(fight("mainland-done", SessionRepository.PLAYER_SLOT, foodEaten = 100))
        db.skillSessionDao().insert(
            fight("mainland-running", SessionRepository.PLAYER_SLOT, foodEaten = 200, completed = false, startedAt = 5_000_000L),
        )
        db.skillSessionDao().insert(
            fight("isle-running", SessionRepository.ISLE_SLOT, foodEaten = 25, completed = false, startedAt = 5_000_000L),
        )
        db.skillSessionDao().insert(fight("worker-running", 1, foodEaten = 1_000, completed = false, startedAt = 5_000_000L))

        playerRepo.updateFlags(playerRepo.getFlags().copy(onElderIsle = false))
        assertEquals(mapOf("shark" to 325), sessionRepo.pendingFoodConsumed())
        playerRepo.updateFlags(playerRepo.getFlags().copy(onElderIsle = true))
        assertEquals(mapOf("shark" to 325), sessionRepo.pendingFoodConsumed())

        // Finishing a running fight moves it into the backlog without counting it twice.
        db.skillSessionDao().markCompleted("isle-running")
        assertEquals(mapOf("shark" to 325), sessionRepo.pendingFoodConsumed())
    }

    @Test
    fun `a queued mainland fight does not get the food a running isle fight will eat`() = runBlocking {
        playerRepo.addItems(mapOf("manta_ray" to 836))
        playerRepo.updateFlags(playerRepo.getFlags().copy(equippedFood = mapOf("manta_ray" to 1), onElderIsle = false))
        // The isle fight started first and was simulated eating every manta in the bag.
        db.skillSessionDao().insert(
            fight("isle-running", SessionRepository.ISLE_SLOT, foodEaten = 836, completed = false, startedAt = 5_000_000L, food = "manta_ray"),
        )

        assertTrue(
            playerRepo.enqueueAction(
                QueuedAction(skillName = "combat", activityKey = "beach_and_cliffs", skillDisplayName = "Beach and Cliffs"),
            ),
        )
        assertTrue("queued mainland fight did not start", starter.startNextQueued(isle = false))

        val mainland = sessionRepo.getActiveSession(SessionRepository.PLAYER_SLOT)!!
        val offered = json.decodeFromString(framesSerializer, mainland.frames).firstOrNull()?.foodAtStart?.get("manta_ray") ?: 0
        assertEquals("the running isle fight already spoke for all 836 manta", 0, offered)
    }
}
