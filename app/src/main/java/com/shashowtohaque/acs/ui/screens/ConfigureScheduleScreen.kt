package com.shashowtohaque.acs.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shashowtohaque.acs.data.local.*
import com.shashowtohaque.acs.util.CallDispatcher
import com.shashowtohaque.acs.util.PermissionHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureScheduleScreen(
    editingSchedule: CallScheduleEntity? = null,
    onDismiss: () -> Unit,
    onSaveSchedule: (CallScheduleEntity, (conflictFound: Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val (sim1Operator, sim2Operator) = remember(context) { PermissionHelper.getSimOperatorNames(context) }

    var title by remember(editingSchedule) { mutableStateOf(editingSchedule?.title ?: "") }
    var contactName by remember(editingSchedule) { mutableStateOf(editingSchedule?.contactName ?: "") }
    var phoneNumber by remember(editingSchedule) { mutableStateOf(editingSchedule?.phoneNumber ?: "") }
    var targetTimeMillis by remember(editingSchedule) {
        val initialMillis = editingSchedule?.targetTimeMillis ?: Calendar.getInstance().apply {
            add(Calendar.MINUTE, 15)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        mutableStateOf(initialMillis)
    }
    var simSlot by remember(editingSchedule) { mutableStateOf(editingSchedule?.simSlot ?: SimSlot.SIM_1) }
    var audioRoute by remember(editingSchedule) { mutableStateOf(editingSchedule?.audioRoute ?: AudioRoute.HANDSET) }
    var confirmationMode by remember(editingSchedule) {
        mutableStateOf(editingSchedule?.confirmationMode ?: ConfirmationMode.AUTOMATED)
    }
    var remindBeforeText by remember(editingSchedule) {
        mutableStateOf((editingSchedule?.remindBeforeValue ?: 5).toString())
    }
    var remindBeforeUnit by remember(editingSchedule) {
        mutableStateOf(editingSchedule?.remindBeforeUnit ?: RemindBeforeUnit.MINUTES)
    }
    var reminderAlertType by remember(editingSchedule) {
        mutableStateOf(editingSchedule?.reminderAlertType ?: ReminderAlertType.SOUND)
    }
    var remindUnitMenuExpanded by remember { mutableStateOf(false) }

    var autoHangupSeconds by remember(editingSchedule) {
        mutableIntStateOf((editingSchedule?.autoHangupSeconds ?: 45).coerceIn(0, 60))
    }
    var respectAirplaneMode by remember(editingSchedule) { mutableStateOf(editingSchedule?.respectAirplaneMode ?: true) }
    var callRepeatMode by remember(editingSchedule) {
        mutableStateOf(editingSchedule?.callRepeatMode ?: CallRepeatMode.NO_REPEAT)
    }
    var callRepeatCount by remember(editingSchedule) {
        mutableIntStateOf(
            if (editingSchedule != null && editingSchedule.callRepeatCount > 0) editingSchedule.callRepeatCount else 3
        )
    }
    var repeatIntervalUnit by remember(editingSchedule) {
        mutableStateOf(editingSchedule?.repeatIntervalUnit ?: RepeatIntervalUnit.MINUTES)
    }
    var repeatIntervalValue by remember(editingSchedule) {
        mutableIntStateOf((editingSchedule?.repeatIntervalValue ?: 5).coerceAtLeast(1))
    }
    var repeatDaysOfWeek by remember(editingSchedule) {
        mutableStateOf(editingSchedule?.repeatDaysOfWeek ?: emptyList())
    }
    var inCallNotes by remember(editingSchedule) { mutableStateOf(editingSchedule?.inCallNotes ?: "") }

    var conflictErrorMessage by remember { mutableStateOf<String?>(null) }

    val hasWeekdays = repeatDaysOfWeek.isNotEmpty()
    val effectiveRepeatCount = if (callRepeatMode == CallRepeatMode.CUSTOM) callRepeatCount else 0
    val intervalBounds = CallDispatcher.getEffectiveIntervalBounds(
        unit = repeatIntervalUnit,
        repeatMode = callRepeatMode,
        repeatCount = effectiveRepeatCount,
        hasWeekdays = hasWeekdays
    )

    val onRepeatModeChanged: (CallRepeatMode) -> Unit = { mode ->
        callRepeatMode = mode
        conflictErrorMessage = null
        if (mode == CallRepeatMode.UNLIMITED) {
            repeatDaysOfWeek = emptyList()
            val (uMin, uMax) = CallDispatcher.getUnitRange(repeatIntervalUnit)
            repeatIntervalValue = repeatIntervalValue.coerceIn(uMin, uMax)
        } else if (mode == CallRepeatMode.CUSTOM) {
            val safeCount = callRepeatCount.coerceIn(1, 100)
            callRepeatCount = safeCount
            if (repeatDaysOfWeek.isNotEmpty()) {
                val bounds = CallDispatcher.getEffectiveIntervalBounds(repeatIntervalUnit, CallRepeatMode.CUSTOM, safeCount, true)
                if (!bounds.allowed) {
                    repeatIntervalUnit = RepeatIntervalUnit.MINUTES
                    val minBounds = CallDispatcher.getEffectiveIntervalBounds(RepeatIntervalUnit.MINUTES, CallRepeatMode.CUSTOM, safeCount, true)
                    repeatIntervalValue = repeatIntervalValue.coerceIn(1, minBounds.max)
                } else {
                    repeatIntervalValue = repeatIntervalValue.coerceIn(bounds.min, bounds.max)
                }
            }
        }
    }

    val onRepeatCountChanged: (Int) -> Unit = { newVal ->
        val safeCount = newVal.coerceIn(1, 100)
        callRepeatCount = safeCount
        if (repeatDaysOfWeek.isNotEmpty()) {
            val bounds = CallDispatcher.getEffectiveIntervalBounds(repeatIntervalUnit, CallRepeatMode.CUSTOM, safeCount, true)
            if (!bounds.allowed) {
                repeatIntervalUnit = RepeatIntervalUnit.MINUTES
                val minBounds = CallDispatcher.getEffectiveIntervalBounds(RepeatIntervalUnit.MINUTES, CallRepeatMode.CUSTOM, safeCount, true)
                repeatIntervalValue = repeatIntervalValue.coerceIn(1, minBounds.max)
            } else if (repeatIntervalValue > bounds.max) {
                repeatIntervalValue = bounds.max
            }
        }
    }

    val onIntervalUnitChanged: (RepeatIntervalUnit) -> Unit = { unit ->
        repeatIntervalUnit = unit
        conflictErrorMessage = null
        val (uMin, uMax) = CallDispatcher.getUnitRange(unit)
        if (!CallDispatcher.isWeekdayCompatibleUnit(unit)) {
            repeatDaysOfWeek = emptyList()
            repeatIntervalValue = repeatIntervalValue.coerceIn(uMin, uMax)
        } else {
            val bounds = CallDispatcher.getEffectiveIntervalBounds(unit, callRepeatMode, effectiveRepeatCount, hasWeekdays)
            if (!bounds.allowed) {
                repeatDaysOfWeek = emptyList()
                repeatIntervalValue = repeatIntervalValue.coerceIn(uMin, uMax)
            } else {
                repeatIntervalValue = repeatIntervalValue.coerceIn(bounds.min, bounds.max)
            }
        }
    }

    val onToggleWeekday: (Int) -> Unit = { day ->
        conflictErrorMessage = null
        if (repeatDaysOfWeek.contains(day)) {
            repeatDaysOfWeek = repeatDaysOfWeek.filter { it != day }
        } else {
            repeatDaysOfWeek = repeatDaysOfWeek + day
            var nextMode = callRepeatMode
            if (nextMode == CallRepeatMode.UNLIMITED) {
                nextMode = CallRepeatMode.NO_REPEAT
                callRepeatMode = CallRepeatMode.NO_REPEAT
            }
            if (nextMode == CallRepeatMode.CUSTOM) {
                val bounds = CallDispatcher.getEffectiveIntervalBounds(repeatIntervalUnit, CallRepeatMode.CUSTOM, callRepeatCount, true)
                if (!bounds.allowed) {
                    repeatIntervalUnit = RepeatIntervalUnit.MINUTES
                    val minBounds = CallDispatcher.getEffectiveIntervalBounds(RepeatIntervalUnit.MINUTES, CallRepeatMode.CUSTOM, callRepeatCount, true)
                    repeatIntervalValue = repeatIntervalValue.coerceIn(1, minBounds.max)
                } else if (repeatIntervalValue > bounds.max) {
                    repeatIntervalValue = bounds.max
                }
            } else if (!CallDispatcher.isWeekdayCompatibleUnit(repeatIntervalUnit)) {
                repeatIntervalUnit = RepeatIntervalUnit.MINUTES
                repeatIntervalValue = 5
            }
        }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) {
            try {
                context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                        val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        val contactId = if (idIdx >= 0) cursor.getString(idIdx) else null
                        if (nameIdx >= 0) {
                            val pickedName = cursor.getString(nameIdx) ?: ""
                            contactName = pickedName
                            if (title.isBlank() && pickedName.isNotBlank()) {
                                title = "Call $pickedName"
                            }
                            conflictErrorMessage = null
                        }
                        if (contactId != null) {
                            context.contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                null,
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId),
                                null
                            )?.use { phoneCursor ->
                                if (phoneCursor.moveToFirst()) {
                                    val numIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                    if (numIdx >= 0) {
                                        phoneNumber = phoneCursor.getString(numIdx) ?: ""
                                        conflictErrorMessage = null
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: SecurityException) {
                conflictErrorMessage = "Contacts permission is required to read device contacts."
            }
        }
    }

    val submitSchedule = {
        val parsedRemind = remindBeforeText.toIntOrNull() ?: 0
        val burstMinutes = if (repeatDaysOfWeek.isNotEmpty() && callRepeatMode == CallRepeatMode.CUSTOM) {
            callRepeatCount * (if (repeatIntervalUnit == RepeatIntervalUnit.HOURS) repeatIntervalValue * 60 else repeatIntervalValue)
        } else 0

        when {
            title.isBlank() || phoneNumber.isBlank() -> {
                conflictErrorMessage = "Please enter Title and Phone Number."
            }
            confirmationMode == ConfirmationMode.REMINDER && parsedRemind <= 0 -> {
                conflictErrorMessage = "Please enter a valid Remind Before duration greater than 0."
            }
            repeatDaysOfWeek.isNotEmpty() && callRepeatMode == CallRepeatMode.CUSTOM &&
                (burstMinutes >= 24 * 60 || !CallDispatcher.isWeekdayCompatibleUnit(repeatIntervalUnit)) -> {
                conflictErrorMessage = "When Repeat on Weekdays is active, daily call repeats must finish within 24 hours."
            }
            else -> {
                val alignedTargetMillis = if (repeatDaysOfWeek.isNotEmpty()) {
                    CallDispatcher.alignToFirstMatchingWeekday(targetTimeMillis, repeatDaysOfWeek)
                } else {
                    targetTimeMillis
                }
                val trimmedTitle = title.trim()
                val trimmedContact = contactName.trim()
                val trimmedPhone = phoneNumber.trim()
                val schedule = CallScheduleEntity(
                    id = editingSchedule?.id ?: 0L,
                    title = trimmedTitle,
                    contactName = trimmedContact.ifBlank { trimmedPhone },
                    phoneNumber = trimmedPhone,
                    targetTimeMillis = alignedTargetMillis,
                    anchorTimeMillis = alignedTargetMillis,
                    simSlot = simSlot,
                    audioRoute = audioRoute,
                    confirmationMode = confirmationMode,
                    remindBeforeValue = if (parsedRemind > 0) parsedRemind else 5,
                    remindBeforeUnit = remindBeforeUnit,
                    reminderAlertType = reminderAlertType,
                    autoHangupSeconds = autoHangupSeconds.coerceIn(0, 60),
                    respectAirplaneMode = respectAirplaneMode,
                    callRepeatMode = callRepeatMode,
                    callRepeatCount = if (callRepeatMode == CallRepeatMode.CUSTOM) callRepeatCount else 0,
                    repeatIntervalValue = repeatIntervalValue.coerceAtLeast(1),
                    repeatIntervalUnit = repeatIntervalUnit,
                    repeatDaysOfWeek = repeatDaysOfWeek,
                    currentRepeatCount = 0,
                    inCallNotes = inCallNotes.trim(),
                    status = ScheduleStatus.PENDING,
                    createdAtMillis = editingSchedule?.createdAtMillis ?: System.currentTimeMillis(),
                    failureReason = null
                )
                onSaveSchedule(schedule) { conflictFound ->
                    if (conflictFound) {
                        conflictErrorMessage = "Schedule Conflict Detected! An existing pending call schedule already exists at this exact execution timeline (+/- 3 min). Please adjust time."
                    } else {
                        onDismiss()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Configure Schedule", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = submitSchedule) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            conflictErrorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Title
            ElevatedCard(shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TITLE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            conflictErrorMessage = null
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Contact Name & Phone Number + right-side full-height Device Contacts button
            ElevatedCard(shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CONTACT NAME & PHONE NUMBER",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = contactName,
                                onValueChange = { contactName = it },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = phoneNumber,
                                onValueChange = {
                                    phoneNumber = it
                                    conflictErrorMessage = null
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Phone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = { contactPickerLauncher.launch(null) },
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(120.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.ContactPhone, contentDescription = null)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Add From Device Contacts",
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Initialization Date & Time
            ElevatedCard(shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "INITIALIZATION DATE & TIME",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val dateFmt = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
                    val timeFmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedCard(
                            onClick = {
                                val cal = Calendar.getInstance().apply { timeInMillis = targetTimeMillis }
                                DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        val updated = Calendar.getInstance().apply {
                                            timeInMillis = targetTimeMillis
                                            set(Calendar.YEAR, year)
                                            set(Calendar.MONTH, month)
                                            set(Calendar.DAY_OF_MONTH, day)
                                            set(Calendar.SECOND, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }
                                        targetTimeMillis = updated.timeInMillis
                                        conflictErrorMessage = null
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = dateFmt.format(targetTimeMillis), fontWeight = FontWeight.SemiBold)
                            }
                        }

                        OutlinedCard(
                            onClick = {
                                val cal = Calendar.getInstance().apply { timeInMillis = targetTimeMillis }
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute ->
                                        val updated = Calendar.getInstance().apply {
                                            timeInMillis = targetTimeMillis
                                            set(Calendar.HOUR_OF_DAY, hour)
                                            set(Calendar.MINUTE, minute)
                                            set(Calendar.SECOND, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }
                                        targetTimeMillis = updated.timeInMillis
                                        conflictErrorMessage = null
                                    },
                                    cal.get(Calendar.HOUR_OF_DAY),
                                    cal.get(Calendar.MINUTE),
                                    false
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = timeFmt.format(targetTimeMillis), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // CALL REPEAT COUNT
            ElevatedCard(shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CALL REPEAT COUNT",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (callRepeatMode != CallRepeatMode.NO_REPEAT) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = when (callRepeatMode) {
                                    CallRepeatMode.NO_REPEAT -> "No Repeat"
                                    CallRepeatMode.UNLIMITED -> "No Limit"
                                    CallRepeatMode.CUSTOM -> "${callRepeatCount}x"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (callRepeatMode != CallRepeatMode.NO_REPEAT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            CallRepeatMode.NO_REPEAT to "No Repeat",
                            CallRepeatMode.CUSTOM to "X Time(s)",
                            CallRepeatMode.UNLIMITED to "No Limit"
                        ).forEach { (mode, label) ->
                            val selected = callRepeatMode == mode
                            Surface(
                                onClick = { onRepeatModeChanged(mode) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    if (callRepeatMode == CallRepeatMode.CUSTOM) {
                        Slider(
                            value = callRepeatCount.toFloat(),
                            onValueChange = { onRepeatCountChanged(it.toInt()) },
                            valueRange = 1f..100f,
                            steps = 98
                        )
                    }

                }
            }

            // SCHEDULE REPEAT INTERVAL
            ElevatedCard(shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "SCHEDULE REPEAT INTERVAL",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (callRepeatMode != CallRepeatMode.NO_REPEAT) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Repeat Interval", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            text = if (callRepeatMode == CallRepeatMode.NO_REPEAT) {
                                "Disabled"
                            } else {
                                CallDispatcher.formatIntervalText(repeatIntervalValue, repeatIntervalUnit)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (callRepeatMode != CallRepeatMode.NO_REPEAT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (callRepeatMode == CallRepeatMode.NO_REPEAT) {
                        Text(
                            text = "Select \"X Time(s)\" or \"No Limit\" above to set an interval between calls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        val unitRows = listOf(
                            listOf(RepeatIntervalUnit.MINUTES to "Minutes", RepeatIntervalUnit.HOURS to "Hours", RepeatIntervalUnit.DAYS to "Days"),
                            listOf(RepeatIntervalUnit.WEEKS to "Weeks", RepeatIntervalUnit.MONTHS to "Months", RepeatIntervalUnit.YEARS to "Years")
                        )
                        unitRows.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowItems.forEach { (unit, label) ->
                                    val selected = repeatIntervalUnit == unit
                                    Surface(
                                        onClick = { onIntervalUnitChanged(unit) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 7.dp, horizontal = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (intervalBounds.max > intervalBounds.min) {
                            val safeSliderVal = repeatIntervalValue.coerceIn(intervalBounds.min, intervalBounds.max)
                            Slider(
                                value = safeSliderVal.toFloat(),
                                onValueChange = { repeatIntervalValue = it.toInt().coerceIn(intervalBounds.min, intervalBounds.max) },
                                valueRange = intervalBounds.min.toFloat()..intervalBounds.max.toFloat(),
                                steps = if (intervalBounds.max - intervalBounds.min > 1) (intervalBounds.max - intervalBounds.min - 1) else 0
                            )
                        }
                    }

                    HorizontalDivider()

                    // Days of Week row (Sat to Fri)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasWeekdays) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Repeat on Weekdays", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CallDispatcher.WEEKDAYS_SAT_TO_FRI.forEach { (dayNum, shortLabel) ->
                            val selected = repeatDaysOfWeek.contains(dayNum)
                            Surface(
                                onClick = { onToggleWeekday(dayNum) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = shortLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Live Schedule Summary Card
                    val summaryDateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
                    val summaryTimeFormatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
                    val summaryDateStr = summaryDateFormatter.format(targetTimeMillis)
                    val summaryTimeStr = summaryTimeFormatter.format(targetTimeMillis)
                    val previewSummary = when {
                        callRepeatMode == CallRepeatMode.NO_REPEAT && !hasWeekdays ->
                            "One-time call: dials once on $summaryDateStr at $summaryTimeStr."
                        callRepeatMode == CallRepeatMode.NO_REPEAT && hasWeekdays ->
                            "Weekly schedule: dials once at $summaryTimeStr every ${CallDispatcher.formatWeekdaysSatToFri(repeatDaysOfWeek)}."
                        callRepeatMode == CallRepeatMode.CUSTOM && !hasWeekdays ->
                            "Dials ${callRepeatCount + 1} times total (initial + $callRepeatCount ${if (callRepeatCount == 1) "repeat" else "repeats"}) ${CallDispatcher.formatIntervalText(repeatIntervalValue, repeatIntervalUnit).lowercase()} starting $summaryDateStr at $summaryTimeStr."
                        callRepeatMode == CallRepeatMode.CUSTOM && hasWeekdays ->
                            "Repeats every ${CallDispatcher.formatWeekdaysSatToFri(repeatDaysOfWeek)} at $summaryTimeStr: ${callRepeatCount + 1} calls/day ${CallDispatcher.formatIntervalText(repeatIntervalValue, repeatIntervalUnit).lowercase()}."
                        else ->
                            "Dials infinitely ${CallDispatcher.formatIntervalText(repeatIntervalValue, repeatIntervalUnit).lowercase()} starting $summaryDateStr at $summaryTimeStr."
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "SCHEDULE SUMMARY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = previewSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Target SIM Card with centered two-line layout & real operator names
            ElevatedCard(shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TARGET SIM CARD",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        listOf(
                            Triple(SimSlot.SIM_1, "SIM 1", sim1Operator),
                            Triple(SimSlot.SIM_2, "SIM 2", sim2Operator)
                        ).forEach { (slot, label, operatorName) ->
                            val selected = simSlot == slot
                            Surface(
                                onClick = { simSlot = slot },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SimCard,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    Text(
                                        text = operatorName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Call Behavior & Audio Settings card grouping matching preview
            ElevatedCard(
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "CALL BEHAVIOR & AUDIO SETTINGS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Audio Routing
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Audio Routing",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            listOf(
                                Triple(AudioRoute.HANDSET, "Handset / Earpiece", Icons.Default.Headphones),
                                Triple(AudioRoute.SPEAKER, "Loudspeaker", Icons.Default.VolumeUp)
                            ).forEach { (route, label, icon) ->
                                val selected = audioRoute == route
                                Surface(
                                    onClick = { audioRoute = route },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Call Execution
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Call Execution",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            listOf(
                                ConfirmationMode.AUTOMATED to "Direct Dial",
                                ConfirmationMode.REMINDER to "Reminder"
                            ).forEach { (mode, label) ->
                                val selected = confirmationMode == mode
                                Surface(
                                    onClick = { confirmationMode = mode },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = if (mode == ConfirmationMode.AUTOMATED) Icons.Default.PhoneForwarded else Icons.Default.Notifications,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Conditional Remind Before input + unit dropdown
                    if (confirmationMode == ConfirmationMode.REMINDER) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Remind Before",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = remindBeforeText,
                                    onValueChange = { input ->
                                        remindBeforeText = input.filter { it.isDigit() }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                val unitDisplay = when (remindBeforeUnit) {
                                    RemindBeforeUnit.SECONDS -> "Seconds"
                                    RemindBeforeUnit.MINUTES -> "Minutes"
                                    RemindBeforeUnit.HOURS -> "Hours"
                                }

                                ExposedDropdownMenuBox(
                                    expanded = remindUnitMenuExpanded,
                                    onExpandedChange = { remindUnitMenuExpanded = !remindUnitMenuExpanded },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = unitDisplay,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = remindUnitMenuExpanded) },
                                        modifier = Modifier
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                            .fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = remindUnitMenuExpanded,
                                        onDismissRequest = { remindUnitMenuExpanded = false }
                                    ) {
                                        listOf(
                                            RemindBeforeUnit.SECONDS to "Seconds",
                                            RemindBeforeUnit.MINUTES to "Minutes",
                                            RemindBeforeUnit.HOURS to "Hours"
                                        ).forEach { (unit, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    remindBeforeUnit = unit
                                                    remindUnitMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                listOf(
                                    ReminderAlertType.SOUND to "Sound",
                                    ReminderAlertType.VIBRATION to "Vibration"
                                ).forEach { (alertType, label) ->
                                    val selected = reminderAlertType == alertType
                                    Surface(
                                        onClick = { reminderAlertType = alertType },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp, horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = if (alertType == ReminderAlertType.SOUND) Icons.Default.VolumeUp else Icons.Default.Vibration,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Auto Hang-up After Slider (0-60s, 5s increment, default 45s)
                    Column {
                        val hangupEnabled = autoHangupSeconds > 0
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (hangupEnabled) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = "Auto Hang-up After",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = if (hangupEnabled) "$autoHangupSeconds seconds" else "Disabled",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hangupEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(
                            value = autoHangupSeconds.toFloat(),
                            onValueChange = { autoHangupSeconds = (((it + 2.5f) / 5f).toInt() * 5).coerceIn(0, 60) },
                            valueRange = 0f..60f,
                            steps = 11
                        )
                    }

                    HorizontalDivider()

                    // Respect Airplane Mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { respectAirplaneMode = !respectAirplaneMode }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AirplanemodeActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Respect Airplane Mode",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Checkbox(
                            checked = respectAirplaneMode,
                            onCheckedChange = { respectAirplaneMode = it }
                        )
                    }
                }
            }

            // In-Call Note
            ElevatedCard(shape = RoundedCornerShape(14.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "IN-CALL NOTE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = inCallNotes,
                        onValueChange = { inCallNotes = it },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Button(
                onClick = submitSchedule,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (editingSchedule != null) "Save Changes" else "Save & Schedule",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}