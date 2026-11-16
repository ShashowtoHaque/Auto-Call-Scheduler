package com.shashowtohaque.acs.util

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.shashowtohaque.acs.receiver.AlarmReceiver
import com.shashowtohaque.acs.receiver.BootReceiver
import com.shashowtohaque.acs.service.AirplaneModeAccessibilityService

data class SurvivabilityReport(
    val autostartGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val backgroundActivityAllowed: Boolean,
    val lockScreenAllowed: Boolean
) {
    val isFullyProtected: Boolean
        get() = autostartGranted && batteryUnrestricted && backgroundActivityAllowed && lockScreenAllowed
}

object PermissionHelper {

    private const val PREFS_NAME = "acs_permission_prefs"
    private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"
    private const val KEY_BG_ACTIVITY_CONFIRMED = "bg_activity_confirmed"
    private const val KEY_LOCKSCREEN_CONFIRMED = "lockscreen_confirmed"

    fun isXiaomiDevice(): Boolean =
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("POCO", ignoreCase = true)

    fun isCallPhoneGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun isContactsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun isNotificationGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isOverlayGranted(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isAppBackgroundRestricted(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activityManager?.isBackgroundRestricted == true
    }

    /**
     * 1. Background Autostart (AUTOSTART_HYPEROS):
     * Governs reboot recovery (BootReceiver), swipe-kill survival, and watchdog resurrection.
     * Completely separate from Battery Optimization and Background Activity.
     */
    fun isAutostartAllowed(context: Context): Boolean {
        if (isAppBackgroundRestricted(context)) return false
        if (!areReceiversEnabled(context)) return false

        if (isXiaomiDevice()) {
            val miuiOpAllowed = checkMiuiAppOp(context, 10008) ?: checkXiaomiAutoStartUtils(context)
            if (miuiOpAllowed != null) return miuiOpAllowed
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false)
        }

        // Other OEMs with dedicated autostart managers require user confirmation if activity resolves
        if (hasNonXiaomiOemAutostartActivity(context)) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false)
        }

        // Standard Android devices grant RECEIVE_BOOT_COMPLETED automatically when not background-restricted
        return true
    }

    fun markAutostartConfirmed(context: Context, confirmed: Boolean = true) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOSTART_CONFIRMED, confirmed)
            .apply()
    }

    /**
     * 2. Battery Optimization (BATTERY_OPTIMIZATION):
     * Standard Android Doze Mode & App Standby exemption (PowerManager.isIgnoringBatteryOptimizations).
     * Exempts CallSchedulerService, WakeLock, and exact alarms from PowerKeeper/Doze throttling.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 3. Background Activity (BACKGROUND_ACTIVITY):
     * Permits launching call windows & dialer intents from background/locked state.
     * Checks Android !isBackgroundRestricted AND Xiaomi OP_BACKGROUND_START_ACTIVITY (10021)
     * ("Open new windows while running in the background") — independent of Battery Optimization.
     */
    fun isBackgroundActivityAllowed(context: Context): Boolean {
        if (isAppBackgroundRestricted(context)) return false
        if (isXiaomiDevice()) {
            val miuiBgAllowed = checkMiuiAppOp(context, 10021) // OP_BACKGROUND_START_ACTIVITY
            if (miuiBgAllowed != null) return miuiBgAllowed
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_BG_ACTIVITY_CONFIRMED, false)
        }
        return true
    }

    fun markBackgroundActivityConfirmed(context: Context, confirmed: Boolean = true) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BG_ACTIVITY_CONFIRMED, confirmed)
            .apply()
    }

    /**
     * Checks if lock screen presentation & full-screen call intent are permitted
     * (Xiaomi OP_SHOW_WHEN_LOCKED = 10020 + Android 14+ NotificationManager.canUseFullScreenIntent)
     */
    fun isShowOnLockScreenAllowed(context: Context): Boolean {
        val fullScreenAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.canUseFullScreenIntent() ?: true
        } else {
            true
        }
        if (!fullScreenAllowed) return false

        if (isXiaomiDevice()) {
            val miuiOpAllowed = checkMiuiAppOp(context, 10020) // OP_SHOW_WHEN_LOCKED
            if (miuiOpAllowed != null) return miuiOpAllowed
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_LOCKSCREEN_CONFIRMED, false)
        }
        return true
    }

    fun markLockScreenConfirmed(context: Context, confirmed: Boolean = true) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCKSCREEN_CONFIRMED, confirmed)
            .apply()
    }

    fun isExactAlarmGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
        return alarmManager.canScheduleExactAlarms()
    }

    fun isWriteSettingsGranted(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun isWriteSecureSettingsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    fun isAccessibilityHelperEnabled(context: Context): Boolean =
        AirplaneModeAccessibilityService.isRunning() ||
            AirplaneModeAccessibilityService.isServiceEnabledInSettings(context)

    fun canAutoDisableAirplaneMode(context: Context): Boolean =
        isWriteSecureSettingsGranted(context) || isAccessibilityHelperEnabled(context)

    fun canStartForegroundFromBackground(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return isIgnoringBatteryOptimizations(context) || isAutostartAllowed(context)
    }

    fun getSurvivabilityReport(context: Context): SurvivabilityReport {
        return SurvivabilityReport(
            autostartGranted = isAutostartAllowed(context),
            batteryUnrestricted = isIgnoringBatteryOptimizations(context),
            backgroundActivityAllowed = isBackgroundActivityAllowed(context),
            lockScreenAllowed = isShowOnLockScreenAllowed(context)
        )
    }

    fun areReceiversEnabled(context: Context): Boolean {
        val pm = context.packageManager
        val bootState = pm.getComponentEnabledSetting(ComponentName(context, BootReceiver::class.java))
        val alarmState = pm.getComponentEnabledSetting(ComponentName(context, AlarmReceiver::class.java))
        return bootState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            alarmState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun ensureReceiversEnabled(context: Context) {
        try {
            val pm = context.packageManager
            listOf(BootReceiver::class.java, AlarmReceiver::class.java).forEach { receiverClass ->
                val component = ComponentName(context, receiverClass)
                if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Verifies all 11 mandatory permissions & device settings are currently active.
     */
    fun areAllPermissionsGranted(context: Context): Boolean {
        return isCallPhoneGranted(context) &&
            isContactsGranted(context) &&
            isAutostartAllowed(context) &&
            isIgnoringBatteryOptimizations(context) &&
            isBackgroundActivityAllowed(context) &&
            isShowOnLockScreenAllowed(context) &&
            isOverlayGranted(context) &&
            isNotificationGranted(context) &&
            isWriteSettingsGranted(context) &&
            isExactAlarmGranted(context) &&
            isAccessibilityHelperEnabled(context)
    }

    fun checkMiuiAppOp(context: Context, opCode: Int): Boolean? {
        return try {
            if (!isXiaomiDevice()) return null
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = try {
                AppOpsManager::class.java.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
            } catch (_: NoSuchMethodException) {
                AppOpsManager::class.java.getDeclaredMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                ).apply { isAccessible = true }
            }
            val result = method.invoke(appOps, opCode, Process.myUid(), context.packageName) as Int
            result == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            null
        }
    }

    private fun checkXiaomiAutoStartUtils(context: Context): Boolean? {
        return try {
            val clazz = Class.forName("android.miui.AppOpsUtils")
            val method = clazz.getMethod("getApplicationAutoStart", Context::class.java, String::class.java)
            val mode = method.invoke(null, context, context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            null
        }
    }

    private fun getNonXiaomiAutostartIntents(): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
        Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"))
    )

    private fun hasNonXiaomiOemAutostartActivity(context: Context): Boolean {
        val pm = context.packageManager
        return getNonXiaomiAutostartIntents().any { intent ->
            try {
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 1. Opens Dedicated Background Autostart Settings (HyperOS/MIUI or OEM Startup Manager)
     */
    fun openHyperOsAutostartSettings(context: Context): Boolean {
        val intents = if (isXiaomiDevice()) {
            listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
            )
        } else {
            getNonXiaomiAutostartIntents()
        }
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                if ((isXiaomiDevice() && checkMiuiAppOp(context, 10008) == null && checkXiaomiAutoStartUtils(context) == null) ||
                    !isXiaomiDevice()
                ) {
                    markAutostartConfirmed(context, true)
                }
                return true
            } catch (_: Exception) {
                // Try fallback intent
            }
        }
        if (!isXiaomiDevice()) {
            markAutostartConfirmed(context, true)
        }
        openAppDetailsSettings(context)
        return false
    }

    /**
     * 2. Opens Dedicated Battery Optimization Exemption Dialog / HyperOS PowerKeeper Settings
     */
    fun openBatteryOptimizationSettings(context: Context) {
        val intents = buildList {
            add(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
            if (isXiaomiDevice()) {
                val appName = context.applicationInfo.loadLabel(context.packageManager).toString()
                add(
                    Intent().apply {
                        component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                        putExtra("package_name", context.packageName)
                        putExtra("package_label", appName)
                    }
                )
            }
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Try next fallback
            }
        }
        openAppDetailsSettings(context)
    }

    /**
     * 3. Opens Dedicated Background Activity Settings
     * Xiaomi HyperOS/MIUI: App Permissions Editor -> "Open new windows while running in the background"
     * Standard Android: App Details -> Battery / Background usage restriction settings
     */
    fun openBackgroundActivitySettings(context: Context) {
        if (isXiaomiDevice()) {
            val miuiIntents = listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    putExtra("extra_pkgname", context.packageName)
                },
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                    putExtra("extra_pkgname", context.packageName)
                }
            )
            for (intent in miuiIntents) {
                try {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    if (checkMiuiAppOp(context, 10021) == null) {
                        markBackgroundActivityConfirmed(context, true)
                    }
                    return
                } catch (_: Exception) {
                    // Try fallback
                }
            }
        }
        openAppDetailsSettings(context)
    }

    fun openLockScreenPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try {
                    val fsiIntent = Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fsiIntent)
                    return
                } catch (_: Exception) {
                }
            }
        }

        if (isXiaomiDevice()) {
            val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(miuiIntent)
                if (checkMiuiAppOp(context, 10020) == null) {
                    markLockScreenConfirmed(context, true)
                }
                return
            } catch (_: Exception) {
                if (checkMiuiAppOp(context, 10020) == null) {
                    markLockScreenConfirmed(context, true)
                }
            }
        }
        openAppDetailsSettings(context)
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openWriteSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        openAppDetailsSettings(context)
    }

    /**
     * Fetches real carrier/operator names for SIM 1 (slot 0) and SIM 2 (slot 1)
     */
    fun getSimOperatorNames(context: Context): Pair<String, String> {
        var sim1Operator: String? = null
        var sim2Operator: String? = null

        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val activeSubs = subManager?.activeSubscriptionInfoList.orEmpty()
                for (sub in activeSubs) {
                    val operator = sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
                        ?: sub.displayName?.toString()?.takeIf { it.isNotBlank() }
                    when (sub.simSlotIndex) {
                        0 -> sim1Operator = operator
                        1 -> sim2Operator = operator
                    }
                }
            }
            if (sim1Operator.isNullOrBlank()) {
                val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                sim1Operator = telephony?.simOperatorName?.takeIf { it.isNotBlank() }
                    ?: telephony?.networkOperatorName?.takeIf { it.isNotBlank() }
            }
        } catch (_: SecurityException) {
            // Permission revoked or restricted by OS
        } catch (_: Exception) {
            // Safe fallback
        }

        return Pair(
            sim1Operator ?: "No SIM",
            sim2Operator ?: "No SIM"
        )
    }
}