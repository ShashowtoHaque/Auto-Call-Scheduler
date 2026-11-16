package com.shashowtohaque.acs.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shashowtohaque.acs.MainActivity
import com.shashowtohaque.acs.R
import com.shashowtohaque.acs.data.local.AudioRoute
import com.shashowtohaque.acs.data.local.CallRepeatMode
import com.shashowtohaque.acs.data.local.CallScheduleEntity
import com.shashowtohaque.acs.data.local.ConfirmationMode
import com.shashowtohaque.acs.data.local.RemindBeforeUnit
import com.shashowtohaque.acs.data.local.ReminderAlertType
import com.shashowtohaque.acs.data.local.RepeatIntervalUnit
import com.shashowtohaque.acs.data.local.ScheduleStatus
import com.shashowtohaque.acs.data.local.SimSlot
import com.shashowtohaque.acs.receiver.AlarmReceiver
import com.shashowtohaque.acs.service.AirplaneModeAccessibilityService
import com.shashowtohaque.acs.service.CallSchedulerService
import java.util.Calendar
import kotlin.math.min

data class IntervalUnitBounds(
    val min: Int,
    val max: Int,
    val allowed: Boolean
)

data class ScheduleAdvanceResult(
    val status: ScheduleStatus,
    val targetTimeMillis: Long,
    val anchorTimeMillis: Long,
    val currentRepeatCount: Int
)

data class CallDispatchResult(
    val dispatched: Boolean,
    val failureReason: String? = null
)

object CallDispatcher {

    const val ACTION_EXECUTE_CALL = "com.shashowtohaque.acs.ACTION_EXECUTE_CALL"
    const val ACTION_REMIND_CALL = "com.shashowtohaque.acs.ACTION_REMIND_CALL"
    const val ACTION_WATCHDOG_CHECK = "com.shashowtohaque.acs.ACTION_WATCHDOG_CHECK"
    const val EXTRA_DISPATCH_CALL_ID = "extra_dispatch_call_id"
    const val EXTRA_AFTER_AIRPLANE_RECOVERY = "extra_after_airplane_recovery"
    private const val REMINDER_SOUND_CHANNEL_ID = "ACS_CALL_REMINDER_SOUND_CHANNEL"
    private const val REMINDER_VIBRATE_CHANNEL_ID = "ACS_CALL_REMINDER_VIBRATE_CHANNEL"
    private const val CALL_TRIGGER_CHANNEL_ID = "ACS_CALL_TRIGGER_CHANNEL"
    private const val REMINDER_REQUEST_OFFSET = 1_000_000
    private const val RECOVERY_REQUEST_OFFSET = 1_500_000
    private const val RECOVERY_NOTIF_OFFSET = 1_600_000
    private const val WATCHDOG_REQUEST_CODE = 19_001
    private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

    val WEEKDAYS_SAT_TO_FRI = listOf(
        6 to "Sat",
        7 to "Sun",
        1 to "Mon",
        2 to "Tue",
        3 to "Wed",
        4 to "Thu",
        5 to "Fri"
    )

    private fun toIsoWeekday(cal: Calendar): Int {
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }
    }

    fun isWeekdayCompatibleUnit(unit: RepeatIntervalUnit): Boolean =
        unit == RepeatIntervalUnit.MINUTES || unit == RepeatIntervalUnit.HOURS

    fun getUnitRange(unit: RepeatIntervalUnit): Pair<Int, Int> = when (unit) {
        RepeatIntervalUnit.MINUTES -> 1 to 59
        RepeatIntervalUnit.HOURS -> 1 to 23
        RepeatIntervalUnit.DAYS -> 1 to 30
        RepeatIntervalUnit.WEEKS -> 1 to 4
        RepeatIntervalUnit.MONTHS -> 1 to 11
        RepeatIntervalUnit.YEARS -> 1 to 5
    }

    fun getEffectiveIntervalBounds(
        unit: RepeatIntervalUnit,
        repeatMode: CallRepeatMode,
        repeatCount: Int,
        hasWeekdays: Boolean
    ): IntervalUnitBounds {
        val (baseMin, baseMax) = getUnitRange(unit)
        if (!hasWeekdays) {
            return IntervalUnitBounds(baseMin, baseMax, true)
        }
        if (!isWeekdayCompatibleUnit(unit)) {
            return IntervalUnitBounds(baseMin, baseMax, false)
        }
        if (repeatMode != CallRepeatMode.CUSTOM || repeatCount <= 0) {
            return IntervalUnitBounds(baseMin, baseMax, true)
        }
        return when (unit) {
            RepeatIntervalUnit.MINUTES -> {
                val capped = min(baseMax, 1439 / repeatCount)
                IntervalUnitBounds(1, capped.coerceAtLeast(1), capped >= 1)
            }
            RepeatIntervalUnit.HOURS -> {
                val capped = min(baseMax, 23 / repeatCount)
                IntervalUnitBounds(1, capped.coerceAtLeast(1), capped >= 1)
            }
            else -> IntervalUnitBounds(baseMin, baseMax, false)
        }
    }

    fun addIntervalFromMillis(anchorMillis: Long, steps: Int, unit: RepeatIntervalUnit): Long {
        if (steps <= 0) return anchorMillis
        val anchorCal = Calendar.getInstance().apply { timeInMillis = anchorMillis }
        val result = Calendar.getInstance().apply { timeInMillis = anchorMillis }
        when (unit) {
            RepeatIntervalUnit.MINUTES -> result.add(Calendar.MINUTE, steps)
            RepeatIntervalUnit.HOURS -> result.add(Calendar.HOUR_OF_DAY, steps)
            RepeatIntervalUnit.DAYS -> result.add(Calendar.DAY_OF_YEAR, steps)
            RepeatIntervalUnit.WEEKS -> result.add(Calendar.DAY_OF_YEAR, steps * 7)
            RepeatIntervalUnit.MONTHS -> {
                val targetDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                result.set(Calendar.DAY_OF_MONTH, 1)
                result.add(Calendar.MONTH, steps)
                val maxDay = result.getActualMaximum(Calendar.DAY_OF_MONTH)
                result.set(Calendar.DAY_OF_MONTH, min(targetDay, maxDay))
            }
            RepeatIntervalUnit.YEARS -> {
                val targetDay = anchorCal.get(Calendar.DAY_OF_MONTH)
                val targetMonth = anchorCal.get(Calendar.MONTH)
                result.set(Calendar.DAY_OF_MONTH, 1)
                result.add(Calendar.YEAR, steps)
                result.set(Calendar.MONTH, targetMonth)
                val maxDay = result.getActualMaximum(Calendar.DAY_OF_MONTH)
                result.set(Calendar.DAY_OF_MONTH, min(targetDay, maxDay))
            }
        }
        return result.timeInMillis
    }

    fun alignToFirstMatchingWeekday(startMillis: Long, weekdays: List<Int>): Long {
        if (weekdays.isEmpty()) return startMillis
        val candidate = Calendar.getInstance().apply { timeInMillis = startMillis }
        for (i in 0..6) {
            if (weekdays.contains(toIsoWeekday(candidate))) {
                return candidate.timeInMillis
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return startMillis
    }

    private fun findNextMatchingWeekday(anchorMillis: Long, weekdays: List<Int>): Long {
        val anchor = Calendar.getInstance().apply { timeInMillis = anchorMillis }
        val candidate = Calendar.getInstance().apply {
            timeInMillis = anchorMillis
            set(Calendar.HOUR_OF_DAY, anchor.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, anchor.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (i in 1..7) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
            if (weekdays.contains(toIsoWeekday(candidate))) {
                return candidate.timeInMillis
            }
        }
        return anchorMillis + 86_400_000L
    }

    fun advanceScheduleAfterCall(schedule: CallScheduleEntity): ScheduleAdvanceResult {
        val safeAnchor = if (schedule.anchorTimeMillis > 0L) schedule.anchorTimeMillis else schedule.targetTimeMillis
        val weekdays = schedule.repeatDaysOfWeek
        val intervalVal = schedule.repeatIntervalValue.coerceAtLeast(1)

        if (weekdays.isNotEmpty()) {
            if (schedule.callRepeatMode == CallRepeatMode.CUSTOM &&
                schedule.currentRepeatCount < schedule.callRepeatCount
            ) {
                val nextStep = schedule.currentRepeatCount + 1
                val nextTime = addIntervalFromMillis(safeAnchor, intervalVal * nextStep, schedule.repeatIntervalUnit)
                return ScheduleAdvanceResult(
                    status = ScheduleStatus.PENDING,
                    targetTimeMillis = nextTime,
                    anchorTimeMillis = safeAnchor,
                    currentRepeatCount = nextStep
                )
            }
            val nextWeekday = findNextMatchingWeekday(safeAnchor, weekdays)
            return ScheduleAdvanceResult(
                status = ScheduleStatus.PENDING,
                targetTimeMillis = nextWeekday,
                anchorTimeMillis = nextWeekday,
                currentRepeatCount = 0
            )
        }

        return when (schedule.callRepeatMode) {
            CallRepeatMode.NO_REPEAT -> ScheduleAdvanceResult(
                status = ScheduleStatus.COMPLETED,
                targetTimeMillis = schedule.targetTimeMillis,
                anchorTimeMillis = safeAnchor,
                currentRepeatCount = schedule.currentRepeatCount
            )
            CallRepeatMode.CUSTOM -> {
                if (schedule.currentRepeatCount < schedule.callRepeatCount) {
                    val nextStep = schedule.currentRepeatCount + 1
                    val nextTime = addIntervalFromMillis(safeAnchor, intervalVal * nextStep, schedule.repeatIntervalUnit)
                    ScheduleAdvanceResult(
                        status = ScheduleStatus.PENDING,
                        targetTimeMillis = nextTime,
                        anchorTimeMillis = safeAnchor,
                        currentRepeatCount = nextStep
                    )
                } else {
                    ScheduleAdvanceResult(
                        status = ScheduleStatus.COMPLETED,
                        targetTimeMillis = schedule.targetTimeMillis,
                        anchorTimeMillis = safeAnchor,
                        currentRepeatCount = schedule.currentRepeatCount
                    )
                }
            }
            CallRepeatMode.UNLIMITED -> {
                val nextStep = schedule.currentRepeatCount + 1
                val nextTime = addIntervalFromMillis(safeAnchor, intervalVal * nextStep, schedule.repeatIntervalUnit)
                ScheduleAdvanceResult(
                    status = ScheduleStatus.PENDING,
                    targetTimeMillis = nextTime,
                    anchorTimeMillis = safeAnchor,
                    currentRepeatCount = nextStep
                )
            }
        }
    }

    fun formatIntervalText(value: Int, unit: RepeatIntervalUnit): String {
        val safeVal = value.coerceAtLeast(1)
        val unitWord = when (unit) {
            RepeatIntervalUnit.MINUTES -> if (safeVal == 1) "Minute" else "Minutes"
            RepeatIntervalUnit.HOURS -> if (safeVal == 1) "Hour" else "Hours"
            RepeatIntervalUnit.DAYS -> if (safeVal == 1) "Day" else "Days"
            RepeatIntervalUnit.WEEKS -> if (safeVal == 1) "Week" else "Weeks"
            RepeatIntervalUnit.MONTHS -> if (safeVal == 1) "Month" else "Months"
            RepeatIntervalUnit.YEARS -> if (safeVal == 1) "Year" else "Years"
        }
        return "Every $safeVal $unitWord"
    }

    fun formatWeekdaysSatToFri(weekdays: List<Int>): String =
        WEEKDAYS_SAT_TO_FRI
            .filter { weekdays.contains(it.first) }
            .joinToString(", ") { it.second }

    fun formatScheduleRepeatSummary(schedule: CallScheduleEntity): String {
        val parts = mutableListOf<String>()
        if (schedule.callRepeatMode == CallRepeatMode.CUSTOM && schedule.callRepeatCount > 0) {
            parts.add("${schedule.callRepeatCount} ${if (schedule.callRepeatCount == 1) "Repeat" else "Repeats"}")
            parts.add(formatIntervalText(schedule.repeatIntervalValue, schedule.repeatIntervalUnit))
        } else if (schedule.callRepeatMode == CallRepeatMode.UNLIMITED) {
            parts.add("Unlimited")
            parts.add(formatIntervalText(schedule.repeatIntervalValue, schedule.repeatIntervalUnit))
        }
        if (schedule.repeatDaysOfWeek.isNotEmpty()) {
            parts.add(formatWeekdaysSatToFri(schedule.repeatDaysOfWeek))
        }
        return parts.joinToString(" • ")
    }

    private fun computeRemindBeforeMillis(value: Int, unit: RemindBeforeUnit): Long {
        if (value <= 0) return 0L
        return when (unit) {
            RemindBeforeUnit.SECONDS -> value * 1_000L
            RemindBeforeUnit.MINUTES -> value * 60_000L
            RemindBeforeUnit.HOURS -> value * 3_600_000L
        }
    }

    /**
     * Schedules highest-priority AlarmClock alarm for call execution (wakes locked devices &
     * bypasses Doze / Xiaomi background restrictions) plus optional Reminder alarm
     */
    fun scheduleAlarm(context: Context, schedule: CallScheduleEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_CALL
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showAppIntent = PendingIntent.getActivity(
            context,
            schedule.id.toInt() + 500_000,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canUseExactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canUseExactAlarms) {
            try {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(schedule.targetTimeMillis, showAppIntent),
                    pendingIntent
                )
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    schedule.targetTimeMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                schedule.targetTimeMillis,
                pendingIntent
            )
        }

        // Schedule pre-call Reminder alarm using system default alarm sound
        val reminderIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_REMIND_CALL
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
        }
        val reminderPendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.toInt() + REMINDER_REQUEST_OFFSET,
            reminderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remindOffsetMs = computeRemindBeforeMillis(schedule.remindBeforeValue, schedule.remindBeforeUnit)
        val remindAtMillis = schedule.targetTimeMillis - remindOffsetMs

        if (schedule.confirmationMode == ConfirmationMode.REMINDER &&
            remindOffsetMs > 0L &&
            remindAtMillis > System.currentTimeMillis()
        ) {
            if (canUseExactAlarms) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    remindAtMillis,
                    reminderPendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    remindAtMillis,
                    reminderPendingIntent
                )
            }
        } else {
            alarmManager.cancel(reminderPendingIntent)
        }
    }

    fun cancelAlarm(context: Context, scheduleId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val callIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_CALL
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
        }
        val callPendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.toInt(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(callPendingIntent)

        val reminderIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_REMIND_CALL
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
        }
        val reminderPendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.toInt() + REMINDER_REQUEST_OFFSET,
            reminderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(reminderPendingIntent)

        val recoveryIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_CALL
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_AFTER_AIRPLANE_RECOVERY, true)
        }
        val recoveryPendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.toInt() + RECOVERY_REQUEST_OFFSET,
            recoveryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(recoveryPendingIntent)
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(scheduleId.toInt() + RECOVERY_NOTIF_OFFSET)
        } catch (_: Exception) {
        }
    }

    /**
     * Schedules an exact wakeup 15 seconds after turning Airplane Mode OFF so cellular radios
     * stabilize cleanly without holding BroadcastReceiver goAsync().
     */
    fun schedulePostAirplaneRecoveryAlarm(
        context: Context,
        schedule: CallScheduleEntity,
        delayMs: Long = 15_000L
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        val recoveryIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_CALL
            putExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, schedule.id)
            putExtra(EXTRA_AFTER_AIRPLANE_RECOVERY, true)
        }
        val recoveryPending = PendingIntent.getBroadcast(
            context,
            schedule.id.toInt() + RECOVERY_REQUEST_OFFSET,
            recoveryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showAppIntent = PendingIntent.getActivity(
            context,
            schedule.id.toInt() + 500_000,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canUseExact) {
            try {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showAppIntent),
                    recoveryPending
                )
            } catch (_: SecurityException) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    recoveryPending
                )
            }
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                recoveryPending
            )
        }

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CALL_TRIGGER_CHANNEL_ID,
                    "Scheduled Call Execution",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
            val delaySec = (delayMs / 1000L).coerceAtLeast(1L)
            val notification = NotificationCompat.Builder(context, CALL_TRIGGER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle("Airplane Mode Turned OFF")
                .setContentText("Waiting ${delaySec}s for cellular signal before dialing ${schedule.contactName}")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(showAppIntent)
                .build()
            nm.notify(schedule.id.toInt() + RECOVERY_NOTIF_OFFSET, notification)
        } catch (_: Exception) {
        }
    }

    /**
     * Triggers the pre-call reminder alert using either the system default alarm sound
     * or an explicit hardware vibration pattern depending on schedule.reminderAlertType
     */
    fun triggerReminderAlarm(context: Context, schedule: CallScheduleEntity) {
        val useVibration = schedule.reminderAlertType == ReminderAlertType.VIBRATION
        val vibratePattern = longArrayOf(0L, 600L, 300L, 600L, 300L, 600L)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val channelId = if (useVibration) REMINDER_VIBRATE_CHANNEL_ID else REMINDER_SOUND_CHANNEL_ID
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = if (useVibration) {
                NotificationChannel(
                    REMINDER_VIBRATE_CHANNEL_ID,
                    "Scheduled Call Reminders (Vibration)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = vibratePattern
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            } else {
                NotificationChannel(
                    REMINDER_SOUND_CHANNEL_ID,
                    "Scheduled Call Reminders (Sound)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(alarmUri, audioAttrs)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        val unitLabel = when (schedule.remindBeforeUnit) {
            RemindBeforeUnit.SECONDS -> if (schedule.remindBeforeValue == 1) "second" else "seconds"
            RemindBeforeUnit.MINUTES -> if (schedule.remindBeforeValue == 1) "minute" else "minutes"
            RemindBeforeUnit.HOURS -> if (schedule.remindBeforeValue == 1) "hour" else "hours"
        }

        val openAppIntent = PendingIntent.getActivity(
            context,
            schedule.id.toInt() + REMINDER_REQUEST_OFFSET,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle("Upcoming Call: ${schedule.title}")
            .setContentText("${schedule.contactName} (${schedule.phoneNumber}) in ${schedule.remindBeforeValue} $unitLabel")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)

        if (useVibration) {
            notificationBuilder.setSound(null).setVibrate(vibratePattern)
        } else {
            notificationBuilder.setSound(alarmUri)
        }

        try {
            notificationManager.notify(schedule.id.toInt() + REMINDER_REQUEST_OFFSET, notificationBuilder.build())
        } catch (_: SecurityException) {
            // Ignore if POST_NOTIFICATIONS was revoked before reminder fired
        }

        if (useVibration) {
            triggerReminderVibration(context, vibratePattern)
        } else {
            try {
                val ringtone = RingtoneManager.getRingtone(context, alarmUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.isLooping = false
                }
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone?.play()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (ringtone?.isPlaying == true) {
                        ringtone.stop()
                    }
                }, 8000L)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerReminderVibration(context: Context, pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, -1)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attrs)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findPhoneAccountForSlot(context: Context, slotIndex: Int): PhoneAccountHandle? {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            val accounts = telecomManager.callCapablePhoneAccounts
            if (accounts.isEmpty()) return null

            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subInfo = subManager?.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slotIndex }
            if (subInfo != null) {
                val subIdStr = subInfo.subscriptionId.toString()
                val iccId = subInfo.iccId
                accounts.firstOrNull { handle ->
                    handle.id == subIdStr ||
                        (!iccId.isNullOrBlank() && handle.id.contains(iccId, ignoreCase = true))
                } ?: accounts.getOrNull(slotIndex)
            } else {
                accounts.getOrNull(slotIndex)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Arms periodic Doze-exempt AlarmManager watchdog heartbeat to keep Foreground Service
     * resurrected after swipe-kill / process eviction and re-verify pending alarms.
     */
    fun scheduleWatchdogAlarm(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val watchdogIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_WATCHDOG_CHECK
            }
            val watchdogPending = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                watchdogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                watchdogPending
            )
        } catch (_: Exception) {
        }
    }

    private fun buildBackgroundActivityOptionsBundle(): Bundle? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }.toBundle()
        } else {
            null
        }
    }

    /**
     * Executes outgoing call reliably even when device is locked or app cleared from recents.
     * Integrates CALL_PHONE, Background Activity BAL ActivityOptions (API 34+ / MIUI OP 10021),
     * Battery Optimization awareness, and full-screen lockscreen call fallback.
     */
    @SuppressLint("MissingPermission")
    fun executeCall(context: Context, schedule: CallScheduleEntity): CallDispatchResult {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(schedule.id.toInt() + RECOVERY_NOTIF_OFFSET)
        } catch (_: Exception) {
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            postFullScreenCallFallback(context, schedule)
            return CallDispatchResult(
                dispatched = false,
                failureReason = "Phone Call permission (CALL_PHONE) is revoked."
            )
        }

        val slotIndex = if (schedule.simSlot == SimSlot.SIM_1) 0 else 1
        val accountHandle = findPhoneAccountForSlot(context, slotIndex)
        var dispatched = false
        var lastError: String? = null

        // 1. Primary path: TelecomManager.placeCall() communicates directly with TelecomService
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                val placeUri = Uri.fromParts("tel", schedule.phoneNumber, null)
                val extras = Bundle().apply {
                    if (accountHandle != null) {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                    }
                    if (schedule.audioRoute == AudioRoute.SPEAKER) {
                        putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
                    }
                }
                telecomManager.placeCall(placeUri, extras)
                dispatched = true
            }
        } catch (e: Exception) {
            lastError = e.localizedMessage
            dispatched = false
        }

        // 2. Secondary path: Intent.ACTION_CALL with multi-SIM extras & explicit BAL ActivityOptions
        if (!dispatched) {
            try {
                val encodedNumber = Uri.encode(schedule.phoneNumber)
                val callUri = Uri.parse("tel:$encodedNumber")
                val callIntent = Intent(Intent.ACTION_CALL, callUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (accountHandle != null) {
                        putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
                    }
                    putExtra("com.android.phone.extra.slot", slotIndex)
                    putExtra("simSlot", slotIndex)
                    putExtra("slot_id", slotIndex)
                    putExtra("phone_account_or_subid", slotIndex)
                }
                val balBundle = buildBackgroundActivityOptionsBundle()
                if (balBundle != null) {
                    context.startActivity(callIntent, balBundle)
                } else {
                    context.startActivity(callIntent)
                }
                dispatched = true
            } catch (e: Exception) {
                e.printStackTrace()
                postFullScreenCallFallback(context, schedule)
                val diagnostic = when {
                    !PermissionHelper.isBackgroundActivityAllowed(context) ->
                        "Background Activity blocked: enable Background Activity / 'Open new windows while running in the background'."
                    !PermissionHelper.isIgnoringBatteryOptimizations(context) ->
                        "Battery Optimization restricted background execution (${e.javaClass.simpleName})."
                    else ->
                        lastError ?: e.localizedMessage ?: "Dial intent blocked or SIM unavailable"
                }
                return CallDispatchResult(dispatched = false, failureReason = diagnostic)
            }
        }

        // 3. Run speakerphone delay & auto-hangup timer inside persistent Foreground Service
        CallSchedulerService.schedulePostCallEffects(
            context = context,
            useSpeaker = schedule.audioRoute == AudioRoute.SPEAKER,
            autoHangupSeconds = schedule.autoHangupSeconds
        )
        return CallDispatchResult(dispatched = dispatched)
    }

    private fun postFullScreenCallFallback(context: Context, schedule: CallScheduleEntity) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CALL_TRIGGER_CHANNEL_ID,
                    "Scheduled Call Execution",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
            val wakeIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DISPATCH_CALL_ID, schedule.id)
            }
            val fullScreenPending = PendingIntent.getActivity(
                context,
                schedule.id.toInt() + 2_000_000,
                wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                buildBackgroundActivityOptionsBundle()
            )
            val notification = NotificationCompat.Builder(context, CALL_TRIGGER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle("Calling ${schedule.contactName}")
                .setContentText("${schedule.title} • ${schedule.phoneNumber}")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setFullScreenIntent(fullScreenPending, true)
                .setContentIntent(fullScreenPending)
                .build()
            nm.notify(schedule.id.toInt() + 2_000_000, notification)
        } catch (_: Exception) {
        }
    }

    /**
     * Checks whether the device is currently in Airplane Mode
     */
    fun isAirplaneModeOn(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) != 0
    }

    /**
     * Attempts to turn Airplane Mode OFF when Respect Airplane Mode is unchecked.
     * Note: Android 10-15 / HyperOS restricts Settings.Global.AIRPLANE_MODE_ON from WRITE_SETTINGS alone.
     * Tries in order:
     * 1) Root shell (su)
     * 2) ConnectivityManager.setAirplaneMode(false)
     * 3) Built-in Accessibility Helper (taps OS Settings / Quick Settings toggle on unrooted devices)
     * 4) WRITE_SECURE_SETTINGS (ADB grant) + protected broadcast in separate try/catch blocks
     * 5) Unprivileged connectivity command fallback
     */
    @SuppressLint("PrivateApi")
    fun disableAirplaneMode(context: Context): Boolean {
        if (!isAirplaneModeOn(context)) return true

        // 1. Root shell path (cleanly cycles ConnectivityService & telephony radios)
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd connectivity airplane-mode disable")).waitFor()
            SystemClock.sleep(500L)
            if (!isAirplaneModeOn(context)) return true

            Runtime.getRuntime().exec(
                arrayOf(
                    "su",
                    "-c",
                    "settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
                )
            ).waitFor()
            SystemClock.sleep(500L)
            if (!isAirplaneModeOn(context)) return true
        } catch (_: Exception) {
            // Device not rooted
        }

        // 2. ConnectivityManager.setAirplaneMode(false) via reflection (succeeds if privileged)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val method = cm.javaClass.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType)
                method.invoke(cm, false)
                SystemClock.sleep(500L)
                if (!isAirplaneModeOn(context)) return true
            }
        } catch (_: Exception) {
            // Requires NETWORK_SETTINGS / system privilege on standard builds
        }

        // 3. Optional AccessibilityService path (works on stock unrooted Android 10-15 & HyperOS)
        if (AirplaneModeAccessibilityService.isRunning()) {
            if (AirplaneModeAccessibilityService.disableAirplaneModeBlocking(context)) {
                return true
            }
        }

        // 4. ADB WRITE_SECURE_SETTINGS path — keep putInt, protected sendBroadcast & shell isolated
        if (PermissionHelper.isWriteSecureSettingsGranted(context)) {
            try {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    0
                )
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }

            try {
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", false)
                }
                context.sendBroadcast(intent)
            } catch (_: SecurityException) {
                // Protected broadcast on Android 11+; ignore without masking earlier steps
            } catch (_: Exception) {
            }
        }

        // 5. Shell connectivity fallback (if process holds shell-compatible capabilities)
        try {
            Runtime.getRuntime().exec(arrayOf("cmd", "connectivity", "airplane-mode", "disable")).waitFor()
        } catch (_: Exception) {
        }

        return !isAirplaneModeOn(context)
    }

    @SuppressLint("MissingPermission")
    fun endCallSafely(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecomManager?.endCall()
            } catch (_: SecurityException) {
                // ANSWER_PHONE_CALLS revoked while app was running
            }
        }
    }
}