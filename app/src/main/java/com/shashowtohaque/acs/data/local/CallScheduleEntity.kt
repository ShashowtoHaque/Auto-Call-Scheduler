package com.shashowtohaque.acs.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

enum class ScheduleStatus {
    PENDING,
    COMPLETED,
    FAILED,
    PAUSED
}

enum class SimSlot {
    SIM_1,
    SIM_2
}

enum class AudioRoute {
    HANDSET,
    SPEAKER
}

enum class ConfirmationMode {
    AUTOMATED,
    REMINDER
}

enum class RemindBeforeUnit {
    SECONDS,
    MINUTES,
    HOURS
}

enum class ReminderAlertType {
    SOUND,
    VIBRATION
}

enum class CallRepeatMode {
    NO_REPEAT,
    CUSTOM,
    UNLIMITED
}

enum class RepeatIntervalUnit {
    MINUTES,
    HOURS,
    DAYS,
    WEEKS,
    MONTHS,
    YEARS
}

@Entity(tableName = "call_schedules")
@TypeConverters(Converters::class)
data class CallScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val contactName: String,
    val phoneNumber: String,
    val targetTimeMillis: Long,
    val anchorTimeMillis: Long = targetTimeMillis,
    val simSlot: SimSlot = SimSlot.SIM_1,
    val audioRoute: AudioRoute = AudioRoute.HANDSET,
    val confirmationMode: ConfirmationMode = ConfirmationMode.AUTOMATED,
    val remindBeforeValue: Int = 5,
    val remindBeforeUnit: RemindBeforeUnit = RemindBeforeUnit.MINUTES,
    val reminderAlertType: ReminderAlertType = ReminderAlertType.SOUND,
    val autoHangupSeconds: Int = 45, // 0 = disabled, 5..60s (5s increment)
    val respectAirplaneMode: Boolean = true, // If true, fail when in Airplane Mode; if false, turn OFF & wait 10s
    val callRepeatMode: CallRepeatMode = CallRepeatMode.NO_REPEAT,
    val callRepeatCount: Int = 0, // Excludes initial call when CUSTOM (1..100 -> 2..101 total calls)
    val repeatIntervalValue: Int = 5, // Min 1 (1 min to 5 years)
    val repeatIntervalUnit: RepeatIntervalUnit = RepeatIntervalUnit.MINUTES,
    val repeatDaysOfWeek: List<Int> = emptyList(), // 1=Mon, ..., 7=Sun (UI ordered Sat..Fri)
    val currentRepeatCount: Int = 0,
    val inCallNotes: String = "",
    val status: ScheduleStatus = ScheduleStatus.PENDING,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastExecutedMillis: Long? = null,
    val failureReason: String? = null
) {
    fun hasScheduleRepeat(): Boolean =
        callRepeatMode != CallRepeatMode.NO_REPEAT || repeatDaysOfWeek.isNotEmpty()
}

class Converters {
    @TypeConverter
    fun fromStatus(value: ScheduleStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): ScheduleStatus = ScheduleStatus.valueOf(value)

    @TypeConverter
    fun fromSim(value: SimSlot): String = value.name

    @TypeConverter
    fun toSim(value: String): SimSlot = SimSlot.valueOf(value)

    @TypeConverter
    fun fromAudio(value: AudioRoute): String = value.name

    @TypeConverter
    fun toAudio(value: String): AudioRoute = AudioRoute.valueOf(value)

    @TypeConverter
    fun fromConfirmation(value: ConfirmationMode): String = value.name

    @TypeConverter
    fun toConfirmation(value: String): ConfirmationMode = ConfirmationMode.valueOf(value)

    @TypeConverter
    fun fromRemindUnit(value: RemindBeforeUnit): String = value.name

    @TypeConverter
    fun toRemindUnit(value: String): RemindBeforeUnit = RemindBeforeUnit.valueOf(value)

    @TypeConverter
    fun fromReminderAlertType(value: ReminderAlertType): String = value.name

    @TypeConverter
    fun toReminderAlertType(value: String): ReminderAlertType = ReminderAlertType.valueOf(value)

    @TypeConverter
    fun fromCallRepeatMode(value: CallRepeatMode): String = value.name

    @TypeConverter
    fun toCallRepeatMode(value: String): CallRepeatMode = CallRepeatMode.valueOf(value)

    @TypeConverter
    fun fromRepeatIntervalUnit(value: RepeatIntervalUnit): String = value.name

    @TypeConverter
    fun toRepeatIntervalUnit(value: String): RepeatIntervalUnit = RepeatIntervalUnit.valueOf(value)

    @TypeConverter
    fun fromDaysList(days: List<Int>): String = days.joinToString(",")

    @TypeConverter
    fun toDaysList(data: String): List<Int> {
        if (data.isBlank()) return emptyList()
        return data.split(",").mapNotNull { it.toIntOrNull() }
    }
}