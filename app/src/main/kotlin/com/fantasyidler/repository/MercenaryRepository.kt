package com.fantasyidler.repository

import com.fantasyidler.data.json.MercenaryData
import com.fantasyidler.data.model.HiredMercenary
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.simulator.CombatSimulator
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.random.Random

enum class MercHireResult { SUCCESS, NOT_IN_POOL, PARTY_FULL, ALREADY_HIRED, NOT_ENOUGH_COINS }

/**
 * Raid mercenary contracts. Hiring lasts [CONTRACT_DURATION_MS] from hire time (a late-day
 * hire is not cut short by the daily reset, issue: PR #1583), the pool of candidates
 * rotates daily, and up to [MAX_PARTY] contracts run concurrently. Hiring is
 * deliberately NOT ironman-gated: raid bosses are unbeatable solo by design, and coins
 * are self-earned.
 */
@Singleton
class MercenaryRepository @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val gameData: GameDataRepository,
) {
    companion object {
        const val MAX_PARTY = 3
        const val POOL_SIZE = 6
        const val CONTRACT_DURATION_MS = 24 * 60 * 60 * 1000L
        private const val POOL_SEED_SALT = 68_111L
    }

    private val roster: Map<String, MercenaryData> by lazy { gameData.mercenaries.associateBy { it.id } }

    /** Today's hireable candidates: a day-seeded pick of two per tier, rotating at the daily reset. */
    fun dailyPool(flags: PlayerFlags): List<MercenaryData> {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < flags.dailyResetHour) cal.add(Calendar.DAY_OF_YEAR, -1)
        val daySeed = cal.get(Calendar.YEAR) * 10000L + cal.get(Calendar.MONTH) * 100 + cal.get(Calendar.DAY_OF_MONTH)
        val rng = Random(daySeed + POOL_SEED_SALT)
        return gameData.mercenaries
            .groupBy { it.tier }
            .toSortedMap()
            .values
            .flatMap { tierMercs -> tierMercs.shuffled(rng).take(POOL_SIZE / 3) }
    }

    /** Contracts still running at [now], resolved against the roster. */
    fun activeContracts(flags: PlayerFlags, now: Long = System.currentTimeMillis()): List<Pair<MercenaryData, HiredMercenary>> =
        flags.hiredMercenaries
            .filter { it.expiresAt > now }
            .mapNotNull { hired -> roster[hired.mercId]?.let { it to hired } }

    suspend fun hire(mercId: String): MercHireResult {
        val flags = playerRepo.getFlags()
        val now = System.currentTimeMillis()
        val active = flags.hiredMercenaries.filter { it.expiresAt > now }
        val merc = dailyPool(flags).firstOrNull { it.id == mercId } ?: return MercHireResult.NOT_IN_POOL
        if (active.any { it.mercId == mercId }) return MercHireResult.ALREADY_HIRED
        if (active.size >= MAX_PARTY) return MercHireResult.PARTY_FULL
        if (!playerRepo.spendCoins(merc.hireCost)) return MercHireResult.NOT_ENOUGH_COINS
        val expiresAt = now + CONTRACT_DURATION_MS
        playerRepo.updateFlags(
            playerRepo.getFlags().copy(hiredMercenaries = active + HiredMercenary(mercId, expiresAt))
        )
        return MercHireResult.SUCCESS
    }

    /** Ends a contract early. No refund: mercenaries do not give money back. */
    suspend fun dismiss(mercId: String) {
        val flags = playerRepo.getFlags()
        val now = System.currentTimeMillis()
        playerRepo.updateFlags(
            flags.copy(hiredMercenaries = flags.hiredMercenaries.filter { it.mercId != mercId && it.expiresAt > now })
        )
    }

    /** Simulator combatants for the currently valid contracts. */
    fun combatants(flags: PlayerFlags, now: Long = System.currentTimeMillis()): List<CombatSimulator.MercCombatant> =
        activeContracts(flags, now).map { (m, _) -> toCombatant(m) }

    fun toCombatant(m: MercenaryData): CombatSimulator.MercCombatant {
        val effStr = m.strengthLevel + m.strengthBonus
        return CombatSimulator.MercCombatant(
            id        = m.id,
            style     = m.combatStyle,
            effAttack = m.attackLevel + m.attackBonus,
            maxHit    = max(1, 1 + effStr * (m.strengthBonus + 64) / 640),
            defense   = m.defenseLevel,
            hpLevel   = m.hp,
        )
    }
}
