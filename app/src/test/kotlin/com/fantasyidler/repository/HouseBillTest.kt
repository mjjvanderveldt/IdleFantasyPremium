package com.fantasyidler.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.Skills
import kotlinx.coroutines.runBlocking
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

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class HouseBillTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var playerRepo: PlayerRepository
    private lateinit var houseRepo: HouseRepository

    // Starter room sits at (7,7) size 4x4; half-cell coords inside it.
    private val spotA = 15 to 15
    private val spotB = 17 to 17

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val json = Json { ignoreUnknownKeys = true }
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
        houseRepo = HouseRepository(playerRepo, gameData, boostRepo)
        runBlocking {
            playerRepo.getOrCreatePlayer()
            houseRepo.ensureStarterRoom()
            houseRepo.beginEdit()
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fund() = runBlocking {
        playerRepo.applyMultiSkillResults(
            xpPerSkill = emptyMap(),
            itemsGained = mapOf("plank" to 2000, "iron_nail" to 2000),
            coinsGained = 1_000_000L,
        )
    }

    @Test
    fun `drafting an item leaves the built house and wallet untouched`() = runBlocking {
        fund()
        val coinsBefore = playerRepo.getOrCreatePlayer().coins

        assertEquals(HouseActionResult.SUCCESS, houseRepo.placeItem("bed_default", spotA.first, spotA.second))

        val flags = playerRepo.getFlags()
        assertTrue(flags.house!!.placements.isEmpty())
        assertEquals(1, flags.houseDraft!!.layout.placements.size)
        assertEquals(coinsBefore, playerRepo.getOrCreatePlayer().coins)

        val bill = houseRepo.computeBill(flags.house!!, flags.houseDraft!!, 1, 0)
        assertEquals(1, bill.lines.size)
        assertEquals(HouseBillLine.Kind.ITEM, bill.lines[0].kind)
        assertEquals("bed_default", bill.lines[0].itemKey)
    }

    @Test
    fun `purchase pays the bill and commits the draft`() = runBlocking {
        fund()
        houseRepo.placeItem("bed_default", spotA.first, spotA.second)
        val flags = playerRepo.getFlags()
        val bill = houseRepo.computeBill(flags.house!!, flags.houseDraft!!, 1, 0)
        val coinsBefore = playerRepo.getOrCreatePlayer().coins
        val plankBefore = playerRepo.getInventory()["plank"] ?: 0
        val xpBefore = playerRepo.getSkillXp()[Skills.CONSTRUCTION] ?: 0L

        assertEquals(HouseActionResult.SUCCESS, houseRepo.purchaseBuild())

        val after = playerRepo.getFlags()
        assertNull(after.houseDraft)
        assertEquals(1, after.house!!.placements.size)
        assertEquals("bed_default", after.house!!.placements[0].item)
        assertEquals(coinsBefore - bill.netCoins, playerRepo.getOrCreatePlayer().coins)
        assertEquals(plankBefore - (bill.netMaterials()["plank"] ?: 0), playerRepo.getInventory()["plank"] ?: 0)
        assertEquals(xpBefore + bill.xp, playerRepo.getSkillXp()[Skills.CONSTRUCTION] ?: 0L)
    }

    @Test
    fun `purchase fails without materials and changes nothing`() = runBlocking {
        houseRepo.placeItem("bed_default", spotA.first, spotA.second)

        assertEquals(HouseActionResult.INSUFFICIENT_MATERIALS, houseRepo.purchaseBuild())

        val flags = playerRepo.getFlags()
        assertTrue(flags.house!!.placements.isEmpty())
        assertNotNull(flags.houseDraft)
    }

    @Test
    fun `rearranging built furniture is a free apply`() = runBlocking {
        fund()
        houseRepo.placeItem("bed_default", spotA.first, spotA.second)
        houseRepo.purchaseBuild()
        houseRepo.beginEdit()
        assertEquals(HouseActionResult.SUCCESS, houseRepo.moveItem(0, spotB.first, spotB.second))

        val flags = playerRepo.getFlags()
        val bill = houseRepo.computeBill(flags.house!!, flags.houseDraft!!, 1, 0)
        assertTrue(bill.isEmpty)
        assertTrue(houseRepo.hasLayoutChanges(flags.house!!, flags.houseDraft!!.layout))

        val coinsBefore = playerRepo.getOrCreatePlayer().coins
        assertEquals(HouseActionResult.SUCCESS, houseRepo.purchaseBuild())
        assertEquals(coinsBefore, playerRepo.getOrCreatePlayer().coins)
        assertEquals(spotB.first, playerRepo.getFlags().house!!.placements[0].x)
    }

    @Test
    fun `removed built item returns to storage and re-placing it is free`() = runBlocking {
        fund()
        houseRepo.placeItem("bed_default", spotA.first, spotA.second)
        houseRepo.purchaseBuild()

        houseRepo.beginEdit()
        houseRepo.removeItem(0)
        houseRepo.purchaseBuild()
        assertEquals(1, playerRepo.getFlags().house!!.storage["bed_default"])

        houseRepo.beginEdit()
        houseRepo.placeItem("bed_default", spotB.first, spotB.second)
        val flags = playerRepo.getFlags()
        assertTrue(houseRepo.computeBill(flags.house!!, flags.houseDraft!!, 1, 0).isEmpty)
        val plankBefore = playerRepo.getInventory()["plank"] ?: 0
        houseRepo.purchaseBuild()
        val after = playerRepo.getFlags().house!!
        assertEquals(1, after.placements.size)
        assertNull(after.storage["bed_default"])
        assertEquals(plankBefore, playerRepo.getInventory()["plank"] ?: 0)
    }

    @Test
    fun `new room is billed and committed`() = runBlocking {
        fund()
        // Level 15 required for the second room tier.
        playerRepo.applyMultiSkillResults(mapOf(Skills.CONSTRUCTION to 10_000_000L), emptyMap())

        assertEquals(HouseActionResult.SUCCESS, houseRepo.buyRoom(11, 7))
        var flags = playerRepo.getFlags()
        assertEquals(1, flags.house!!.rooms.size)
        assertEquals(2, flags.houseDraft!!.layout.rooms.size)
        val level = playerRepo.getSkillLevels()[Skills.CONSTRUCTION] ?: 1
        val bill = houseRepo.computeBill(flags.house!!, flags.houseDraft!!, level, 0)
        assertEquals(1, bill.lines.count { it.kind == HouseBillLine.Kind.NEW_ROOM })

        assertEquals(HouseActionResult.SUCCESS, houseRepo.purchaseBuild())
        flags = playerRepo.getFlags()
        assertEquals(2, flags.house!!.rooms.size)
        assertNull(flags.houseDraft)
    }

    @Test
    fun `blueprint round trip prices only what is new`() = runBlocking {
        fund()
        houseRepo.placeItem("bed_default", spotA.first, spotA.second)
        houseRepo.purchaseBuild()

        // Draft a second bed and save the two-bed layout as a blueprint.
        houseRepo.beginEdit()
        houseRepo.placeItem("bed_default", spotB.first, spotB.second)
        assertEquals(HouseActionResult.SUCCESS, houseRepo.saveBlueprint(0, "two beds"))
        houseRepo.discardDraft()
        houseRepo.beginEdit()

        assertEquals(0, houseRepo.loadBlueprint(0))
        val flags = playerRepo.getFlags()
        val draft = flags.houseDraft!!
        assertEquals(2, draft.layout.placements.size)
        // The owned room matched, so nothing room-related is billed.
        assertEquals(listOf<Int?>(0), draft.builtRoomIndex)
        val bill = houseRepo.computeBill(flags.house!!, draft, 1, 0)
        assertEquals(1, bill.lines.size)
        assertEquals(HouseBillLine.Kind.ITEM, bill.lines[0].kind)
        assertEquals(1, bill.lines[0].units)
        // Exactly one of the two drafted beds is unpurchased.
        assertEquals(1, houseRepo.ghostPlacements(flags.house!!, draft.layout).size)
    }

    @Test
    fun `under leveled build drafts fine but purchase is blocked`() = runBlocking {
        fund()
        assertEquals(HouseActionResult.SUCCESS, houseRepo.buyRoom(11, 7))
        val flags = playerRepo.getFlags()
        assertEquals(2, flags.houseDraft!!.layout.rooms.size)
        assertEquals(15, houseRepo.computeBill(flags.house!!, flags.houseDraft!!, 1, 0).requiredLevel)

        assertEquals(HouseActionResult.INSUFFICIENT_LEVEL, houseRepo.purchaseBuild())
        assertEquals(1, playerRepo.getFlags().house!!.rooms.size)

        playerRepo.applyMultiSkillResults(mapOf(Skills.CONSTRUCTION to 10_000_000L), emptyMap())
        assertEquals(HouseActionResult.SUCCESS, houseRepo.purchaseBuild())
        assertEquals(2, playerRepo.getFlags().house!!.rooms.size)
    }

    @Test
    fun `discard throws away the draft`() = runBlocking {
        fund()
        houseRepo.placeItem("bed_default", spotA.first, spotA.second)
        houseRepo.discardDraft()
        assertNull(playerRepo.getFlags().houseDraft)
        assertTrue(playerRepo.getFlags().house!!.placements.isEmpty())
    }
}
