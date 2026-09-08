package com.fantasyidler.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.FarmingPatch
import com.fantasyidler.data.model.PlayerExport
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.QuestProgress
import com.fantasyidler.data.model.SkillSession
import com.fantasyidler.data.model.SkillSessionExport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Provider

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class SaveSlotSwitchTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var json: Json
    private lateinit var playerRepo: PlayerRepository
    private lateinit var sessionRepo: SessionRepository
    private lateinit var saveSlotRepo: SaveSlotRepository
    private lateinit var globalStateRepo: GlobalStateRepository
    private lateinit var starter: QueuedSessionStarter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        json = Json { ignoreUnknownKeys = true }
        val gameData = GameDataRepository(context, json)
        val dailyQuestRepo = DailyQuestRepository(gameData)
        val weeklyQuestRepo = WeeklyQuestRepository(gameData)
        val boostRepo = BoostRepository(gameData)
        val buffNotifScheduler = BuffNotificationScheduler(context)
        playerRepo = PlayerRepository(
            db.playerDao(),
            db.questProgressDao(),
            db.farmingPatchDao(),
            json,
            dailyQuestRepo,
            weeklyQuestRepo,
            buffNotifScheduler,
            gameData,
            boostRepo,
            db,
        )
        sessionRepo = SessionRepository(db.skillSessionDao(), context, json, gameData, db.playerDao(), playerRepo)
        val backupScheduler = BackupScheduler(context, sessionRepo, GlobalStateRepository(db.globalStateDao()))
        val questRepo = QuestRepository(db.questProgressDao(), gameData)
        globalStateRepo = GlobalStateRepository(db.globalStateDao())
        val townRepo = TownRepository(gameData, playerRepo, questRepo, boostRepo)
        val mercRepo = MercenaryRepository(playerRepo, gameData)
        val queuedSessionStarter = QueuedSessionStarter(
            boostRepo, context, playerRepo, sessionRepo, townRepo, gameData, mercRepo, json,
        )
        starter = queuedSessionStarter
        val workerStarter = WorkerQueuedSessionStarter(boostRepo, playerRepo, sessionRepo, gameData, json)
        val seasonalEventRepo = SeasonalEventRepository(playerRepo, gameData, dailyQuestRepo, context)
        val farmingRepo = FarmingRepository(
            context, db, db.farmingPatchDao(), playerRepo, gameData,
            seasonalEventRepo, globalStateRepo, json, boostRepo,
        )
        val guildRepo = GuildRepository(playerRepo, db.questProgressDao(), gameData, Provider { townRepo })
        saveSlotRepo = SaveSlotRepository(
            context, playerRepo, sessionRepo, questRepo, farmingRepo, guildRepo,
            globalStateRepo, queuedSessionStarter, workerStarter, backupScheduler,
            buffNotifScheduler, json,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedCharacterA(base: Long) {
        db.playerDao().upsert(
            playerRepo.getOrCreatePlayer().copy(
                skillLevels = """{"mining":42}""",
                skillXp = """{"mining":123456}""",
                inventory = """{"iron_ore":99}""",
                equipped = """{"head":"bronze_helm"}""",
                pets = """[{"id":"rock_golem"}]""",
                coins = 111_111L,
                flags = json.encodeToString<PlayerFlags>(PlayerFlags(currentHp = 66)),
            )
        )
        db.questProgressDao().upsert(QuestProgress("sheep_shearer", progress = 3))
        db.farmingPatchDao().upsert(FarmingPatch(patchNumber = 0, cropType = "guam_seed", plantedAt = base))
        sessionRepo.insertSession(
            SkillSession(
                sessionId = "a_completed",
                skillName = "woodcutting",
                startedAt = base - 3_600_000L,
                endsAt = base - 1_800_000L,
                completed = true,
                activityKey = "oak_tree",
            )
        )
        sessionRepo.insertSession(
            SkillSession(
                sessionId = "a_active",
                skillName = "mining",
                startedAt = base,
                endsAt = base + 3_600_000L,
                completed = false,
                activityKey = "iron_ore",
            )
        )
    }

    @Test
    fun `switchTo empty slot snapshots outgoing character and creates fresh state`() = runBlocking {
        globalStateRepo.setActiveSaveSlot(1)
        seedCharacterA(System.currentTimeMillis())

        val ironmanDemoted = saveSlotRepo.switchTo(2)

        assertFalse(ironmanDemoted)
        assertEquals(2, saveSlotRepo.activeSlot())

        val freshPlayer = playerRepo.getOrCreatePlayer()
        assertEquals(0L, freshPlayer.coins)
        assertTrue(db.questProgressDao().getAllProgress().isEmpty())
        assertTrue(db.farmingPatchDao().getAllPatches().all { it.cropType == null && it.plantedAt == null })
        assertEquals(null, sessionRepo.getActiveSession())
        assertEquals(0, sessionRepo.getAllCompletedSessions().size)

        val slotsDir = File(context.filesDir, "save_slots")
        assertTrue(File(slotsDir, "slot_1.json").exists())
        assertTrue(File(slotsDir, "slot_1.meta.json").exists())
        val snapshot = json.decodeFromString<PlayerExport>(File(slotsDir, "slot_1.json").readText())
        assertEquals(111_111L, snapshot.coins)
        assertEquals("""{"mining":42}""", snapshot.skillLevels)
        val meta = json.decodeFromString<SaveSlotMeta>(File(slotsDir, "slot_1.meta.json").readText())
        assertEquals(111_111L, meta.coins)
    }

    @Test
    fun `switchTo back restores original coins xp and keeps absolute session end time`() = runBlocking {
        globalStateRepo.setActiveSaveSlot(1)
        val base = System.currentTimeMillis()
        seedCharacterA(base)
        saveSlotRepo.switchTo(2)

        db.playerDao().upsert(playerRepo.getOrCreatePlayer().copy(coins = 222_222L))

        val secondDemoted = saveSlotRepo.switchTo(1)

        assertFalse(secondDemoted)
        assertEquals(1, saveSlotRepo.activeSlot())
        assertTrue(saveSlotRepo.hasMultipleCharacters())

        val restored = playerRepo.getOrCreatePlayer()
        assertEquals(111_111L, restored.coins)
        assertEquals("""{"mining":42}""", restored.skillLevels)
        assertEquals("""{"mining":123456}""", restored.skillXp)
        assertEquals("""{"iron_ore":99}""", restored.inventory)
        assertEquals("""{"head":"bronze_helm"}""", restored.equipped)
        assertEquals("""[{"id":"rock_golem"}]""", restored.pets)
        assertEquals(66, json.decodeFromString<PlayerFlags>(restored.flags).currentHp)

        val active = sessionRepo.getActiveSession()
        assertNotNull(active)
        assertEquals("a_active", active!!.sessionId)
        assertFalse(active.completed)
        assertEquals(base + 3_600_000L, active.endsAt)
        val completed = sessionRepo.getAllCompletedSessions()
        assertEquals(listOf("a_completed"), completed.map { it.sessionId })

        assertTrue(File(context.filesDir, "save_slots/slot_2.json").exists())
    }

    @Test
    fun `imported incomplete session anchors monotonic clock so post-import clock jump cannot complete it`() = runBlocking {
        globalStateRepo.setActiveSaveSlot(1)
        seedCharacterA(System.currentTimeMillis())
        saveSlotRepo.switchTo(2)
        val snapshot = File(context.filesDir, "save_slots/slot_1.json").readText()

        saveSlotRepo.importFullSave(snapshot)

        val imported = sessionRepo.getActiveSession()!!
        assertEquals("a_active", imported.sessionId)
        assertFalse(imported.completed)
        assertNotNull(imported.startElapsedMs)
        assertTrue(imported.endsAt > System.currentTimeMillis())
        assertNull(
            sessionRepo.getAllCompletedSessions()
                .single { it.sessionId == "a_completed" }.startElapsedMs,
        )

        val jumpMs = 8L * 3_600_000L
        db.skillSessionDao().update(
            imported.copy(startedAt = imported.startedAt - jumpMs, endsAt = imported.endsAt - jumpMs),
        )

        sessionRepo.recoverActiveSession(starter)

        val after = sessionRepo.getSession("a_active")!!
        assertFalse(after.completed)
        assertEquals(1, sessionRepo.getAllCompletedSessions().size)
    }

    @Test
    fun `deleteSlot refuses to delete the active slot files`() = runBlocking {
        globalStateRepo.setActiveSaveSlot(1)
        seedCharacterA(System.currentTimeMillis())
        saveSlotRepo.switchTo(2)
        saveSlotRepo.switchTo(1)

        val slotOneJson = File(context.filesDir, "save_slots/slot_1.json")
        val slotOneMeta = File(context.filesDir, "save_slots/slot_1.meta.json")
        assertTrue(slotOneJson.exists())
        assertTrue(slotOneMeta.exists())

        saveSlotRepo.deleteSlot(1)

        assertTrue(slotOneJson.exists())
        assertTrue(slotOneMeta.exists())
        assertEquals(1, saveSlotRepo.activeSlot())
    }

    @Test
    fun `deleteSlot removes inactive slot files`() = runBlocking {
        globalStateRepo.setActiveSaveSlot(1)
        seedCharacterA(System.currentTimeMillis())
        saveSlotRepo.switchTo(2)
        saveSlotRepo.switchTo(1)

        val slotTwoJson = File(context.filesDir, "save_slots/slot_2.json")
        val slotTwoMeta = File(context.filesDir, "save_slots/slot_2.meta.json")
        assertTrue(slotTwoJson.exists())
        assertTrue(slotTwoMeta.exists())

        saveSlotRepo.deleteSlot(2)

        assertFalse(slotTwoJson.exists())
        assertFalse(slotTwoMeta.exists())
        assertTrue(File(context.filesDir, "save_slots/slot_1.json").exists())
    }
}
