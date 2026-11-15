package com.shashowtohaque.acs.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shashowtohaque.acs.MainActivity
import com.shashowtohaque.acs.R
import com.shashowtohaque.acs.data.local.AppDatabase
import com.shashowtohaque.acs.data.local.AudioRoute
import com.shashowtohaque.acs.data.local.CallRepeatMode
import com.shashowtohaque.acs.data.local.CallScheduleEntity
import com.shashowtohaque.acs.data.local.ConfirmationMode
import com.shashowtohaque.acs.data.local.SimSlot
import com.shashowtohaque.acs.data.repository.ScheduleRepository
import com.shashowtohaque.acs.util.CallDispatcher
import com.shashowtohaque.acs.util.PermissionHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallSchedulerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repository: ScheduleRepository

    companion object {
        const val CHANNEL_ID = "ACS_FOREGROUND_SERVICE_CHANNEL_V2"
        private const val LEGACY_CHANNEL_ID = "ACS_FOREGROUND_SERVICE_CHANNEL"
        const val NOTIFICATION_ID = 1001
        const val ACTION_REFRESH_NOTIFICATION = "com.shashowtohaque.acs.ACTION_REFRESH_NOTIFICATION"
        const val ACTION_POST_CALL_EFFECTS = "com.shashowtohaque.acs.ACTION_POST_CALL_EFFECTS"
        const val EXTRA_USE_SPEAKER = "extra_use_speaker"
        const val EXTRA_AUTO_HANGUP_SECONDS = "extra_auto_hangup_seconds"

        fun startService(context: Context) {
            val intent = Intent(context, CallSchedulerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Prevent ForegroundServiceStartNotAllowedException if Battery Optimization was restricted
                CallDispatcher.scheduleWatchdogAlarm(context, 3000L)
            }
        }

        fun refreshNotification(context: Context) {
            val intent = Intent(context, CallSchedulerService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Safe fallback if background start is restricted
            }
        }

        fun schedulePostCallEffects(
            context: Context,
            useSpeaker: Boolean,
            autoHangupSeconds: Int
        ) {
            if (!useSpeaker && autoHangupSeconds <= 0) return
            val intent = Intent(context, CallSchedulerService::class.java).apply {
                action = ACTION_POST_CALL_EFFECTS
                putExtra(EXTRA_USE_SPEAKER, useSpeaker)
                putExtra(EXTRA_AUTO_HANGUP_SECONDS, autoHangupSeconds)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Safe fallback
            }
        }
    }

    private data class NotificationSnapshot(
        val pending: Int,
        val completed: Int,
        val failed: Int,
        val nextSchedule: CallScheduleEntity?
    )

    override fun onCreate() {
        super.onCreate()
        val dao = AppDatabase.getDatabase(this).callScheduleDao()
        repository = ScheduleRepository(dao)

        createNotificationChannel()
        startForegroundWithCounters(pending = 0, completed = 0, failed = 0, nextSchedule = null)
        CallDispatcher.scheduleWatchdogAlarm(applicationContext)

        observeScheduleCounters()
    }

    private fun observeScheduleCounters() {
        serviceScope.launch {
            combine(
                repository.pendingCount,
                repository.completedCount,
                repository.failedCount,
                repository.nextPendingSchedule
            ) { pending, completed, failed, nextSchedule ->
                NotificationSnapshot(pending, completed, failed, nextSchedule)
            }.collect { snapshot ->
                updateNotification(
                    pending = snapshot.pending,
                    completed = snapshot.completed,
                    failed = snapshot.failed,
                    nextSchedule = snapshot.nextSchedule
                )
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Call Scheduler Engine",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps ACS active in background and displays vital schedule counters & next schedule insights"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithCounters(
        pending: Int,
        completed: Int,
        failed: Int,
        nextSchedule: CallScheduleEntity?
    ) {
        val notification = buildOngoingNotification(pending, completed, failed, nextSchedule)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun updateNotification(
        pending: Int,
        completed: Int,
        failed: Int,
        nextSchedule: CallScheduleEntity?
    ) {
        val notification = buildOngoingNotification(pending, completed, failed, nextSchedule)
        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission revoked at runtime
        }
    }

    private fun buildOngoingNotification(
        pending: Int,
        completed: Int,
        failed: Int,
        nextSchedule: CallScheduleEntity?
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val survivability = PermissionHelper.getSurvivabilityReport(this)
        val missingGuards = buildList {
            if (!survivability.autostartGranted) add("Autostart")
            if (!survivability.batteryUnrestricted) add("Battery Optimization")
            if (!survivability.backgroundActivityAllowed) add("Background Activity")
            if (!survivability.lockScreenAllowed) add("Lock Screen")
        }

        val statusTag = if (missingGuards.isNotEmpty()) {
            "Action Needed"
        } else if (pending > 0) {
            "Active"
        } else {
            "Standby"
        }
        val title = "Auto Call Scheduler • $statusTag"
        val compactCountsText = if (missingGuards.isNotEmpty()) {
            "Enable: ${missingGuards.joinToString(", ")}"
        } else {
            "Pending: $pending • Completed: $completed • Failed: $failed"
        }

        val nextInsights = if (nextSchedule != null) {
            val timeFormatter = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
            val nextTime = timeFormatter.format(Date(nextSchedule.targetTimeMillis))
            val simLabel = if (nextSchedule.simSlot == SimSlot.SIM_1) "SIM 1" else "SIM 2"
            val modeLabel = if (nextSchedule.confirmationMode == ConfirmationMode.REMINDER) "Reminder" else "Direct Dial"
            val audioLabel = if (nextSchedule.audioRoute == AudioRoute.SPEAKER) "Speaker" else "Handset"
            val repeatLabel = when (nextSchedule.callRepeatMode) {
                CallRepeatMode.CUSTOM -> "${nextSchedule.callRepeatCount}x (${nextSchedule.repeatIntervalValue} ${nextSchedule.repeatIntervalUnit.name.lowercase()})"
                CallRepeatMode.UNLIMITED -> "Unlimited (${nextSchedule.repeatIntervalValue} ${nextSchedule.repeatIntervalUnit.name.lowercase()})"
                CallRepeatMode.NO_REPEAT -> "One-time"
            }
            val hangupLabel = if (nextSchedule.autoHangupSeconds > 0) " • End ${nextSchedule.autoHangupSeconds}s" else ""

            buildString {
                appendLine("Next: ${nextSchedule.title} ($nextTime)")
                appendLine("${nextSchedule.contactName} • ${nextSchedule.phoneNumber}")
                append("$simLabel • $modeLabel • $audioLabel$hangupLabel • $repeatLabel")
            }
        } else {
            "No upcoming schedules queued."
        }

        val expandedFullBody = if (missingGuards.isNotEmpty()) {
            "Pending: $pending • Completed: $completed • Failed: $failed\nWarning — disabled: ${missingGuards.joinToString(", ")}\n\n$nextInsights"
        } else {
            "$compactCountsText\n\n$nextInsights"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(compactCountsText)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setLargeIcon(getAppLogoBitmap())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(expandedFullBody)
            )

        return builder.build()
    }

    private fun getAppLogoBitmap(): Bitmap? {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_app_logo) ?: return null
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallDispatcher.scheduleWatchdogAlarm(applicationContext)
        when (intent?.action) {
            ACTION_REFRESH_NOTIFICATION -> {
                serviceScope.launch {
                    createNotificationChannel()
                    val pending = repository.pendingCount.first()
                    val completed = repository.completedCount.first()
                    val failed = repository.failedCount.first()
                    val next = repository.nextPendingSchedule.first()
                    startForegroundWithCounters(pending, completed, failed, next)
                    updateNotification(pending, completed, failed, next)
                }
            }
            ACTION_POST_CALL_EFFECTS -> {
                val useSpeaker = intent.getBooleanExtra(EXTRA_USE_SPEAKER, false)
                val autoHangupSeconds = intent.getIntExtra(EXTRA_AUTO_HANGUP_SECONDS, 0)
                if (useSpeaker) {
                    serviceScope.launch {
                        delay(2500L)
                        try {
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            audioManager?.mode = AudioManager.MODE_IN_CALL
                            @Suppress("DEPRECATION")
                            audioManager?.isSpeakerphoneOn = true
                        } catch (_: Exception) {
                        }
                    }
                }
                if (autoHangupSeconds > 0) {
                    serviceScope.launch {
                        delay(autoHangupSeconds * 1000L)
                        CallDispatcher.endCallSafely(this@CallSchedulerService)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Re-ensure BroadcastReceivers remain enabled & arm both Foreground Service restart + Watchdog wakeup
        PermissionHelper.ensureReceiversEnabled(applicationContext)

        val restartIntent = Intent(applicationContext, CallSchedulerService::class.java)
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                applicationContext,
                1002,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                applicationContext,
                1002,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        try {
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000L,
                pendingIntent
            )
        } catch (_: Exception) {
        }
        // Broadcast-based watchdog fallback works reliably with Autostart (OP_AUTO_START) + Doze whitelist
        CallDispatcher.scheduleWatchdogAlarm(applicationContext, 2000L)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}