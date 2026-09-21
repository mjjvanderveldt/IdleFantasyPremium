package com.fantasyidler.data.model

/**
 * Elder Isle's skill roster. Mirrors the mainland roster minus Prayer and Construction. Elder
 * skills reuse mainland skill keys because the state map itself is elder-only
 * (PlayerFlags.elderSkillLevels/Xp).
 *
 * Grouping keeps the UI in step with the mainland Skills object: gathering first, crafting
 * next, support (agility), combat last.
 */
object ElderSkills {

    /** Trimmed to skills that actually serve isle progression. Farming, Fletching, Firemaking,
     *  Runecrafting, Herblore, Construction, Prayer, Slayer, Mercantile, Thieving are all
     *  mainland-only — the isle doesn't need them. Agility IS included, so isle Agility
     *  training can shorten isle sessions from 60 → 45 min via elderSessionDurationMs. */
    val GATHERING       = listOf(Skills.MINING, Skills.FISHING, Skills.WOODCUTTING)
    val CRAFTING_SKILLS = listOf(Skills.SMITHING, Skills.COOKING)
    val SUPPORT         = listOf(Skills.AGILITY)
    val COMBAT          = listOf(
        Skills.ATTACK, Skills.STRENGTH, Skills.DEFENSE,
        Skills.RANGED, Skills.MAGIC, Skills.HITPOINTS,
    )

    /** All 13 elder skill keys, in a stable render order. */
    val ALL = GATHERING + CRAFTING_SKILLS + SUPPORT + COMBAT
}
