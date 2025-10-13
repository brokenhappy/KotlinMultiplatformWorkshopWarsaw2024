package workshop.adminaccess

import kotlinx.coroutines.flow.Flow

interface AdminAccess {
    fun serverState(): Flow<ServerState>
}