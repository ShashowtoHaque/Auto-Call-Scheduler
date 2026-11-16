package com.shashowtohaque.acs.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shashowtohaque.acs.util.CallDispatcher

class AirplaneModeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 150L
        }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = SystemClock.elapsedRealtime()
        if (now >= requestDeadlineMs) return
        if (!CallDispatcher.isAirplaneModeOn(this)) {
            requestDeadlineMs = 0L
            return
        }

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName) return
        if (now - lastClickMs < 1_500L) return

        if (tryToggleAirplaneOffInTree(root)) {
            lastClickMs = SystemClock.elapsedRealtime()
        }
    }

    override fun onInterrupt() {
        // No persistent state required
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    private fun tryToggleAirplaneOffInTree(root: AccessibilityNodeInfo): Boolean {
        val visited = HashSet<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && visited.size < 250) {
            val (node, depth) = queue.removeFirst()
            if (!visited.add(node) || depth > 18) continue

            val label = buildString {
                node.text?.let { append(it).append(' ') }
                node.contentDescription?.let { append(it) }
            }.trim().lowercase()

            if (isAirplaneLabel(label) && clickAirplaneControl(node)) {
                return true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child to (depth + 1))
            }
        }
        return false
    }

    private fun isAirplaneLabel(text: String): Boolean {
        if (text.isBlank()) return false
        return text.contains("airplane mode") ||
            text.contains("aeroplane mode") ||
            text.contains("flight mode") ||
            text == "airplane" ||
            text == "aeroplane"
    }

    private fun clickAirplaneControl(labelNode: AccessibilityNodeInfo): Boolean {
        if (labelNode.isCheckable) {
            if (!labelNode.isChecked) return false
            return performClickOrParent(labelNode)
        }

        var container: AccessibilityNodeInfo? = labelNode.parent
        var steps = 0
        var clickableRow: AccessibilityNodeInfo? = if (labelNode.isClickable) labelNode else null

        while (container != null && steps < 4) {
            if (clickableRow == null && container.isClickable) {
                clickableRow = container
            }
            val switchNode = findCheckableDescendant(container)
            if (switchNode != null) {
                if (!switchNode.isChecked) return false
                if (switchNode.isClickable && switchNode.isEnabled &&
                    switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
                if (clickableRow != null && clickableRow.isEnabled &&
                    clickableRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
            }
            container = container.parent
            steps++
        }

        if (clickableRow != null && clickableRow.isEnabled && CallDispatcher.isAirplaneModeOn(this)) {
            return clickableRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    private fun performClickOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 3) {
            if (parent.isClickable && parent.isEnabled && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            hops++
        }
        return false
    }

    private fun findCheckableDescendant(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (node.isCheckable) return node
            if (depth >= 5) continue
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child to (depth + 1))
            }
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: AirplaneModeAccessibilityService? = null

        @Volatile
        private var requestDeadlineMs: Long = 0L

        @Volatile
        private var lastClickMs: Long = 0L

        fun isRunning(): Boolean = instance != null

        fun isServiceEnabledInSettings(context: Context): Boolean {
            if (instance != null) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val targetFull = "${context.packageName}/${AirplaneModeAccessibilityService::class.java.name}"
                val targetShort = "${context.packageName}/.service.AirplaneModeAccessibilityService"
                enabledServices.split(':').any { entry ->
                    entry.equals(targetFull, ignoreCase = true) ||
                        entry.equals(targetShort, ignoreCase = true)
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun buildBalBundle(): Bundle? {
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

        fun disableAirplaneModeBlocking(context: Context): Boolean {
            val service = instance ?: return false
            if (!CallDispatcher.isAirplaneModeOn(context)) return true

            requestDeadlineMs = SystemClock.elapsedRealtime() + 10_000L
            lastClickMs = 0L

            // Briefly wake screen if sleeping so Settings or Quick Settings can render windows
            var screenWakeLock: PowerManager.WakeLock? = null
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null && !pm.isInteractive) {
                    @Suppress("DEPRECATION")
                    screenWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "ACS:AirplaneScreenWake"
                    )
                    screenWakeLock?.acquire(9_000L)
                }
            } catch (_: Exception) {
            }

            try {
                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val isLocked = keyguardManager?.isKeyguardLocked == true
                var openedSettings = false

                // If device is unlocked, Settings screen is most deterministic; if locked, open Quick Settings over lockscreen first
                if (!isLocked) {
                    val settingsIntents = listOf(
                        Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
                        Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    )
                    val balBundle = buildBalBundle()
                    for (intent in settingsIntents) {
                        try {
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            if (balBundle != null) {
                                service.startActivity(intent, balBundle)
                            } else {
                                service.startActivity(intent)
                            }
                            openedSettings = true
                            break
                        } catch (_: Exception) {
                            // Try next settings action
                        }
                    }
                }

                if (!openedSettings) {
                    service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                }

                var triedQuickSettings = !openedSettings
                val pollStart = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - pollStart < 7_500L) {
                    if (!CallDispatcher.isAirplaneModeOn(context)) {
                        requestDeadlineMs = 0L
                        service.performGlobalAction(
                            if (isLocked) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME
                        )
                        return true
                    }
                    if (!triedQuickSettings && SystemClock.elapsedRealtime() - pollStart >= 3_000L) {
                        triedQuickSettings = true
                        service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                    }
                    try {
                        service.rootInActiveWindow?.let { activeRoot ->
                            val now = SystemClock.elapsedRealtime()
                            if (activeRoot.packageName?.toString() != service.packageName &&
                                now - lastClickMs >= 2_000L
                            ) {
                                if (service.tryToggleAirplaneOffInTree(activeRoot)) {
                                    lastClickMs = now
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                    SystemClock.sleep(350L)
                }
            } finally {
                requestDeadlineMs = 0L
                try {
                    if (screenWakeLock?.isHeld == true) {
                        screenWakeLock.release()
                    }
                } catch (_: Exception) {
                }
            }

            return !CallDispatcher.isAirplaneModeOn(context)
        }
    }
}