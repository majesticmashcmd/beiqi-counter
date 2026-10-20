package com.beiqi.counter

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class JapaForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "daily_japa_service_channel"
        const val NOTIFICATION_ID = 108

        fun start(context: Context) {
            val intent = Intent(context, JapaForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JapaForegroundService::class.java)
            context.stopService(intent)
        }

        fun updateProgress(context: Context, counter: CounterProfile) {
            try {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val notification = buildNotification(context, counter)
                notificationManager?.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @SuppressLint("UnspecifiedImmutableFlag")
        fun buildNotification(context: Context, counter: CounterProfile? = null): Notification {
            createNotificationChannel(context)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )

            val title = if (counter != null) {
                "Daily Japa: 📿 ${counter.name}"
            } else {
                "Daily Japa: BEIQI-ST Connected"
            }

            val text = if (counter != null) {
                "Bead ${counter.currentBeads} of ${counter.beadTarget} • ${counter.dailyMala} Malas Today"
            } else {
                "Connected & Ready. Click ring to count."
            }

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }

        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Daily Japa Background Counter",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Displays live bead progress and prevents disconnection when phone is locked"
                    setShowBadge(false)
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val repository = CounterRepository(this)
        val active = repository.getActiveCounter()
        val notification = buildNotification(this, active)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repository = CounterRepository(this)
        val active = repository.getActiveCounter()
        updateProgress(this, active)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

