package com.fantasyidler.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.OwnedPet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every whole-row PlayerEntity mutator must serialize on
 * [PlayerRepository.playerMutex], and none may re-enter that mutex internally --
 * kotlinx Mutex is non-reentrant, so nested acquisition hangs forever.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class PlayerMutexCoverageTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var playerRepo: PlayerRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val json = Json { ignoreUnknownKeys = true }
        val gameData = GameDataRepository(context, json)
        playerRepo = PlayerRepository(
            db.playerDao(),
            db.questProgressDao(),
            db.farmingPatchDao(),
            json,
            DailyQuestRepository(gameData),
            WeeklyQuestRepository(gameData),
            BuffNotificationScheduler(context),
            gameData,
            BoostRepository(gameData),
            db,
        )
        runBlocking { playerRepo.getOrCreatePlayer() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * While an external holder owns playerMutex, [op] must not run at all;
     * after the holder releases, [op] must finish promptly (no nested-lock hang).
     */
    private fun probe(opName: String, op: suspend () -> Unit) = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val holder = launch { playerRepo.withLock { gate.await() } }
        var waited = 0
        while (!playerRepo.playerMutex.isLocked && waited < 2_000) { delay(5); waited += 5 }
        assertTrue("$opName: holder never acquired playerMutex", playerRepo.playerMutex.isLocked)

        val ranWhileLocked = withTimeoutOrNull(300) { op(); true }
        assertNull("$opName ran while playerMutex was held elsewhere (unlocked mutator)", ranWhileLocked)

        gate.complete(Unit)
        val finishedAfterRelease = withTimeoutOrNull(3_000) { op(); true }
        assertNotNull("$opName did not finish after lock release (internal re-entry deadlock)", finishedAfterRelease)

        holder.cancel()
    }

    @Test
    fun `all player entity mutators serialize on playerMutex without re-entering it`() {
        probe("deductSkillXp") { playerRepo.deductSkillXp("mining", 10L) }
        probe("debugAddSkillXp") { playerRepo.debugAddSkillXp("mining", 10L) }
        probe("buryBonesAtomic") { playerRepo.buryBonesAtomic("bones", 1, 100L) }
        probe("removeFromQueue") { playerRepo.removeFromQueue(0) }
        probe("evictQueueForSkill") { playerRepo.evictQueueForSkill("mining") }
        probe("moveQueueItem") { playerRepo.moveQueueItem(0, 0) }
        probe("incrementDungeonRun") { playerRepo.incrementDungeonRun("crypt") }
        probe("markWhatsNewSeen") { playerRepo.markWhatsNewSeen(1) }
        probe("dismissCharacterSetup") { playerRepo.dismissCharacterSetup() }
        probe("updateEquipped") { playerRepo.updateEquipped(mapOf("head" to null)) }
        probe("applyLoadout") { playerRepo.applyLoadout("melee", emptyMap()) }
        probe("updatePets") { playerRepo.updatePets(emptyList<OwnedPet>()) }
        probe("buyItem") { playerRepo.buyItem("probe_item", 1, 1) }
        probe("sellItem") { playerRepo.sellItem("probe_item", 1, 1) }
        probe("activateXpBoost") { playerRepo.activateXpBoost(1_000L, 0L) }
        probe("grantXpBoost") { playerRepo.grantXpBoost(1_000L) }
        probe("debugChangeRaceFree") { playerRepo.debugChangeRaceFree("human") }
    }
}
