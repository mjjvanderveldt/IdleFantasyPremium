package com.fantasyidler.util

import android.content.Context
import android.text.format.DateFormat
import com.fantasyidler.R
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SkillSession
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.Date
import kotlin.math.floor

/** Format a raw XP long as a readable string (e.g. 1,234,567 → "1.2M"). */
fun Long.formatXp(): String = when {
    this >= 1_000_000L -> "%.1fM".format(this / 1_000_000.0)
    this >= 1_000L     -> "%,d".format(this)
    else               -> toString()
}

/** Parenthetical multiplier breakdown for a flat XP grant, e.g. "(50,000 × 2 × 1.28)", or null if no bonus applied. */
fun xpMultiplierBreakdown(baseXp: Long, boostFactor: Long, blessingMult: Float, prestigeXpPct: Int = 0): String? {
    if (boostFactor <= 1L && blessingMult <= 1f && prestigeXpPct <= 0) return null
    val factors = buildList {
        if (boostFactor > 1L) add("$boostFactor")
        if (blessingMult > 1f) add("%.2f".format(blessingMult).trimEnd('0').trimEnd('.'))
        if (prestigeXpPct > 0) add("%.2f".format(1.0 + prestigeXpPct / 100.0).trimEnd('0').trimEnd('.'))
    }
    return "(${baseXp.formatXp()} × ${factors.joinToString(" × ")})"
}

/**
 * Format a coin amount with thousands separators. Amounts >= 1M are floored (not rounded) to one
 * decimal place so the displayed value never overstates what the player actually has — otherwise
 * e.g. 99.96M would display as "100.0M" and make an affordability-gated button look wrongly
 * disabled (issue #1470).
 */
fun Long.formatCoins(): String = when {
    this >= 1_000_000L -> "%.1fM".format(floor(this / 100_000.0) / 10.0)
    this >= 1_000L     -> "%,d".format(this)
    else               -> toString()
}

/** Format an Int coin amount with thousands separators. */
fun Int.formatCoins(): String = toLong().formatCoins()

/** Abbreviated coin format for compact UI (e.g. 50000 → "50k"). */
fun Long.formatCoinsBrief(): String = when {
    this >= 1_000_000L -> "%.1fM".format(floor(this / 100_000.0) / 10.0)
    this >= 1_000L     -> "${this / 1000}k"
    else               -> toString()
}

/** Format an integer quantity as a readable string, respecting the [compact] setting. */
fun Int.formatQuantity(compact: Boolean = false): String = toLong().formatQuantity(compact)

/** Format a Long quantity as a readable string, respecting the [compact] setting. */
fun Long.formatQuantity(compact: Boolean = false): String = when {
    compact && this >= 1_000_000L -> {
        val formatted = "%.2f".format(this / 1_000_000.0).trimEnd('0').trimEnd('.', ',')
        "${formatted}M"
    }
    compact && this >= 1_000L -> {
        val formatted = "%.1f".format(this / 1_000.0).trimEnd('0').trimEnd('.', ',')
        "${formatted}k"
    }
    this >= 1_000L || this <= -1_000L -> "%,d".format(this)
    else -> toString()
}

/** Formats an epoch-ms timestamp as a clock time, respecting the device's 12/24-hour preference. */
fun Long.toClockTime(context: Context): String =
    DateFormat.getTimeFormat(context).format(Date(this))

/** Formats the player's local daily reset hour as a clock string, respecting the device's 12/24-hour preference. */
fun dailyResetClockTime(context: Context, resetHour: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, resetHour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }
    return cal.timeInMillis.toClockTime(context)
}

/**
 * Convert an epoch-ms "ends_at" timestamp to a human-readable countdown string, optionally
 * with the completion clock time, e.g. "42m 10s (1:45 PM)" or "42m 10s" or "Complete"
 */
fun Long.toCountdown(context: Context, showEndTime: Boolean = true): String {
    val remaining = this - System.currentTimeMillis()
    if (remaining <= 0) return context.getString(R.string.duration_complete)
    val totalSeconds = remaining / 1_000
    val seconds = totalSeconds % 60
    val secondsPart = context.getString(R.string.duration_seconds, seconds)
    val duration = if (totalSeconds < 60) secondsPart
                   else "${((totalSeconds / 60) * 60_000).formatDurationMs(context)} $secondsPart"
    return if (showEndTime) "$duration (${toClockTime(context)})" else duration
}

/**
 * Convert an epoch-ms "started_at" timestamp to a relative time string.
 * e.g. "5m ago", "1h 2m ago"
 */
fun Long.toRelativeTime(): String {
    val elapsedMs = System.currentTimeMillis() - this
    if (elapsedMs < 60_000) return "Just now"
    val minutes = elapsedMs / 60_000
    return if (minutes < 60) {
        "${minutes}m ago"
    } else {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0L) "${h}h ago" else "${h}h ${m}m ago"
    }
}

/**
 * Format a raw millisecond duration (not an epoch) as a human-readable string, e.g. "2h 30m",
 * "45m", or "4y 1mo 1w 1d 8h 54m". Zero-valued units are omitted; months are 30 days, years 365.
 * Unit suffixes come from string resources so each locale can abbreviate its own way (issue #1399).
 */
fun Long.formatDurationMs(context: Context): String =
    context.withAppLocale().let { ctx -> formatDurationMs { resId, value -> ctx.getString(resId, value) } }

/** Testable core of [formatDurationMs]; [unitString] renders one unit from its template resource. */
internal fun Long.formatDurationMs(unitString: (Int, Long) -> String): String {
    val totalSeconds = this / 1_000
    var rem = totalSeconds / 60
    if (rem == 0L) return unitString(R.string.duration_seconds, totalSeconds)
    val minutesPerDay = 24L * 60
    val years  = rem / (365 * minutesPerDay); rem %= 365 * minutesPerDay  // this does allow 1y 12mo 4 days, which is acceptable
    val months = rem / (30 * minutesPerDay);  rem %= 30 * minutesPerDay
    val weeks  = rem / (7 * minutesPerDay);   rem %= 7 * minutesPerDay
    val days   = rem / minutesPerDay;         rem %= minutesPerDay
    val hours  = rem / 60
    val minutes = rem % 60
    return buildList {
        if (years   > 0) add(unitString(R.string.duration_years, years))
        if (months  > 0) add(unitString(R.string.duration_months, months))
        if (weeks   > 0) add(unitString(R.string.duration_weeks, weeks))
        if (days    > 0) add(unitString(R.string.duration_days, days))
        if (hours   > 0) add(unitString(R.string.duration_hours, hours))
        if (minutes > 0) add(unitString(R.string.duration_minutes, minutes))
    }.joinToString(" ")
}

/**
 * Crafting-type sessions (mainly hired-worker batch jobs) are pre-simulated as a single frame
 * that already holds the whole job's assigned total, however long the job runs. Gathering/combat
 * sessions use many real per-minute frames instead, so this only returns something for that
 * single-frame batch case. Most of these batch types record the total in [SessionFrame.items]
 * (e.g. bars, potions, runes); Prayer's only records a bone-burial count in [SessionFrame.kills],
 * so that's used as a fallback, keyed by this session's own activity (the bone type).
 */
fun SkillSession.singleBatchItems(json: Json): Map<String, Int> = try {
    val decodedFrames: List<SessionFrame> = json.decodeFromString(frames)
    val frame = decodedFrames.singleOrNull()
    when {
        frame == null            -> emptyMap()
        frame.items.isNotEmpty() -> frame.items
        frame.kills > 0          -> mapOf(activityKey to frame.kills)
        else                      -> emptyMap()
    }
} catch (_: Exception) {
    emptyMap()
}

/** Clamp an Int to the valid skill level range [1, 99]. */
fun Int.clampLevel(): Int = coerceIn(1, 99)

/** Short display label for a skill key, used in compact bonus rows. */
fun String.toSkillAbbrev(): String = when (this) {
    "attack"       -> "Atk"
    "strength"     -> "Str"
    "defense"      -> "Def"
    "ranged"       -> "Rng"
    "magic"        -> "Mag"
    "hitpoints"    -> "HP"
    "prayer"       -> "Pry"
    "mining"       -> "Min"
    "woodcutting"  -> "WC"
    "fishing"      -> "Fish"
    "firemaking"   -> "FM"
    "cooking"      -> "Cook"
    "smithing"     -> "Smith"
    "crafting"     -> "Craft"
    "fletching"    -> "Fletch"
    "agility"      -> "Agil"
    "runecrafting" -> "RC"
    "farming"      -> "Farm"
    else           -> take(4).replaceFirstChar { it.uppercase() }
}
