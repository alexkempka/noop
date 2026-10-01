package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.ui.LiveSessionRunner
import com.noop.ui.appLaunchIntent

/**
 * The ongoing notification that says a Live Session is running, and the one button that stops it.
 *
 * ## Why this exists
 *
 * The session deliberately outlives the dialog that starts it — the comment on [LiveSessionRunner]
 * calls it wrist-first, and that is the right call. But the only way back to it was the entry card on
 * the Today screen, which means a wearer who starts a session and then leaves that screen has nothing
 * anywhere telling them a session is on. One did: the strap buzzed every 50 seconds for 72 minutes and
 * the alerts screens, where anybody would look first, had nothing to show because this is not an
 * alert. It is a session, and it was invisible.
 *
 * A notification is the one surface Android gives for "something of yours is running right now", so
 * that is what this is: ongoing (it cannot be swiped away while the session runs), silent, and
 * carrying a Stop that works without opening the app.
 *
 * ## What it deliberately is not
 *
 * Not a foreground service. The session's tick already lives on the app-wide scope and dies with the
 * process, exactly as documented; promoting it would change that lifetime and is a far bigger decision
 * than making a running session visible.
 *
 * Silent by design — IMPORTANCE_LOW, no sound, no vibration from the PHONE. The session already speaks
 * through the wrist; a notification that also pinged would double every cue.
 */
object LiveSessionNotifier {
    private const val CHANNEL_ID = "noop_live_session"
    private const val NOTIF_ID = 4204   // 4201 ongoing connection, 4202 illness, 4203 inactivity

    /** The Stop action's intent action. Private to the app — the receiver is not exported. */
    const val ACTION_STOP = "com.noop.action.LIVE_SESSION_STOP"

    /**
     * Post (or refresh) the running notification.
     *
     * [pushCount] and [easeCount] are shown because they are the honest answer to the question a
     * buzzing wrist raises — "how many times has this thing tapped me?" — and because seeing the count
     * climb is what tells a wearer the session, not some alert setting, is the thing doing it.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun showRunning(context: Context, floorBpm: Int, ceilingBpm: Int, pushCount: Int, easeCount: Int) {
        // Defensive for the same reason every other notifier here is: a revoked POST_NOTIFICATIONS or
        // an OEM quirk must never take the session down with it.
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val cues = pushCount + easeCount
            val body = if (cues > 0) {
                context.getString(R.string.live_session_notif_body_cues, floorBpm, ceilingBpm, cues)
            } else {
                context.getString(R.string.live_session_notif_body, floorBpm, ceilingBpm)
            }
            val open = PendingIntent.getActivity(
                context, 7,
                appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getBroadcast(
                context, 8,
                Intent(context, StopReceiver::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(context.getString(R.string.live_session_notif_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.live_session_notif_stop), stop)
                // Ongoing: a session that cannot be swiped out of sight while it is still buzzing.
                .setOngoing(true)
                // The phone stays quiet on every refresh; only the wrist speaks.
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID, n)
        }
    }

    /** Take the notification down. Called when the session ends, by whichever route ended it. */
    fun hide(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.live_session_channel_name),
                    // LOW: visible in the shade, never a sound or a heads-up. The wrist is the alert.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.live_session_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * The Stop button. Ends the one active session and clears the notification.
     *
     * It calls the SAME [LiveSessionRunner.end] the End button in the dialog calls, so a session
     * stopped from the shade banks its totals row exactly like one stopped on screen — no second path
     * through the state machine, nothing for the two to disagree about.
     */
    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STOP) return
            LiveSessionRunner.active.value?.end()
            hide(context)
        }
    }
}
