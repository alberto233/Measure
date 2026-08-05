package com.measure.feature.projects

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.measure.core.data.MeasureData
import com.measure.core.data.ProjectCatalogue
import com.measure.core.data.ProjectSort
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

    /**
     * What is typed into the search field, and how the list is ordered.
     *
     * Held here rather than in the composable so they survive a rotation and a trip into a
     * plan and back — coming back from a plan to a list that has forgotten what you were
     * looking for is the small betrayal that makes people stop using search.
     */
    var query by mutableStateOf("")
        private set

    var sort by mutableStateOf(ProjectSort.RECENT)
        private set

    fun search(text: String) {
        query = text
    }

    /**
     * Named `selectSort` rather than `setSort`, which would clash with the property's own
     * generated setter on the JVM. The two view models with a `mode` do the same.
     */
    fun selectSort(next: ProjectSort) {
        sort = next
    }

    /** The list as it should appear: filtered, then ordered. Null while still loading. */
    fun visible(all: List<ProjectSummary>?): List<ProjectSummary>? =
        all?.let { ProjectCatalogue.arrange(it, query, sort) }

    fun rename(projectId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameProject(projectId, trimmed) }
    }

    /** Sets the free-text label. Blank is allowed — it is how the field gets cleared. */
    fun setReference(projectId: Long, reference: String) {
        viewModelScope.launch { repository.setReference(projectId, reference) }
    }

    fun delete(projectId: Long) {
        viewModelScope.launch { repository.deleteProject(projectId) }
    }

    private companion object {
        /** Long enough to survive a rotation without re-querying. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
