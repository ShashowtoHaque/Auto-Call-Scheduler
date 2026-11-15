package com.shashowtohaque.acs.data.repository

import com.shashowtohaque.acs.data.local.CallScheduleDao
import com.shashowtohaque.acs.data.local.CallScheduleEntity
import com.shashowtohaque.acs.data.local.ScheduleStatus
import kotlinx.coroutines.flow.Flow

class ScheduleRepository(private val dao: CallScheduleDao) {

    val allSchedules: Flow<List<CallScheduleEntity>> = dao.getAllSchedules()
    val pendingSchedules: Flow<List<CallScheduleEntity>> = dao.getPendingSchedules()
    val completedSchedules: Flow<List<CallScheduleEntity>> = dao.getCompletedSchedules()
    val failedSchedules: Flow<List<CallScheduleEntity>> = dao.getFailedSchedules()

    val pendingCount: Flow<Int> = dao.getPendingCount()
    val completedCount: Flow<Int> = dao.getCompletedCount()
    val failedCount: Flow<Int> = dao.getFailedCount()
    val nextPendingSchedule: Flow<CallScheduleEntity?> = dao.getNextPendingSchedule()

    suspend fun getScheduleById(id: Long): CallScheduleEntity? = dao.getScheduleById(id)

    /**
     * Strict Conflict Protection:
     * Check if another pending schedule exists within +/- 3 minutes of target execution time
     */
    suspend fun checkConflict(targetTimeMillis: Long, excludeId: Long = 0): CallScheduleEntity? {
        val windowMargin = 3 * 60 * 1000L // 3 minutes buffer
        val start = targetTimeMillis - windowMargin
        val end = targetTimeMillis + windowMargin
        return dao.findConflictingSchedule(excludeId, start, end)
    }

    suspend fun insertOrUpdate(schedule: CallScheduleEntity): Long {
        return dao.insertSchedule(schedule)
    }

    suspend fun updateSchedule(schedule: CallScheduleEntity) {
        dao.updateSchedule(schedule)
    }

    suspend fun deleteSchedule(schedule: CallScheduleEntity) {
        dao.deleteSchedule(schedule)
    }

    suspend fun togglePause(schedule: CallScheduleEntity) {
        val newStatus = if (schedule.status == ScheduleStatus.PAUSED) {
            ScheduleStatus.PENDING
        } else {
            ScheduleStatus.PAUSED
        }
        dao.updateStatus(schedule.id, newStatus)
    }
}