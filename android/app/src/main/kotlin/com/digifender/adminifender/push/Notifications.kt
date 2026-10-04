package com.digifender.adminifender.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.digifender.adminifender.MainActivity
import com.digifender.adminifender.R
import com.digifender.adminifender.core.model.NotificationChannelKind
import com.digifender.adminifender.core.model.PushEvent
import com.digifender.adminifender.core.model.channelFor

/** Intent extra carrying only an identifier; the app resolves it against the cache after authentication. */
const val EXTRA_DESTINATION = "com.digifender.adminifender.destination"
const val BRIEFING_DESTINATION = "briefing"

/** What a validated push turns into on the phone. Pure so the routing rules are tested on the JVM. */
data class NotificationSpec(val channelId: String, val notificationId: Int, val destination: String, val highPriority: Boolean)

fun notificationSpec(event: PushEvent): NotificationSpec {
    val channel = channelFor(event.kind)
    val destination = when (val d = event.destination) {
        is PushEvent.Destination.Concern -> d.id
        PushEvent.Destination.Briefing -> BRIEFING_DESTINATION
    }
    // One notification per logical event: a redelivered push replaces itself instead of stacking.
    return NotificationSpec(channel.id, event.eventId.hashCode(), destination, channel == NotificationChannelKind.CRITICAL)
}

/** The three channels (tdd.md#android); the owner tunes or silences each one in system settings. */
fun createChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannels(
        listOf(
            NotificationChannel(NotificationChannelKind.CRITICAL.id, context.getString(R.string.channel_critical), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_critical_body) },
            NotificationChannel(NotificationChannelKind.REVIEW_REMINDERS.id, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_reminders_body) },
            NotificationChannel(NotificationChannelKind.PROCESSING.id, context.getString(R.string.channel_processing), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_processing_body) },
        ),
    )
}

/** Posts generic text only: the payload carries no content and the notification adds none (FR-031). */
fun show(context: Context, spec: NotificationSpec) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    val open = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_DESTINATION, spec.destination)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val pending = PendingIntent.getActivity(context, spec.notificationId, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = NotificationCompat.Builder(context, spec.channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.push_generic))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPriority(if (spec.highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()
    try {
        manager.notify(spec.notificationId, notification)
    } catch (_: SecurityException) {
        // Permission withdrawn between the check and the post; the inbox still shows the item.
    }
}
