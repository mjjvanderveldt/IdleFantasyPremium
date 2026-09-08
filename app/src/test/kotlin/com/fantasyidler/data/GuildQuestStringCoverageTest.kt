package com.fantasyidler.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guild quest names fall back to the raw English JSON name when quest_<id>_name is missing
 * from the string resources, which silently makes them untranslatable in Weblate (issue
 * #1714). This guards every quest in the guild data files, including ones added later.
 */
class GuildQuestStringCoverageTest {

    private fun mainFile(relative: String): File =
        listOf("src/main/$relative", "app/src/main/$relative")
            .map(::File)
            .firstOrNull(File::exists)
            ?: error("Could not locate $relative from ${File(".").absolutePath}")

    @Test
    fun `every guild quest has a name string resource`() {
        val ids = buildSet {
            Json.parseToJsonElement(mainFile("assets/data/guild_quests.json").readText())
                .jsonObject.values.forEach { add(it.jsonObject.getValue("id").jsonPrimitive.content) }
            Json.parseToJsonElement(mainFile("assets/data/guild_daily_quests.json").readText())
                .jsonArray.forEach { add(it.jsonObject.getValue("id").jsonPrimitive.content) }
        }

        val resourced = Regex("name=\"quest_([A-Za-z0-9_]+)_name\"")
            .findAll(mainFile("res/values/strings_guild_quests.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

        val missing = ids - resourced
        assertTrue(
            "Guild quests without a quest_<id>_name entry in strings_guild_quests.xml " +
                "(add the English name to values/ and run scripts/sync_locale_strings.py): $missing",
            missing.isEmpty(),
        )
    }
}
