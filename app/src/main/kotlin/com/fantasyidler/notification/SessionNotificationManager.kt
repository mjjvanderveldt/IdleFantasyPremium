package com.fantasyidler.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationChannelCompat
import com.fantasyidler.MainActivity
import com.fantasyidler.R
import com.fantasyidler.util.withAppLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_ID_SESSIONS = "fantasy_idler_sessions"
        const val CHANNEL_ID_FARMING  = "fantasy_idler_farming"
        const val CHANNEL_ID_BUFFS    = "fantasy_idler_buffs"

        const val EXTRA_NAVIGATE_TO   = "navigate_to"
        const val EXTRA_SAVE_SLOT     = "save_slot"
        const val NAVIGATE_FARMING    = "farming"
        const val NAVIGATE_SAVE_SLOTS = "save_slots"

        private const val NOTIF_ID_SESSION_COMPLETE = 1001
        private const val NOTIF_ID_FARMING_READY    = 2001
        private const val NOTIF_ID_XP_BOOST_EXPIRED = 3001
        private const val NOTIF_ID_BLESSING_EXPIRED  = 3002
    }

    @Volatile
    private var appInForeground = false

    /** Call from the activity's onStart/onStop to track foreground state. */
    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        if (inForeground) cancelAll()
    }

    fun cancelAll() {
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun localizedContext(): Context = context.withAppLocale()

    /** Call once on app startup to register notification channels (idempotent). */
    fun createChannels() {
        val mgr = NotificationManagerCompat.from(context)
        mgr.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID_SESSIONS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notif_channel_sessions_name))
                .setDescription(context.getString(R.string.notif_channel_sessions_desc))
                .build()
        )
        mgr.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID_FARMING, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notif_channel_farming_name))
                .setDescription(context.getString(R.string.notif_channel_farming_desc))
                .build()
        )
        mgr.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID_BUFFS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notif_channel_buffs_name))
                .setDescription(context.getString(R.string.notif_channel_buffs_desc))
                .build()
        )
    }

    private fun launchIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Per-slot request codes: FLAG_UPDATE_CURRENT would otherwise rewrite the slot extra on a
    // PendingIntent an earlier character's notification still holds.
    private fun farmingLaunchIntent(slot: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_NAVIGATE_TO, NAVIGATE_FARMING)
            putExtra(EXTRA_SAVE_SLOT, slot)
        }
        return PendingIntent.getActivity(
            context, 10 + slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Show "Your [skillName] session has finished" notification. */
    fun showSessionComplete(skillDisplayName: String) {
        val lc = localizedContext()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lc.getString(R.string.notif_session_complete_title))
            .setContentText(lc.getString(R.string.notif_session_complete_body, skillDisplayName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(launchIntent())
            .setAutoCancel(true)
            .build()

        postIfPermitted(NOTIF_ID_SESSION_COMPLETE, notification)
    }

    /** Show "Your [cropName] is ready to harvest" notification. */
    fun showFarmingReady(cropDisplayName: String, saveSlot: Int = 0) {
        val lc = localizedContext()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_FARMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lc.getString(R.string.notif_farming_ready_title))
            .setContentText(lc.getString(R.string.notif_farming_ready_body, cropDisplayName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(farmingLaunchIntent(saveSlot))
            .setAutoCancel(true)
            .build()

        postIfPermitted(NOTIF_ID_FARMING_READY, notification)
    }

    /** Show "Your 2x XP boost has run out" notification. */
    fun showXpBoostExpired() {
        val lc = localizedContext()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_BUFFS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lc.getString(R.string.notif_xp_boost_expired_title))
            .setContentText(lc.getString(R.string.notif_xp_boost_expired_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(launchIntent())
            .setAutoCancel(true)
            .build()
        postIfPermitted(NOTIF_ID_XP_BOOST_EXPIRED, notification)
    }

    /** Show "Your church blessing has faded" notification. */
    fun showBlessingExpired() {
        val lc = localizedContext()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_BUFFS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lc.getString(R.string.notif_blessing_expired_title))
            .setContentText(lc.getString(R.string.notif_blessing_expired_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(launchIntent())
            .setAutoCancel(true)
            .build()
        postIfPermitted(NOTIF_ID_BLESSING_EXPIRED, notification)
    }

    private fun postIfPermitted(id: Int, notification: Notification) {
        if (appInForeground) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
