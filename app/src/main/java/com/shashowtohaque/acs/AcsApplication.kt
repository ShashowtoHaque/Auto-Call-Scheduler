package com.shashowtohaque.acs

import android.app.Application
import com.shashowtohaque.acs.data.local.AppDatabase
import com.shashowtohaque.acs.service.CallSchedulerService
import com.shashowtohaque.acs.util.CallDispatcher
import com.shashowtohaque.acs.util.PermissionHelper

class AcsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize Room DB early
        AppDatabase.getDatabase(this)
        // Ensure Boot/Alarm BroadcastReceivers stay enabled
        PermissionHelper.ensureReceiversEnabled(this)
        // Start Foreground Service & arm survivability watchdog if permitted
        CallSchedulerService.startService(this)
        if (PermissionHelper.isAutostartAllowed(this) || PermissionHelper.isIgnoringBatteryOptimizations(this)) {
            CallDispatcher.scheduleWatchdogAlarm(this)
        }
    }
}