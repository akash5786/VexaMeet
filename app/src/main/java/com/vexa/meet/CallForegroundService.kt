package com.vexa.meet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class CallForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var callerName = "Active call"
    private var startedAtMillis = 0L
    private var muted = false

    private val durationRunnable = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: callerName
                muted = intent.getBooleanExtra(EXTRA_MUTED, muted)
                if (startedAtMillis == 0L) {
                    startedAtMillis = System.currentTimeMillis()
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                handler.removeCallbacks(durationRunnable)
                handler.post(durationRunnable)
            }
            ACTION_SET_MUTED -> {
                muted = intent.getBooleanExtra(EXTRA_MUTED, muted)
                updateNotification()
            }
            ACTION_TOGGLE_MUTE -> {
                muted = !muted
                val listener = ActiveCallActions.listener
                if (listener != null) {
                    listener.onNotificationMuteToggled(muted)
                } else {
                    ActiveCallSession.setMuted(muted)
                }
                updateNotification()
            }
            ACTION_END_CALL -> {
                val listener = ActiveCallActions.listener
                if (listener != null) {
                    listener.onNotificationEndCall()
                } else {
                    ActiveCallSession.endActiveCall()
                }
                stopSelf()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(durationRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mutePendingIntent = servicePendingIntent(ACTION_TOGGLE_MUTE, 1)
        val endPendingIntent = servicePendingIntent(ACTION_END_CALL, 2)
        val duration = formatDuration()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(callerName)
            .setContentText("Ongoing call • $duration")
            .setSubText(duration)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPendingIntent)
            .addAction(
                if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                if (muted) "Unmute" else "Mute",
                mutePendingIntent
            )
            .addAction(R.drawable.ic_call_end, "End call", endPendingIntent)
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, CallForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatDuration(): String {
        val elapsedSeconds = ((System.currentTimeMillis() - startedAtMillis) / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ongoing video and audio calls"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "active_call"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.vexa.meet.action.START_CALL_SERVICE"
        private const val ACTION_STOP = "com.vexa.meet.action.STOP_CALL_SERVICE"
        private const val ACTION_SET_MUTED = "com.vexa.meet.action.SET_MUTED"
        private const val ACTION_TOGGLE_MUTE = "com.vexa.meet.action.TOGGLE_MUTE"
        private const val ACTION_END_CALL = "com.vexa.meet.action.END_CALL"
        private const val EXTRA_CALLER_NAME = "extra_caller_name"
        private const val EXTRA_MUTED = "extra_muted"

        fun start(context: Context, callerName: String, muted: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_MUTED, muted)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }

        fun setMuted(context: Context, muted: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_SET_MUTED
                putExtra(EXTRA_MUTED, muted)
            }
            context.startService(intent)
        }
    }
}
