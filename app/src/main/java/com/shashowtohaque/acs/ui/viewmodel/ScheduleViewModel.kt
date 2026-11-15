package com.shashowtohaque.acs.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shashowtohaque.acs.data.local.AppDatabase
import com.shashowtohaque.acs.data.local.CallScheduleEntity
import com.shashowtohaque.acs.data.local.ScheduleStatus
import com.shashowtohaque.acs.data.repository.ScheduleRepository
import com.shashowtohaque.acs.service.CallSchedulerService
import com.shashowtohaque.acs.util.CallDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ScheduleRepository

    val allSchedules: StateFlow<List<CallScheduleEntity>>
    val pendingSchedules: StateFlow<List<CallScheduleEntity>>
    val completedSchedules: StateFlow<List<CallScheduleEntity>>
    val failedSchedules: StateFlow<List<CallScheduleEntity>>

    val pendingCount: StateFlow<Int>
    val completedCount: StateFlow<Int>
    val failedCount: StateFlow<Int>

    init {
        val dao = AppDatabase.getDatabase(application).callScheduleDao()
        repository = ScheduleRepository(dao)

        allSchedules = repository.allSchedules.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        pendingSchedules = repository.pendingSchedules.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        completedSchedules = repository.completedSchedules.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        failedSchedules = repository.failedSchedules.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

        pendingCount = repository.pendingCount.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), 0
        )
        completedCount = repository.completedCount.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), 0
        )
        failedCount = repository.failedCount.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), 0
        )

        // Ensure Foreground Service is started on boot
        CallSchedulerService.startService(application)
    }

    /**
     * Strict Conflict Check & Save
     */
    fun saveSchedule(schedule: CallScheduleEntity, onResult: (hasConflict: Boolean) -> Unit) {
        viewModelScope.launch {
            val conflict = repository.checkConflict(schedule.targetTimeMillis, schedule.id)
            if (conflict != null) {
                onResult(true)
            } else {
                val insertedId = repository.insertOrUpdate(schedule)
                val finalSchedule = schedule.copy(id = insertedId)
                CallDispatcher.scheduleAlarm(getApplication<Application>(), finalSchedule)
                onResult(false)
            }
        }
    }

    fun togglePause(schedule: CallScheduleEntity) {
        viewModelScope.launch {
            if (schedule.status == ScheduleStatus.PAUSED) {
                CallDispatcher.scheduleAlarm(getApplication<Application>(), schedule)
            } else {
                CallDispatcher.cancelAlarm(getApplication<Application>(), schedule.id)
            }
            repository.togglePause(schedule)
        }
    }

    fun deleteSchedule(schedule: CallScheduleEntity) {
        viewModelScope.launch {
            CallDispatcher.cancelAlarm(getApplication<Application>(), schedule.id)
            repository.deleteSchedule(schedule)
        }
    }
}