package com.shashowtohaque.acs

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shashowtohaque.acs.data.local.AppDatabase
import com.shashowtohaque.acs.data.local.CallScheduleEntity
import com.shashowtohaque.acs.service.CallSchedulerService
import com.shashowtohaque.acs.ui.screens.*
import com.shashowtohaque.acs.ui.viewmodel.ScheduleViewModel
import com.shashowtohaque.acs.util.CallDispatcher
import com.shashowtohaque.acs.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: ScheduleViewModel by viewModels()

    private fun applyMaxRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val currentDisplay = display ?: return
                val activeMode = currentDisplay.mode
                val sameResMaxMode = currentDisplay.supportedModes
                    .filter {
                        it.physicalWidth == activeMode.physicalWidth &&
                            it.physicalHeight == activeMode.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate }
                    ?: currentDisplay.supportedModes.maxByOrNull { it.refreshRate }
                sameResMaxMode?.let { maxMode ->
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                        preferredRefreshRate = maxMode.refreshRate
                    }
                }
            } catch (_: Exception) {
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val modes = windowManager.defaultDisplay?.supportedModes
                val maxRate = modes?.maxOfOrNull { it.refreshRate } ?: 0f
                if (maxRate > 0f) {
                    window.attributes = window.attributes.apply {
                        preferredRefreshRate = maxRate
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readPermissionMask(): Int {
        var mask = 0
        if (PermissionHelper.isCallPhoneGranted(this)) mask = mask or (1 shl 0)
        if (PermissionHelper.isContactsGranted(this)) mask = mask or (1 shl 1)
        if (PermissionHelper.isAutostartAllowed(this)) mask = mask or (1 shl 2)
        if (PermissionHelper.isIgnoringBatteryOptimizations(this)) mask = mask or (1 shl 3)
        if (PermissionHelper.isBackgroundActivityAllowed(this)) mask = mask or (1 shl 4)
        if (PermissionHelper.isShowOnLockScreenAllowed(this)) mask = mask or (1 shl 5)
        if (PermissionHelper.isOverlayGranted(this)) mask = mask or (1 shl 6)
        if (PermissionHelper.isNotificationGranted(this)) mask = mask or (1 shl 7)
        if (PermissionHelper.isWriteSettingsGranted(this)) mask = mask or (1 shl 8)
        if (PermissionHelper.isExactAlarmGranted(this)) mask = mask or (1 shl 9)
        if (PermissionHelper.isAccessibilityHelperEnabled(this)) mask = mask or (1 shl 10)
        return mask
    }

    override fun onResume() {
        super.onResume()
        applyMaxRefreshRate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Request highest available refresh rate matching active resolution (e.g. 120Hz LTPO on Xiaomi/HyperOS)
        applyMaxRefreshRate()

        // Android 10+ Lock Screen Presentation Flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Start Ongoing Foreground Service & handle any lockscreen full-screen call dispatch
        CallSchedulerService.startService(this)
        handleDispatchIntent(intent)

        val uiPrefs = getSharedPreferences("acs_ui_prefs", Context.MODE_PRIVATE)

        setContent {
            var themeMode by remember {
                mutableStateOf(uiPrefs.getString("ui_theme_mode", "SYSTEM") ?: "SYSTEM")
            }
            val systemDarkByConfig =
                (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val systemInDarkTheme = isSystemInDarkTheme() || systemDarkByConfig
            val darkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> systemInDarkTheme
            }

            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as? Activity)?.window ?: return@SideEffect
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !darkTheme
                    controller.isAppearanceLightNavigationBars = !darkTheme
                }
            }

            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                val navController = rememberNavController()
                var permissionTick by remember { mutableIntStateOf(0) }
                var lastPermissionMask by remember { mutableIntStateOf(readPermissionMask()) }
                var showConfigureSheet by remember { mutableStateOf(false) }
                var editingSchedule by remember { mutableStateOf<CallScheduleEntity?>(null) }

                val syncPermissionState: () -> Unit = {
                    lastPermissionMask = readPermissionMask()
                    permissionTick++
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    syncPermissionState()
                }
                val multiPermissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    syncPermissionState()
                }

                // Re-verify permissions whenever the activity resumes from Settings or background
                DisposableEffect(this@MainActivity) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            syncPermissionState()
                        }
                    }
                    this@MainActivity.lifecycle.addObserver(observer)
                    onDispose {
                        this@MainActivity.lifecycle.removeObserver(observer)
                    }
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                // Poll off-UI-thread and only recompose when a permission status actually changes
                LaunchedEffect(currentRoute) {
                    while (true) {
                        delay(if (currentRoute == "onboarding") 1500L else 4000L)
                        val newMask = withContext(Dispatchers.Default) {
                            readPermissionMask()
                        }
                        if (newMask != lastPermissionMask) {
                            lastPermissionMask = newMask
                            permissionTick++
                        }
                    }
                }

                val permissionList = remember(permissionTick) {
                    listOf(
                        PermissionStatusItem(
                            id = "CALL_PHONE",
                            title = "Phone Call Permission",
                            description = "Places direct SIM 1 / SIM 2 calls and enables auto hang-up",
                            isGranted = PermissionHelper.isCallPhoneGranted(this@MainActivity),
                            onRequest = {
                                multiPermissionsLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.CALL_PHONE,
                                        Manifest.permission.ANSWER_PHONE_CALLS,
                                        Manifest.permission.READ_PHONE_STATE
                                    )
                                )
                            }
                        ),
                        PermissionStatusItem(
                            id = "READ_CONTACTS",
                            title = "Read/Write Contacts",
                            description = "Allows picking call recipients from device contacts",
                            isGranted = PermissionHelper.isContactsGranted(this@MainActivity),
                            onRequest = {
                                multiPermissionsLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_CONTACTS,
                                        Manifest.permission.WRITE_CONTACTS
                                    )
                                )
                            }
                        ),
                        PermissionStatusItem(
                            id = "AUTOSTART_HYPEROS",
                            title = "Background Autostart",
                            description = "Keeps alarms & receivers active after reboot or app close",
                            isGranted = PermissionHelper.isAutostartAllowed(this@MainActivity),
                            onRequest = { PermissionHelper.openHyperOsAutostartSettings(this@MainActivity) }
                        ),
                        PermissionStatusItem(
                            id = "BATTERY_OPTIMIZATION",
                            title = "Battery Optimization",
                            description = "Excludes the scheduler from Doze and battery throttling",
                            isGranted = PermissionHelper.isIgnoringBatteryOptimizations(this@MainActivity),
                            onRequest = { PermissionHelper.openBatteryOptimizationSettings(this@MainActivity) }
                        ),
                        PermissionStatusItem(
                            id = "BACKGROUND_ACTIVITY",
                            title = "Background Activity",
                            description = "Allows launching call windows from the background",
                            isGranted = PermissionHelper.isBackgroundActivityAllowed(this@MainActivity),
                            onRequest = { PermissionHelper.openBackgroundActivitySettings(this@MainActivity) }
                        ),
                        PermissionStatusItem(
                            id = "SHOW_ON_LOCKSCREEN",
                            title = "Show on Lock Screen",
                            description = "Displays scheduled call triggers over the lock screen",
                            isGranted = PermissionHelper.isShowOnLockScreenAllowed(this@MainActivity),
                            onRequest = { PermissionHelper.openLockScreenPermissionSettings(this@MainActivity) }
                        ),
                        PermissionStatusItem(
                            id = "SYSTEM_ALERT_WINDOW",
                            title = "Display Over Other Apps",
                            description = "Enables in-call notes and controls over other apps",
                            isGranted = PermissionHelper.isOverlayGranted(this@MainActivity),
                            onRequest = { PermissionHelper.openOverlaySettings(this@MainActivity) }
                        ),
                        PermissionStatusItem(
                            id = "POST_NOTIFICATIONS",
                            title = "Active Notification",
                            description = "Shows live schedule counts in status bar & lock screen",
                            isGranted = PermissionHelper.isNotificationGranted(this@MainActivity),
                            onRequest = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        ),
                        PermissionStatusItem(
                            id = "WRITE_SETTINGS",
                            title = "Modify System Settings",
                            description = "Supports system settings access for Airplane Mode recovery",
                            isGranted = PermissionHelper.isWriteSettingsGranted(this@MainActivity),
                            onRequest = { PermissionHelper.openWriteSettings(this@MainActivity) }
                        ),
                        PermissionStatusItem(
                            id = "SCHEDULE_EXACT_ALARM",
                            title = "Alarms & Reminders",
                            description = "Ensures exact-second call and network recovery wakeups",
                            isGranted = PermissionHelper.isExactAlarmGranted(this@MainActivity),
                            onRequest = { PermissionHelper.openExactAlarmSettings(this@MainActivity) }
                        ),
                        PermissionStatusItem(
                            id = "ACCESSIBILITY_SERVICE",
                            title = "Accessibility Airplane Helper",
                            description = "Turns Airplane Mode OFF automatically (allow restricted settings if greyed)",
                            isGranted = PermissionHelper.isAccessibilityHelperEnabled(this@MainActivity),
                            onRequest = { PermissionHelper.openAccessibilitySettings(this@MainActivity) }
                        )
                    )
                }

                val allGranted = remember(permissionTick) {
                    PermissionHelper.areAllPermissionsGranted(this@MainActivity)
                }

                // Refresh lockscreen foreground notification & arm survivability watchdog when ready
                LaunchedEffect(allGranted, currentRoute) {
                    if (allGranted) {
                        PermissionHelper.ensureReceiversEnabled(this@MainActivity)
                        CallSchedulerService.refreshNotification(this@MainActivity)
                        CallDispatcher.scheduleWatchdogAlarm(this@MainActivity)
                    } else if (currentRoute != null && currentRoute != "onboarding") {
                        showConfigureSheet = false
                        editingSchedule = null
                        navController.navigate("onboarding") {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }

                val startDestination = remember {
                    if (PermissionHelper.areAllPermissionsGranted(this@MainActivity)) "dashboard" else "onboarding"
                }

                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it / 6 },
                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(220))
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 10 },
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(180))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 6 },
                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(220))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it / 6 },
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(180))
                    }
                ) {
                    composable("onboarding") {
                        OnboardingScreen(
                            permissionList = permissionList,
                            allPermissionsGranted = allGranted,
                            onProceedClicked = {
                                if (PermissionHelper.areAllPermissionsGranted(this@MainActivity)) {
                                    PermissionHelper.ensureReceiversEnabled(this@MainActivity)
                                    CallSchedulerService.refreshNotification(this@MainActivity)
                                    CallDispatcher.scheduleWatchdogAlarm(this@MainActivity)
                                    navController.navigate("dashboard") {
                                        popUpTo(0) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onExitClicked = {
                                finishAffinity()
                            }
                        )
                    }

                    composable("dashboard") {
                        val schedules by viewModel.allSchedules.collectAsState()
                        var selectedTab by remember { mutableStateOf(DashboardTab.ALL) }

                        AnimatedContent(
                            targetState = showConfigureSheet,
                            transitionSpec = {
                                if (targetState) {
                                    (slideInVertically(
                                        initialOffsetY = { it / 10 },
                                        animationSpec = tween(230, easing = FastOutSlowInEasing)
                                    ) + fadeIn(tween(200))) togetherWith
                                        fadeOut(tween(150))
                                } else {
                                    fadeIn(tween(200)) togetherWith
                                        (slideOutVertically(
                                            targetOffsetY = { it / 10 },
                                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                                        ) + fadeOut(tween(160)))
                                }
                            },
                            label = "DashboardConfigureTransition"
                        ) { configureVisible ->
                            if (configureVisible) {
                                BackHandler {
                                    showConfigureSheet = false
                                    editingSchedule = null
                                }
                                ConfigureScheduleScreen(
                                    editingSchedule = editingSchedule,
                                    refreshTick = permissionTick,
                                    onDismiss = {
                                        showConfigureSheet = false
                                        editingSchedule = null
                                    },
                                    onSaveSchedule = { newSchedule, callback ->
                                        viewModel.saveSchedule(newSchedule, callback)
                                    }
                                )
                            } else {
                                DashboardScreen(
                                    schedules = schedules,
                                    selectedTab = selectedTab,
                                    onTabSelected = { selectedTab = it },
                                    onAddScheduleClicked = {
                                        editingSchedule = null
                                        showConfigureSheet = true
                                    },
                                    onEditSchedule = { schedule ->
                                        editingSchedule = schedule
                                        showConfigureSheet = true
                                    },
                                    onTogglePause = { viewModel.togglePause(it) },
                                    onTestCall = { schedule ->
                                        CallDispatcher.executeCall(this@MainActivity, schedule)
                                    },
                                    onDelete = { viewModel.deleteSchedule(it) },
                                    onNavigatePermissions = { navController.navigate("onboarding") },
                                    themeMode = themeMode,
                                    onThemeModeChanged = { newMode ->
                                        themeMode = newMode
                                        uiPrefs.edit().putString("ui_theme_mode", newMode).apply()
                                    },
                                    onCloseApp = {
                                        finishAffinity()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDispatchIntent(intent)
    }

    private fun handleDispatchIntent(intent: Intent?) {
        val dispatchId = intent?.getLongExtra(CallDispatcher.EXTRA_DISPATCH_CALL_ID, -1L) ?: -1L
        if (dispatchId <= 0L) return
        intent?.removeExtra(CallDispatcher.EXTRA_DISPATCH_CALL_ID)
        lifecycleScope.launch {
            val schedule = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(applicationContext).callScheduleDao().getScheduleById(dispatchId)
            }
            if (schedule != null) {
                CallDispatcher.executeCall(applicationContext, schedule)
            }
        }
    }
}