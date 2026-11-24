package kmpworkshop.server

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import workshop.adminaccess.ServerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminAccessPasswordTest {
    @Test
    fun `requires a non-blank configured password`() {
        for (password in listOf(null, "", " \t\n", "\u2003")) {
            val failure = assertFailsWith<IllegalStateException> { requireAdminAccessPassword(password) }
            assertEquals("admin_access_password must be set to a non-blank value.", failure.message)
        }
    }

    @Test
    fun `preserves the configured password exactly`() {
        assertEquals(" test-admin-password ", requireAdminAccessPassword(" test-admin-password "))
    }

    @Test
    fun `bug downloads and acknowledgements require the admin password`() = runTest {
        val admin = adminAccess(
            "secret",
            flowOf(ServerState()),
            { error("Unexpected event") },
            emptyFlow(),
            PendingBugReports(),
        )

        assertFailsWith<IllegalStateException> { admin.receiveNextBug("wrong", null) }
        assertFailsWith<IllegalStateException> { admin.receiveNextBug("wrong", "id") }
    }
}
