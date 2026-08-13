package workshop.adminaccess

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Backup(val instant: Instant, val initial: ServerState, val events: List<TimedEvent>)