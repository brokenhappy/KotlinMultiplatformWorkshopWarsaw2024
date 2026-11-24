package kmpworkshop.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import workshop.adminaccess.PendingBug
import workshop.adminaccess.id

/** Holds reports until an admin has saved and acknowledged each one. */
class PendingBugReports {
    private val pending = MutableStateFlow<List<PendingBug>>(emptyList())

    /** Adds a report even when no admin is connected. */
    fun add(report: PendingBug) {
        pending.update { it + report }
    }

    /** Confirms the last saved report and waits for the oldest remaining report. */
    suspend fun receiveNext(lastSavedBugId: String? = null): PendingBug {
        lastSavedBugId?.let(::acknowledge)
        return pending.first { it.isNotEmpty() }.first()
    }

    private fun acknowledge(id: String) {
        while (true) {
            val current = pending.value
            if (current.firstOrNull()?.id != id) return
            if (pending.compareAndSet(current, current.drop(1))) return
        }
    }
}
