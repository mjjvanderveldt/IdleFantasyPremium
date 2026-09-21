package com.fantasyidler.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.db.MIGRATION_1_2
import com.fantasyidler.data.db.MIGRATION_2_3
import com.fantasyidler.data.db.MIGRATION_3_4
import com.fantasyidler.data.db.MIGRATION_4_5
import com.fantasyidler.data.db.MIGRATION_5_6
import com.fantasyidler.data.db.MIGRATION_6_7
import com.fantasyidler.data.db.MIGRATION_7_8
import com.fantasyidler.data.db.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "fantasy_idler.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .addCallback(object : RoomDatabase.Callback() {
                // Old versions (pre-1.8.6) could write a session whose frames JSON exceeds
                // the 2 MB CursorWindow limit; every read of the table then throws
                // SQLiteBlobTooBigException and the app crash-loops on open (issues #357,
                // #497, #1746). Legitimate sessions are capped at 60 frames (< 100 KB), so
                // anything over 1 MB is unreadable garbage. DELETE never materialises the
                // row into a CursorWindow, so this can remove rows no query could read.
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("DELETE FROM skill_sessions WHERE length(data) > 1000000")
                }
            })
            .build()

    @Provides fun providePlayerDao(db: AppDatabase): PlayerDao = db.playerDao()
    @Provides fun provideSkillSessionDao(db: AppDatabase): SkillSessionDao = db.skillSessionDao()
    @Provides fun provideQuestProgressDao(db: AppDatabase): QuestProgressDao = db.questProgressDao()
    @Provides fun provideFarmingPatchDao(db: AppDatabase): FarmingPatchDao = db.farmingPatchDao()
    @Provides fun provideGlobalStateDao(db: AppDatabase): GlobalStateDao = db.globalStateDao()
    @Provides fun provideArenaRecordDao(db: AppDatabase): ArenaRecordDao = db.arenaRecordDao()
    @Provides fun provideCustomThemeDao(db: AppDatabase): CustomThemeDao = db.customThemeDao()
}
