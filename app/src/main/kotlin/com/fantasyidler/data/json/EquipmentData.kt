package com.fantasyidler.data.json

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EquipmentData(
    val name: String,
    @SerialName("display_name")           val displayName: String,
    val slot: String,
    @SerialName("combat_style")           val combatStyle: String? = null,
    val description: String = "",
    @SerialName("attack_bonus")           val attackBonus: Int = 0,
    @SerialName("strength_bonus")         val strengthBonus: Int = 0,
    @SerialName("defense_bonus")          val defenseBonus: Int = 0,
    @SerialName("ranged_attack_bonus")    val rangedAttackBonus: Int? = null,
    @SerialName("ranged_strength_bonus")  val rangedStrengthBonus: Int? = null,
    @SerialName("magic_attack_bonus")     val magicAttackBonus: Int? = null,
    @SerialName("magic_damage_bonus")     val magicDamageBonus: Int? = null,
    val requirements: Map<String, Int> = emptyMap(),
    @SerialName("infinite_runes")          val infiniteRunes: String? = null,
    @SerialName("attack_speed")            val attackSpeed: Double? = null,
    // Gathering/skilling tool efficiency fields (null for combat gear)
    @SerialName("mining_efficiency")      val miningEfficiency: Float? = null,
    @SerialName("woodcutting_efficiency") val woodcuttingEfficiency: Float? = null,
    @SerialName("fishing_efficiency")     val fishingEfficiency: Float? = null,
    @SerialName("farming_efficiency")     val farmingEfficiency: Float? = null,
    @SerialName("smithing_efficiency")    val smithingEfficiency: Float? = null,
    @SerialName("firemaking_efficiency")  val firemakingEfficiency: Float? = null,
    @SerialName("agility_efficiency")     val agilityEfficiency: Float? = null,
    @SerialName("cooking_efficiency")     val cookingEfficiency: Float? = null,
    @SerialName("thieving_efficiency")    val thievingEfficiency: Float? = null,
    @SerialName("cape_skill")             val capeSkill: String? = null,
    @SerialName("cape_bonus")             val capeBonus: Float = 0f,
    @SerialName("two_handed")             val twoHanded: Boolean = false,
    /** Non-null marks an heirloom item and names the skill whose XP levels it (and whose level gates it). */
    @SerialName("heirloom_skill")         val heirloomSkill: String? = null,
    /** Item-level-1 stats an heirloom starts from; the normal fields above hold its level-99 potential. */
    @SerialName("heirloom_base")          val heirloomBase: HeirloomBase? = null,
)

/** The starting (item level 1) values for the stats an heirloom scales between. */
@Serializable
data class HeirloomBase(
    @SerialName("attack_bonus")           val attackBonus: Int = 0,
    @SerialName("strength_bonus")         val strengthBonus: Int = 0,
    @SerialName("defense_bonus")          val defenseBonus: Int = 0,
    @SerialName("ranged_attack_bonus")    val rangedAttackBonus: Int = 0,
    @SerialName("ranged_strength_bonus")  val rangedStrengthBonus: Int = 0,
    @SerialName("magic_attack_bonus")     val magicAttackBonus: Int = 0,
    @SerialName("magic_damage_bonus")     val magicDamageBonus: Int = 0,
    /** Starting value for whichever single *_efficiency field the tool defines. */
    val efficiency: Float = 1.0f,
)
