package kmpworkshop.server

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
}
