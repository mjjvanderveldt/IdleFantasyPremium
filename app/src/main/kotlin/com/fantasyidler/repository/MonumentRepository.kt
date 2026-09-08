package com.fantasyidler.repository

import com.fantasyidler.data.model.PlayerFlags
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.sync.withLock

sealed class MonumentTouchResult {
    data class Blessing(val durationMs: Long) : MonumentTouchResult()
    data class BlessingExtended(val durationMs: Long) : MonumentTouchResult()
    data class Items(val items: Map<String, Int>) : MonumentTouchResult()
    object AlreadyTouchedToday : MonumentTouchResult()
    object NotUnlocked : MonumentTouchResult()
}

@Singleton
class MonumentRepository @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val buffNotifScheduler: BuffNotificationScheduler,
) {
    companion object {
        /** Lump costs of stages 1-4 (Foundation, Pillars, Statue, Gilded). */
        val STAGE_COSTS = listOf(1_000_000L, 10_000_000L, 50_000_000L, 100_000_000L)

        /** Stage 5 (Eternal Flame) total, funded incrementally via [contributeToFlame]. */
        const val FLAME_GOAL = 1_000_000_000L

        const val GOLDEN_GOOSE_PET_ID = "golden_goose"
        const val PATRON_TITLE_ID = "patron_of_the_realm"

        /** Blessing duration bonus once the Statue stage (3) is built. */
        const val BLESSING_BONUS_MS = 6 * 3_600_000L

        private const val TOUCH_BLESSING_MS = 2 * 3_600_000L
        private const val TOUCH_BLESSING_KEY = "blessed_focus"

        /** 1 in N touches rolls [TOUCH_JACKPOT] instead of a regular boon. */
        private const val TOUCH_JACKPOT_ONE_IN = 10

        // Base quantities; scaled up by [touchTierMultiplier]. Late-game items on purpose:
        // the stage-2 audience has paid 11M+ and found the original pocket-change boons
        // (15 big bones, 30 maple logs) not worth the tap.
        private val TOUCH_ITEM_BOONS = listOf(
            mapOf("carnival_ticket" to 10),
            mapOf("dragon_bone" to 25),
            mapOf("runite_ore" to 25, "platinum_ore" to 10),
            mapOf("magic_log" to 40, "redwood_log" to 20),
            mapOf("rune_essence" to 300),
            mapOf("ruby" to 8, "diamond" to 4),
            mapOf("raw_shark" to 60),
        )
        private val TOUCH_JACKPOT = mapOf("ancient_treasure" to 5)

        /** Later stages deepen the daily touch: x1 at Pillars up to x3 with the Eternal Flame lit. */
        private fun touchTierMultiplier(tier: Int): Double = when {
            tier >= 5 -> 3.0
            tier == 4 -> 2.0
            tier == 3 -> 1.5
            else      -> 1.0
        }

        /** yyyymmdd stamp of the current game day, rolling over at [resetHour] rather than midnight. */
        private fun today(resetHour: Int): Int = Calendar.getInstance().let {
            if (it.get(Calendar.HOUR_OF_DAY) < resetHour) it.add(Calendar.DAY_OF_YEAR, -1)
            it.get(Calendar.YEAR) * 10000 + it.get(Calendar.MONTH) * 100 + it.get(Calendar.DAY_OF_MONTH)
        }
    }

    /** Buys the next lump stage (1-4). Returns false when maxed, mid flame-funding, or short on coins. */
    suspend fun purchaseNextStage(): Boolean = playerRepo.playerMutex.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val nextStage = flags.monumentTier + 1
        if (nextStage > STAGE_COSTS.size) return@withLock false
        if (!playerRepo.spendCoinsUnlocked(STAGE_COSTS[nextStage - 1])) return@withLock false
        if (nextStage == 4) playerRepo.addPetIfNewUnlocked(GOLDEN_GOOSE_PET_ID, 10)
        val latest = playerRepo.getFlagsUnlocked()
        playerRepo.updateFlagsUnlocked(latest.copy(monumentTier = nextStage))
        true
    }

    /**
     * Contributes [amount] coins toward the Eternal Flame (requires stages 1-4 built).
     * Coins are sunk immediately; reaching [FLAME_GOAL] completes the monument, which
     * unlocks the Vault Guardian and the Patron of the Realm title.
     */
    suspend fun contributeToFlame(amount: Long): Boolean = playerRepo.playerMutex.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        if (flags.monumentTier != STAGE_COSTS.size) return@withLock false
        val toDonate = amount.coerceAtMost(FLAME_GOAL - flags.monumentFund)
        if (toDonate <= 0) return@withLock false
        if (!playerRepo.spendCoinsUnlocked(toDonate)) return@withLock false
        val latest  = playerRepo.getFlagsUnlocked()
        val newFund = latest.monumentFund + toDonate
        val done    = newFund >= FLAME_GOAL
        playerRepo.updateFlagsUnlocked(latest.copy(
            monumentFund  = newFund,
            monumentTier  = if (done) 5 else latest.monumentTier,
            unlockedTitles = if (done) latest.unlockedTitles + PATRON_TITLE_ID else latest.unlockedTitles,
        ))
        true
    }

    /** Once-a-day monument touch (stage 2+): a random small boon, never coins. */
    suspend fun touchMonument(): MonumentTouchResult = playerRepo.playerMutex.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        if (flags.monumentTier < 2) return@withLock MonumentTouchResult.NotUnlocked
        val day = today(flags.dailyResetHour)
        if (flags.monumentTouchDay == day) return@withLock MonumentTouchResult.AlreadyTouchedToday

        // Ironman characters never receive blessing boons (all XP/coin multipliers are inert);
        // the touch always rolls an item boon instead.
        if (!flags.ironman && Random.nextInt(4) == 0) {
            val blessingActive = flags.activeBlessingKey.isNotEmpty() &&
                flags.activeBlessingExpiresAt > System.currentTimeMillis()
            if (blessingActive) {
                // Players who keep Church blessings running would otherwise never see this
                // branch, so an active blessing is extended instead of skipped.
                val expiresAt = flags.activeBlessingExpiresAt + TOUCH_BLESSING_MS
                playerRepo.updateFlagsUnlocked(flags.copy(
                    monumentTouchDay        = day,
                    activeBlessingExpiresAt = expiresAt,
                ))
                buffNotifScheduler.cancelBlessingExpiry()
                buffNotifScheduler.scheduleBlessingExpiry(expiresAt)
                return@withLock MonumentTouchResult.BlessingExtended(TOUCH_BLESSING_MS)
            }
            val expiresAt = System.currentTimeMillis() + TOUCH_BLESSING_MS
            playerRepo.updateFlagsUnlocked(flags.copy(
                monumentTouchDay        = day,
                activeBlessingKey       = TOUCH_BLESSING_KEY,
                activeBlessingExpiresAt = expiresAt,
            ))
            buffNotifScheduler.scheduleBlessingExpiry(expiresAt)
            return@withLock MonumentTouchResult.Blessing(TOUCH_BLESSING_MS)
        }

        val mult = touchTierMultiplier(flags.monumentTier)
        val base = if (Random.nextInt(TOUCH_JACKPOT_ONE_IN) == 0) TOUCH_JACKPOT else TOUCH_ITEM_BOONS.random()
        val boon = base.mapValues { (_, qty) -> (qty * mult).roundToInt().coerceAtLeast(1) }
        playerRepo.addItemsUnlocked(boon)
        val latest = playerRepo.getFlagsUnlocked()
        playerRepo.updateFlagsUnlocked(latest.copy(monumentTouchDay = day))
        MonumentTouchResult.Items(boon)
    }

    fun touchedToday(flags: PlayerFlags): Boolean = flags.monumentTouchDay == today(flags.dailyResetHour)
}
