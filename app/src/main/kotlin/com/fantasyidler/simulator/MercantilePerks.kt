package com.fantasyidler.simulator

/**
 * Mercantile shop-trading ladder. The buy discount and sell bonus climb together,
 * 5% per tier; the same numbers feed the Shop's real prices and the perks summary
 * on the Mercantile screen, so the two can never drift apart.
 */
object MercantilePerks {

    val TIER_LEVELS = listOf(20, 40, 60, 80, 99)

    /** Percent (0..25) that shop buy prices drop and sell prices rise at [level]. */
    fun tradePct(level: Int): Int = TIER_LEVELS.count { level >= it } * 5

    /** Next level that improves the trade percentages, or null once maxed. */
    fun nextTierLevel(level: Int): Int? = TIER_LEVELS.firstOrNull { level < it }
}
