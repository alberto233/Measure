package com.measure.feature.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.measure.core.data.MeasureData
import com.measure.core.data.ProjectSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeasureData.repository(application)

    /**
     * Straight from the database, so the list is correct the moment a capture saves
     * rather than the next time the screen is opened. `null` means "not loaded yet",
     * which the screen renders differently from a genuinely empty list — showing "no
     * plans yet" for a frame before the real list arrives would be a lie, briefly.
     */
    val projects: StateFlow<List<ProjectSummary>?> = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun rename(projectId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameProject(projectId, trimmed) }
    }

    fun delete(projectId: Long) {
        viewModelScope.launch { repository.deleteProject(projectId) }
    }

    private companion object {
        /** Long enough to survive a rotation without re-querying. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
