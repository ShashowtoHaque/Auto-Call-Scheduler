package com.shashowtohaque.acs.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CallScheduleDao {

    @Query("SELECT * FROM call_schedules ORDER BY targetTimeMillis ASC")
    fun getAllSchedules(): Flow<List<CallScheduleEntity>>

    @Query("SELECT * FROM call_schedules WHERE status IN ('PENDING', 'PAUSED') ORDER BY targetTimeMillis ASC")
    fun getPendingSchedules(): Flow<List<CallScheduleEntity>>

    @Query("SELECT * FROM call_schedules WHERE status = 'COMPLETED' ORDER BY lastExecutedMillis DESC")
    fun getCompletedSchedules(): Flow<List<CallScheduleEntity>>

    @Query("SELECT * FROM call_schedules WHERE status = 'FAILED' ORDER BY targetTimeMillis DESC")
    fun getFailedSchedules(): Flow<List<CallScheduleEntity>>

    @Query("SELECT * FROM call_schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: Long): CallScheduleEntity?

    @Query("""
        SELECT * FROM call_schedules 
        WHERE status = 'PENDING'
          AND id != :excludeId
          AND (targetTimeMillis BETWEEN :windowStart AND :windowEnd)
        LIMIT 1
    """)
    suspend fun findConflictingSchedule(
        excludeId: Long,
        windowStart: Long,
        windowEnd: Long
    ): CallScheduleEntity?

    @Query("SELECT COUNT(*) FROM call_schedules WHERE status = 'PENDING'")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM call_schedules WHERE status = 'COMPLETED'")
    fun getCompletedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM call_schedules WHERE status = 'FAILED'")
    fun getFailedCount(): Flow<Int>

    @Query("SELECT * FROM call_schedules WHERE status = 'PENDING' ORDER BY targetTimeMillis ASC LIMIT 1")
    fun getNextPendingSchedule(): Flow<CallScheduleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: CallScheduleEntity): Long

    @Update
    suspend fun updateSchedule(schedule: CallScheduleEntity)

    @Delete
    suspend fun deleteSchedule(schedule: CallScheduleEntity)

    @Query("UPDATE call_schedules SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ScheduleStatus)
}