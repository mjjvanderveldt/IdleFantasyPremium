package com.fantasyidler.data.model

/**
 * Elder Isle content whitelists — used to filter gathering targets, cooking/smithing/crafting
 * recipes, and firemaking logs down to isle-only entries when the player is sailed. Mainland
 * items simply won't show up in the isle Skills sheets.
 */
object ElderContent {
    val ORES  = setOf("mythrite_ore", "abyssal_ore", "voidsteel_ore", "starforged_ore")
    val TREES = setOf("coastal_pine", "grove_oak", "abyssal_tree", "starwood_tree")
    val FISH  = setOf("raw_tidepool_crab", "raw_grove_bass", "raw_lavafin", "raw_deepwater_ray")
    val LOGS  = setOf("coastal_pine_log", "grove_oak_log", "abyssal_log", "starwood_log")
    /** Farming is not an elder skill — no elder crops exist. */
    val CROPS = emptySet<String>()

    /** Smithing on the isle covers bars AND all elder armor (armor was moved off Crafting). */
    val SMITHING_RECIPES = setOf(
        "mythrite_bar", "abyssal_bar", "voidsteel_bar", "starforged_bar",
        "coastal_helm", "coastal_platebody", "coastal_platelegs", "coastal_boots",
        "grove_helm", "grove_platebody", "grove_platelegs", "grove_boots",
        "volcanic_helm", "volcanic_platebody", "volcanic_platelegs", "volcanic_boots",
        "elder_helm", "elder_platebody", "elder_platelegs", "elder_boots",
        "elder_cape", "elder_shield", "elder_signet_ring", "elder_amulet",
    )
    val COOKING_RECIPES  = setOf("tidepool_crab", "grove_bass", "lavafin", "deepwater_ray")
    /** Crafting is not an elder skill anymore; armor lives on Smithing. */
    val CRAFTING_RECIPES = emptySet<String>()
    val FLETCHING_RECIPES = emptySet<String>()   // no elder fletching yet
    val HERBLORE_RECIPES  = emptySet<String>()   // no elder herblore yet
    val RUNES             = emptySet<String>()   // no elder runes yet
    /** Isle agility courses. Their success rate uses elder Agility level on isle. */
    val AGILITY_COURSES   = setOf(
        "elder_coastal_run",
        "elder_grove_traverse",
        "elder_volcano_scramble",
        "elder_abyssal_ascent",
    )
}
