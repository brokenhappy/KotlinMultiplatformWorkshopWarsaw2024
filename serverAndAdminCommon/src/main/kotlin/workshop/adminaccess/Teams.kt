package workshop.adminaccess

import kmpworkshop.common.ApiKey
import kotlinx.serialization.Serializable

@Serializable
enum class TeamColor { Red, Blue, Green, Yellow, Orange }

@Serializable
data class TeamAssignmentChange(val apiKey: ApiKey, val team: TeamColor) : ServerWideEvents()

@Serializable
data object AddTeam : ServerWideEvents()

@Serializable
data object RemoveTeam : ServerWideEvents()
