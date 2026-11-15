package com.shashowtohaque.acs.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.shashowtohaque.acs.data.local.AppDatabase
import com.shashowtohaque.acs.data.local.CallScheduleEntity
import com.shashowtohaque.acs.data.local.ConfirmationMode
import com.shashowtohaque.acs.data.local.ScheduleStatus
import com.shashowtohaque.acs.data.repository.ScheduleRepository
import com.shashowtohaque.acs.service.CallSchedulerService
import com.shashowtohaque.acs.util.CallDispatcher
import com.shashowtohaque.acs.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: CallDispatcher.ACTION_EXECUTE_CALL

        // Promote process via persistent Foreground Service when Battery Optimization / FGS rules permit
        if (PermissionHelper.canStartForegroundFromBackground(context)) {
            CallSchedulerService.startService(context)
        }

        // Acquire Partial WakeLock to guarantee CPU execution during Doze / sleep
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ACS:CallAlarmWakeLock"
        )
        wakeLock.acquire(45_000L)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val repository = ScheduleRepository(db.callScheduleDao())

                if (action == CallDispatcher.ACTION_WATCHDOG_CHECK) {
                    val pendingList = repository.pendingSchedules.first()
                        .filter { it.status == ScheduleStatus.PENDING }
                    val now = System.currentTimeMillis()
                    for (sched in pendingList) {
                        if (sched.targetTimeMillis > now) {
                            CallDispatcher.scheduleAlarm(context, sched)
                        } else if (now - sched.targetTimeMillis <= 120_000L) {
                            // Recover schedule missed briefly during OS process kill or reboot
                            executeScheduledCall(context, repository, sched)
                        }
                    }
                    if (pendingList.isNotEmpty() || PermissionHelper.isAutostartAllowed(context)) {
                        CallDispatcher.scheduleWatchdogAlarm(context)
                    }
                    return@launch
                }

                val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
                if (scheduleId == -1L) return@launch

                val schedule = repository.getScheduleById(scheduleId)
                if (schedule != null && schedule.status == ScheduleStatus.PENDING) {
                    if (action == CallDispatcher.ACTION_REMIND_CALL) {
                        if (schedule.confirmationMode == ConfirmationMode.REMINDER) {
                            CallDispatcher.triggerReminderAlarm(context, schedule)
                        }
                        return@launch
                    }

                    executeScheduledCall(context, repository, schedule)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }

    private suspend fun executeScheduledCall(
        context: Context,
        repository: ScheduleRepository,
        schedule: CallScheduleEntity
    ) {
        // Respect Airplane Mode handling
        if (CallDispatcher.isAirplaneModeOn(context)) {
            if (schedule.respectAirplaneMode) {
                val failed = schedule.copy(
                    status = ScheduleStatus.FAILED,
                    lastExecutedMillis = System.currentTimeMillis(),
                    failureReason = "Respect Airplane Mode enabled: device is in Airplane Mode with no cellular network."
                )
                repository.updateSchedule(failed)
                return
            } else {
                val disabled = CallDispatcher.disableAirplaneMode(context)
                if (!disabled) {
                    val failed = schedule.copy(
                        status = ScheduleStatus.FAILED,
                        lastExecutedMillis = System.currentTimeMillis(),
                        failureReason = "Device in Airplane Mode: unable to toggle Airplane Mode OFF without system/ADB permission."
                    )
                    repository.updateSchedule(failed)
                    return
                }
                // Wait 10 seconds for cellular network stability before executing the schedule
                kotlinx.coroutines.delay(10_000L)
            }
        }

        // Dispatch Call via TelecomManager / BAL-aware Native Call Intent
        val result = CallDispatcher.executeCall(context, schedule)

        if (result.dispatched) {
            if (schedule.hasScheduleRepeat()) {
                val nextState = CallDispatcher.advanceScheduleAfterCall(schedule)
                val updated = schedule.copy(
                    targetTimeMillis = nextState.targetTimeMillis,
                    anchorTimeMillis = nextState.anchorTimeMillis,
                    currentRepeatCount = nextState.currentRepeatCount,
                    lastExecutedMillis = System.currentTimeMillis(),
                    status = nextState.status
                )
                repository.updateSchedule(updated)
                if (nextState.status == ScheduleStatus.PENDING) {
                    CallDispatcher.scheduleAlarm(context, updated)
                }
            } else {
                val updated = schedule.copy(
                    status = ScheduleStatus.COMPLETED,
                    lastExecutedMillis = System.currentTimeMillis()
                )
                repository.updateSchedule(updated)
            }
        } else {
            val failed = schedule.copy(
                status = ScheduleStatus.FAILED,
                lastExecutedMillis = System.currentTimeMillis(),
                failureReason = result.failureReason ?: "Dial intent failed or SIM unavailable"
            )
            repository.updateSchedule(failed)
        }
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
    }
}