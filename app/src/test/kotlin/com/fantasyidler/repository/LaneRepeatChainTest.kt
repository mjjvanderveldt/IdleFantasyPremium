package com.fantasyidler.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.QueuedAction
import com.fantasyidler.data.model.RepeatChain
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
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
 * Idle Fantasy+ parallel lanes: the mainland and the isle each keep their own
 * "fight this N times" chain, so one location never cuts short or advances the other's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LaneRepeatChainTest {

    private lateinit var db: AppDatabase
    private lateinit var playerRepo: PlayerRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var starter: QueuedSessionStarter

    private val json = Json { ignoreUnknownKeys = true }
    private val framesSerializer = ListSerializer(SessionFrame.serializer())

    private val mainlandBoss = QueuedAction(skillName = "boss", activityKey = "demon_lord", skillDisplayName = "Demon Lord", repeatCount = 3)
    private val isleBoss = QueuedAction(
        skillName = "boss", activityKey = "last_elder", skillDisplayName = "The Last Elder", repeatCount = 5, isElderSession = true,
    )
    private val isleDungeon = QueuedAction(
        skillName = "combat", activityKey = "beach_and_cliffs", skillDisplayName = "Beach and Cliffs", repeatCount = 3, isElderSession = true,
    )

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

    private fun survivedRun(id: String, slot: Int, startedAt: Long) = SkillSession(
        sessionId = id,
        skillName = "combat",
        startedAt = startedAt,
        endsAt = startedAt + 3_600_000L,
        frames = json.encodeToString(
            framesSerializer,
            listOf(SessionFrame(minute = 0, xpGain = 0, xpBefore = 0L, xpAfter = 0L, levelBefore = 0, levelAfter = 0)),
        ),
        completed = true,
        activityKey = "beach_and_cliffs",
        isElderSession = slot == SessionRepository.ISLE_SLOT,
        workerSlot = slot,
    )

    @Test
    fun `starting, finishing or abandoning in one lane leaves the other lane's chain alone`() = runBlocking {
        playerRepo.stampBossRepeatStartUnlocked(mainlandBoss, isle = false)
        playerRepo.stampBossRepeatStartUnlocked(isleBoss, isle = true)
        assertEquals(RepeatChain(1, 3, mainlandBoss), playerRepo.getFlags().bossRepeatFor(false))
        assertEquals(RepeatChain(1, 5, isleBoss), playerRepo.getFlags().bossRepeatFor(true))

        // A single boss fight starting on the isle ends only the isle's chain.
        playerRepo.stampBossRepeatStartUnlocked(isleBoss.copy(repeatCount = 1), isle = true)
        assertEquals(RepeatChain(1, 3, mainlandBoss), playerRepo.getFlags().bossRepeatFor(false))
        assertEquals(RepeatChain(), playerRepo.getFlags().bossRepeatFor(true))

        // Abandoning on the mainland ends only the mainland's chain.
        playerRepo.stampBossRepeatStartUnlocked(isleBoss, isle = true)
        playerRepo.clearActiveBossRepeat(isle = false)
        assertEquals(RepeatChain(), playerRepo.getFlags().bossRepeatFor(false))
        assertEquals(RepeatChain(1, 5, isleBoss), playerRepo.getFlags().bossRepeatFor(true))

        // Dungeon chains are kept apart the same way.
        playerRepo.stampDungeonRepeatStartUnlocked(isleDungeon, isle = true)
        playerRepo.stampDungeonRepeatStartUnlocked(isleDungeon.copy(repeatCount = 1, isElderSession = false), isle = false)
        assertEquals(RepeatChain(1, 3, isleDungeon), playerRepo.getFlags().dungeonRepeatFor(true))
    }

    @Test
    fun `an isle chain from an older save moves out of the mainland fields`() {
        val old = PlayerFlags(activeDungeonRepeatIndex = 2, activeDungeonRepeatTotal = 3, activeDungeonRepeatSnapshot = isleDungeon)
        assertEquals(RepeatChain(2, 3, isleDungeon), old.dungeonRepeatFor(true))
        assertEquals(RepeatChain(), old.dungeonRepeatFor(false))

        val mainlandDungeon = isleDungeon.copy(activityKey = "goblin_caves", isElderSession = false)
        val written = old.withDungeonRepeatFor(false, RepeatChain(1, 2, mainlandDungeon))
        assertEquals(RepeatChain(1, 2, mainlandDungeon), written.dungeonRepeatFor(false))
        assertEquals(RepeatChain(2, 3, isleDungeon), written.dungeonRepeatFor(true))
        assertEquals(isleDungeon, written.isleDungeonRepeatSnapshot)
    }

    @Test
    fun `only the isle lane advances an isle chain, and runs 2 to N land on the isle`() = runBlocking {
        playerRepo.updateFlags(playerRepo.getFlags().withDungeonRepeatFor(true, RepeatChain(1, 3, isleDungeon)))

        // A mainland dungeon finishing with an empty mainland queue starts nothing.
        db.skillSessionDao().insert(survivedRun("mainland-run", SessionRepository.PLAYER_SLOT, startedAt = 1_000L))
        assertFalse(starter.startNextQueued(isle = false))
        assertEquals(RepeatChain(1, 3, isleDungeon), playerRepo.getFlags().dungeonRepeatFor(true))
        assertEquals("mainland-run", sessionRepo.getActiveSession(SessionRepository.PLAYER_SLOT)!!.sessionId)

        // The isle's run 1 finishing starts run 2 from the snapshot, in the isle lane.
        db.skillSessionDao().insert(survivedRun("isle-run-1", SessionRepository.ISLE_SLOT, startedAt = 2_000L))
        assertTrue("isle run 2 did not start", starter.startNextQueued(isle = true))
        assertEquals(2, playerRepo.getFlags().dungeonRepeatFor(true).index)
        val run2 = sessionRepo.getActiveSession(SessionRepository.ISLE_SLOT)!!
        assertTrue(run2.sessionId != "isle-run-1")
        assertEquals("beach_and_cliffs", run2.activityKey)
        assertTrue(run2.isElderSession)
        assertEquals("mainland-run", sessionRepo.getActiveSession(SessionRepository.PLAYER_SLOT)!!.sessionId)
    }
}
