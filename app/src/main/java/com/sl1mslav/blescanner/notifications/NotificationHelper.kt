package com.sl1mslav.blescanner.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.sl1mslav.blescanner.R

private const val BLE_SERVICE_NOTIFICATION_CHANNEL_ID = "le_service_notification_channel"

@RequiresApi(Build.VERSION_CODES.O)
private fun createNotificationChannel(context: Context) {
    val notificationManager = context.getSystemService(
        Service.NOTIFICATION_SERVICE
    ) as NotificationManager


    val channel = NotificationChannel(
        BLE_SERVICE_NOTIFICATION_CHANNEL_ID,
        "BLE Service Notifications",
        NotificationManager.IMPORTANCE_DEFAULT
    )
    notificationManager.createNotificationChannel(channel)
}

fun buildBleServiceNotification(
    context: Context,
    title: String,
    text: String
): Notification {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        createNotificationChannel(context)
    }
    @Suppress("DEPRECATION")
    return NotificationCompat.Builder(context)
        .setChannelId(BLE_SERVICE_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        /*.setContentIntent(Intent(context, BlePermissionsActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(
                context,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
        })*/ // todo если захочу показывать пермишены
        .build()
}