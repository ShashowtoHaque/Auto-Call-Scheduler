package com.shashowtohaque.acs.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shashowtohaque.acs.data.local.AppDatabase
import com.shashowtohaque.acs.data.local.ScheduleStatus
import com.shashowtohaque.acs.service.CallSchedulerService
import com.shashowtohaque.acs.util.CallDispatcher
import com.shashowtohaque.acs.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_REBOOT ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            PermissionHelper.ensureReceiversEnabled(context)

            // 1. Resurrect Foreground Service if background execution is permitted
            if (PermissionHelper.isAutostartAllowed(context) ||
                PermissionHelper.canStartForegroundFromBackground(context)
            ) {
                CallSchedulerService.startService(context)
            }

            // 2. Reschedule all pending alarms and re-arm periodic survival watchdog
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val pendingSchedules = db.callScheduleDao().getPendingSchedules().first()
                    val now = System.currentTimeMillis()
                    for (schedule in pendingSchedules) {
                        if (schedule.status == ScheduleStatus.PENDING &&
                            schedule.targetTimeMillis > now
                        ) {
                            CallDispatcher.scheduleAlarm(context, schedule)
                        }
                    }
                    if (pendingSchedules.isNotEmpty() || PermissionHelper.isAutostartAllowed(context)) {
                        CallDispatcher.scheduleWatchdogAlarm(context)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}