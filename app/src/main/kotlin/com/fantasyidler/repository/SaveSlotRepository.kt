package com.fantasyidler.repository

import android.content.Context
import android.os.SystemClock
import com.fantasyidler.data.model.PlayerExport
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.SkillSessionExport
import com.fantasyidler.data.model.toExport
import com.fantasyidler.data.model.toSkillSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight per-slot summary written alongside each snapshot so the picker
 *  never has to parse the (potentially multi-MB) full save file. */
@Serializable
data class SaveSlotMeta(
    /** Raw PlayerFlags JSON — gives character name, appearance, and ironman. */
    @SerialName("flags") val flags: String,
    /** Raw Map<String, Int> JSON of skill levels. */
    @SerialName("skill_levels") val skillLevels: String,
    @SerialName("coins") val coins: Long,
    @SerialName("last_played_at") val lastPlayedAt: Long,
)

/** Picker-facing view of one save slot. */
data class SlotInfo(
    val slot: Int,
    val isActive: Boolean,
    val exists: Boolean,
    val flags: PlayerFlags? = null,
    val skillLevels: Map<String, Int> = emptyMap(),
    val coins: Long = 0L,
    val lastPlayedAt: Long = 0L,
)

/**
 * Manages up to [MAX_SLOTS] character save slots.
 *
 * The live Room DB always holds only the ACTIVE character. Inactive slots are stored as
 * full save-export JSON files (the same [PlayerExport] format as manual export/import) under
 * filesDir/save_slots/. Switching slots snapshots the outgoing character to its file, then
 * restores the incoming one through the same import path used by Settings > Import save —
 * incomplete sessions keep their absolute end times (they finish in real time while the
 * character is inactive; only manual file imports freeze remaining time) and alarms are
 * re-registered for the incoming character.
 */
@Singleton
class SaveSlotRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepo: PlayerRepository,
    private val sessionRepo: SessionRepository,
    private val questRepo: QuestRepository,
    private val farmingRepo: FarmingRepository,
    private val guildRepo: GuildRepository,
    private val globalStateRepo: GlobalStateRepository,
    private val queuedSessionStarter: QueuedSessionStarter,
    private val workerStarter: WorkerQueuedSessionStarter,
    private val backupScheduler: BackupScheduler,
    private val buffNotifScheduler: BuffNotificationScheduler,
    private val json: Json,
) {
    private val slotsDir: File get() = File(context.filesDir, "save_slots")

    private fun slotFile(slot: Int) = File(slotsDir, "slot_$slot.json")
    private fun metaFile(slot: Int) = File(slotsDir, "slot_$slot.meta.json")

    // ------------------------------------------------------------------
    // Full-save export/import — shared with SettingsViewModel
    // ------------------------------------------------------------------

    /** All sessions worth persisting: the active one plus completed ones, player and both worker slots. */
    suspend fun collectSessionExports(): List<SkillSessionExport> = buildList {
        sessionRepo.getActiveSession()?.let { add(it.toExport()) }
        addAll(sessionRepo.getAllCompletedSessions().map { it.toExport() })
        for (slot in 1..2) {
            sessionRepo.getActiveWorkerSession(slot)?.let { add(it.toExport()) }
            addAll(sessionRepo.getAllCompletedWorkerSessions(slot).map { it.toExport() })
        }
    }.distinctBy { it.sessionId }

    /** Serializes the current character (player + quests + patches + sessions) to save-file JSON. */
    suspend fun exportFullSave(): String = playerRepo.exportSave(collectSessionExports())

    /**
     * Overwrites the current character with [jsonString] and restores its sessions.
     * With [freezeSessionTimers] (manual file imports) incomplete sessions resume with the
     * remaining time they had at export; without it (slot switches) they keep their absolute
     * end times so they finish in real time while the character is inactive, and the recovery
     * pass below completes them and catches up the queue exactly as if the app had been closed.
     * Session/buff/backup alarms are re-registered for the restored character.
     * Returns true if an edited ironman save was demoted to a regular character.
     */
    suspend fun importFullSave(jsonString: String, freezeSessionTimers: Boolean = true): Boolean {
        val (export, ironmanDemoted) = playerRepo.importSave(jsonString)
        // Imported flags may predate the guild-leveling rework (reputation -> daily-count),
        // and importing overwrites the current save's migration state wholesale, so this
        // must re-run here rather than relying on the one-time app-startup call.
        guildRepo.migrateLegacyGuildReputation()
        sessionRepo.deleteAllSessions()
        sessionRepo.deleteAllWorkerSessions()
        val now = System.currentTimeMillis()
        val exportedAt = export.exportedAt.takeIf { it > 0L } ?: now
        export.sessions.forEach { s ->
            val restored = if (s.completed || !freezeSessionTimers) {
                s.toSkillSession()
            } else {
                val remainingMs = (s.endsAt - exportedAt).coerceAtLeast(0L)
                s.toSkillSession().copy(endsAt = now + remainingMs)
            }
            val session = if (s.completed) restored else restored.copy(
                startElapsedMs = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - s.startedAt),
                startBootCount = sessionRepo.currentBootCount(),
            )
            try {
                sessionRepo.insertSession(session)
            } catch (_: Exception) {
                // A duplicate/bad session in an old export file shouldn't abort the whole restore.
            }
        }
        sessionRepo.recoverActiveSession(queuedSessionStarter)
        sessionRepo.recoverActiveWorkerSession(1, workerStarter)
        sessionRepo.recoverActiveWorkerSession(2, workerStarter)
        rescheduleAlarmsFromFlags()
        return ironmanDemoted
    }

    // ------------------------------------------------------------------
    // Slot management
    // ------------------------------------------------------------------

    suspend fun activeSlot(): Int = globalStateRepo.getActiveSaveSlot()

    /** True when more than one character exists: the active slot always exists, so any inactive slot file counts. */
    suspend fun hasMultipleCharacters(): Boolean = withContext(Dispatchers.IO) {
        val active = globalStateRepo.getActiveSaveSlot()
        (1..MAX_SLOTS).any { it != active && (metaFile(it).exists() || slotFile(it).exists()) }
    }

    suspend fun slotInfos(): List<SlotInfo> = withContext(Dispatchers.IO) {
        val active = globalStateRepo.getActiveSaveSlot()
        (1..MAX_SLOTS).map { slot ->
            if (slot == active) {
                val player = playerRepo.getOrCreatePlayer()
                SlotInfo(
                    slot         = slot,
                    isActive     = true,
                    exists       = true,
                    flags        = decodeFlags(player.flags),
                    skillLevels  = decodeLevels(player.skillLevels),
                    coins        = player.coins,
                    lastPlayedAt = System.currentTimeMillis(),
                )
            } else {
                readInactiveSlot(slot)
            }
        }
    }

    /**
     * Saves the current character into its slot file, then loads [targetSlot].
     * An empty target slot becomes a brand-new character ([createIronman] decides its mode);
     * the fresh flags have characterSetupDone=false, so the Home screen shows the setup sheet.
     * Returns true if the loaded slot held an edited ironman save that was demoted.
     */
    /**
     * Fires after every successful character switch. ViewModels caching per-character UI
     * selections (active spell, arrow, potion, weapon style) must reset on it, or the old
     * character's picks override the new character's saved loadout (issue: spell leaking
     * across save slots).
     */
    private val _switchEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val switchEvents: SharedFlow<Unit> = _switchEvents

    suspend fun switchTo(targetSlot: Int, createIronman: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        require(targetSlot in 1..MAX_SLOTS) { "Invalid slot $targetSlot" }
        val current = globalStateRepo.getActiveSaveSlot()
        if (targetSlot == current) return@withContext false

        // Back up the outgoing character to its own external file while it is still live,
        // so an inactive character always has a fresh backup to restore from (issue #1640:
        // a cleared app storage lost the character whose backup never fired while active).
        backupScheduler.performBackup(playerRepo)

        snapshotCurrent(current)

        val target = slotFile(targetSlot)
        var ironmanDemoted = false
        if (target.exists()) {
            ironmanDemoted = importFullSave(target.readText(), freezeSessionTimers = false)
        } else {
            sessionRepo.deleteAllSessions()
            sessionRepo.deleteAllWorkerSessions()
            questRepo.resetAllProgress()
            farmingRepo.resetAllPatches()
            playerRepo.resetProgression(ironman = createIronman)
            rescheduleAlarmsFromFlags()
        }
        globalStateRepo.setActiveSaveSlot(targetSlot)
        _switchEvents.tryEmit(Unit)
        ironmanDemoted
    }

    /** Deletes an INACTIVE slot's files permanently. The active slot cannot be deleted here. */
    suspend fun deleteSlot(slot: Int) = withContext(Dispatchers.IO) {
        if (slot == globalStateRepo.getActiveSaveSlot()) return@withContext
        slotFile(slot).delete()
        metaFile(slot).delete()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun snapshotCurrent(slot: Int) {
        writeAtomic(slotFile(slot), exportFullSave())
        val player = playerRepo.getOrCreatePlayer()
        val meta = SaveSlotMeta(
            flags        = player.flags,
            skillLevels  = player.skillLevels,
            coins        = player.coins,
            lastPlayedAt = System.currentTimeMillis(),
        )
        writeAtomic(metaFile(slot), json.encodeToString(json.serializersModule.serializer<SaveSlotMeta>(), meta))
    }

    /** Buff-expiry and periodic-backup alarms follow the character; re-arm them from live flags. */
    private suspend fun rescheduleAlarmsFromFlags() {
        val flags = playerRepo.getFlags()
        buffNotifScheduler.cancelXpBoostExpiry()
        buffNotifScheduler.cancelBlessingExpiry()
        if (flags.xpBoostExpiresAt > System.currentTimeMillis()) {
            buffNotifScheduler.scheduleXpBoostExpiry(flags.xpBoostExpiresAt)
        }
        if (flags.activeBlessingExpiresAt > System.currentTimeMillis()) {
            buffNotifScheduler.scheduleBlessingExpiry(flags.activeBlessingExpiresAt)
        }
        backupScheduler.schedule(flags.backupFrequency)
    }

    private fun readInactiveSlot(slot: Int): SlotInfo {
        val meta = metaFile(slot)
        if (meta.exists()) {
            try {
                val parsed: SaveSlotMeta = json.decodeFromString(meta.readText())
                return SlotInfo(
                    slot         = slot,
                    isActive     = false,
                    exists       = true,
                    flags        = decodeFlags(parsed.flags),
                    skillLevels  = decodeLevels(parsed.skillLevels),
                    coins        = parsed.coins,
                    lastPlayedAt = parsed.lastPlayedAt,
                )
            } catch (_: Exception) {
                // Fall through to the full save file below.
            }
        }
        val save = slotFile(slot)
        if (save.exists()) {
            try {
                val export: PlayerExport = json.decodeFromString(save.readText())
                return SlotInfo(
                    slot         = slot,
                    isActive     = false,
                    exists       = true,
                    flags        = decodeFlags(export.flags),
                    skillLevels  = decodeLevels(export.skillLevels),
                    coins        = export.coins,
                    lastPlayedAt = export.exportedAt,
                )
            } catch (_: Exception) {
                // Corrupt slot file: surface it as empty rather than crashing the picker.
            }
        }
        return SlotInfo(slot = slot, isActive = false, exists = false)
    }

    private fun decodeFlags(raw: String): PlayerFlags? =
        try { json.decodeFromString<PlayerFlags>(raw) } catch (_: Exception) { null }

    private fun decodeLevels(raw: String): Map<String, Int> =
        try { json.decodeFromString(raw) } catch (_: Exception) { emptyMap() }

    private fun writeAtomic(target: File, content: String) {
        slotsDir.mkdirs()
        val tmp = File(slotsDir, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail across some filesystems; fall back to a direct write.
            target.writeText(content)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_SLOTS = 3
    }
}
