package com.fantasyidler.data.model

/**
 * Elder Isle activities per skill. Values are (activity key, level required) so the isle
 * Skills sheet can render them consistently. Level values match the elder skill's own scale
 * (elder mining, elder woodcutting, ...) — they are gated by the elder-side level, not the
 * mainland one, per the bonus-flow rule.
 *
 * Only the four gathering skills ship with wired activities in this build. Crafting and
 * combat activities land in later slices; the sheet shows "no activities yet" for those.
 */
object ElderActivities {
    data class Entry(val key: String, val levelRequired: Int)

    val MINING = listOf(
        Entry("mythrite_ore",   1),
        Entry("abyssal_ore",    30),
        Entry("voidsteel_ore",  60),
        Entry("starforged_ore", 85),
    )

    val WOODCUTTING = listOf(
        Entry("coastal_pine",  1),
        Entry("grove_oak",     30),
        Entry("abyssal_tree",  60),
        Entry("starwood_tree", 85),
    )

    val FISHING = listOf(
        Entry("raw_tidepool_crab", 1),
        Entry("raw_grove_bass",    30),
        Entry("raw_lavafin",       60),
        Entry("raw_deepwater_ray", 85),
    )

    /** Farming isn't an elder skill; no crops on the isle. */
    val FARMING = emptyList<Entry>()

    /** Elder Agility courses (see agility_courses.json). Level gates match the isle-tier
     *  pattern (1 / 30 / 60 / 85) used by other elder skills. */
    val AGILITY = listOf(
        Entry("elder_coastal_run",      1),
        Entry("elder_grove_traverse",   30),
        Entry("elder_volcano_scramble", 60),
        Entry("elder_abyssal_ascent",   85),
    )

    val SMITHING = listOf(
        Entry("mythrite_bar",   1),
        Entry("abyssal_bar",    30),
        Entry("voidsteel_bar",  60),
        Entry("starforged_bar", 85),
    )

    val COOKING = listOf(
        Entry("tidepool_crab", 1),
        Entry("grove_bass",    30),
        Entry("lavafin",       60),
        Entry("deepwater_ray", 85),
    )

    val CRAFTING = listOf(
        Entry("coastal_helm",   1),
        Entry("grove_helm",    30),
        Entry("volcanic_helm", 60),
        Entry("elder_helm",    85),
    )

    val FLETCHING     = emptyList<Entry>()
    val FIREMAKING    = emptyList<Entry>()
    val RUNECRAFTING  = emptyList<Entry>()

    fun forSkill(skillKey: String): List<Entry> = when (skillKey) {
        Skills.MINING       -> MINING
        Skills.WOODCUTTING  -> WOODCUTTING
        Skills.FISHING      -> FISHING
        Skills.FARMING      -> FARMING
        Skills.SMITHING     -> SMITHING
        Skills.COOKING      -> COOKING
        Skills.FLETCHING    -> FLETCHING
        Skills.CRAFTING     -> CRAFTING
        Skills.FIREMAKING   -> FIREMAKING
        Skills.RUNECRAFTING -> RUNECRAFTING
        Skills.AGILITY      -> AGILITY
        else                -> emptyList()
    }
}
