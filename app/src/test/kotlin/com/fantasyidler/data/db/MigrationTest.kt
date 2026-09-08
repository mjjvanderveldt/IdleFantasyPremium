package com.fantasyidler.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the hand-written Room migrations ([MIGRATION_1_2], [MIGRATION_2_3])
 * by applying them directly to an in-memory SQLite database seeded with the
 * version-1 `skill_sessions` schema (taken from the exported `app/schemas/1.json`).
 *
 * Runs on the JVM via Robolectric (no emulator). This exercises the migration
 * SQL itself — column additions, defaults, and the worker_slot backfill — which
 * is the part most likely to corrupt real save data if it regresses.
 */
@RunWith(AndroidJUnit4::class)
// Robolectric 4.12.x has no Android 35 image yet; the project targets SDK 35.
@Config(manifest = Config.NONE, sdk = [34])
class MigrationTest {

    /** The v1 `skill_sessions` table exactly as Room generated it (schemas/1.json). */
    private val createSkillSessionsV1 =
        "CREATE TABLE IF NOT EXISTS `skill_sessions` (" +
            "`session_id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `skill_name` TEXT NOT NULL, " +
            "`started_at` INTEGER NOT NULL, `ends_at` INTEGER NOT NULL, `data` TEXT NOT NULL, " +
            "`completed` INTEGER NOT NULL, `activity_key` TEXT NOT NULL, PRIMARY KEY(`session_id`))"

    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        helper?.close()
    }

    /** Opens a fresh in-memory database already at the version-1 schema. */
    private fun openV1Database(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext())
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(createSkillSessionsV1)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Migrations are applied explicitly by each test.
                }
            })
            .build()
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper = openHelper // kept so @After can close it
        return openHelper.writableDatabase
    }

    private fun insertSession(
        db: SupportSQLiteDatabase,
        id: String,
        isWorker: Int? = null,
        efficiency: Double? = null,
    ) {
        val cols = StringBuilder("session_id, user_id, skill_name, started_at, ends_at, data, completed, activity_key")
        val vals = StringBuilder("'$id', 1, 'mining', 0, 0, '{}', 0, 'iron_ore'")
        if (isWorker != null) {
            cols.append(", is_worker_session"); vals.append(", $isWorker")
        }
        if (efficiency != null) {
            cols.append(", efficiency_multiplier"); vals.append(", $efficiency")
        }
        db.execSQL("INSERT INTO skill_sessions ($cols) VALUES ($vals)")
    }

    private fun SupportSQLiteDatabase.intColumn(sql: String): Int =
        query(sql).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) }

    private fun SupportSQLiteDatabase.doubleColumn(sql: String): Double =
        query(sql).use { c -> assertTrue(c.moveToFirst()); c.getDouble(0) }

    @Test
    fun `migration 1 to 2 adds worker columns with defaults`() {
        val db = openV1Database()
        insertSession(db, "s1")

        MIGRATION_1_2.migrate(db)

        assertEquals(0, db.intColumn("SELECT is_worker_session FROM skill_sessions WHERE session_id = 's1'"))
        assertEquals(
            1.0,
            db.doubleColumn("SELECT efficiency_multiplier FROM skill_sessions WHERE session_id = 's1'"),
            0.0001,
        )
    }

    @Test
    fun `migration 2 to 3 backfills worker_slot from is_worker_session`() {
        val db = openV1Database()
        MIGRATION_1_2.migrate(db) // reach v2 first
        insertSession(db, "worker", isWorker = 1, efficiency = 1.5)
        insertSession(db, "player", isWorker = 0, efficiency = 1.0)

        MIGRATION_2_3.migrate(db)

        assertEquals(1, db.intColumn("SELECT worker_slot FROM skill_sessions WHERE session_id = 'worker'"))
        assertEquals(0, db.intColumn("SELECT worker_slot FROM skill_sessions WHERE session_id = 'player'"))
    }

    @Test
    fun `full chain 1 to 3 preserves existing rows and adds all columns`() {
        val db = openV1Database()
        insertSession(db, "s1")

        MIGRATION_1_2.migrate(db)
        MIGRATION_2_3.migrate(db)

        // Pre-existing row survives and gets the new defaults.
        assertEquals(0, db.intColumn("SELECT is_worker_session FROM skill_sessions WHERE session_id = 's1'"))
        assertEquals(0, db.intColumn("SELECT worker_slot FROM skill_sessions WHERE session_id = 's1'"))
        assertEquals(1, db.intColumn("SELECT COUNT(*) FROM skill_sessions WHERE session_id = 's1'"))
    }

    @Test
    fun `full chain 1 to 7 preserves v6 rows with null start_elapsed_ms`() {
        val db = openV1Database()
        insertSession(db, "s1")

        MIGRATION_1_2.migrate(db)
        MIGRATION_2_3.migrate(db)
        MIGRATION_3_4.migrate(db)
        MIGRATION_4_5.migrate(db)
        MIGRATION_5_6.migrate(db)
        insertSession(db, "v6row")

        MIGRATION_6_7.migrate(db)

        assertEquals(2, db.intColumn("SELECT COUNT(*) FROM skill_sessions"))
        assertEquals(0, db.intColumn("SELECT started_at FROM skill_sessions WHERE session_id = 'v6row'"))
        db.query("SELECT start_elapsed_ms FROM skill_sessions WHERE session_id = 's1'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }
        db.query("SELECT start_elapsed_ms FROM skill_sessions WHERE session_id = 'v6row'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }
    }
}
