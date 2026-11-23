package kmpworkshop.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminPasswordTest {
    @Test
    fun `missing and blank admin passwords are rejected`() {
        for (password in listOf(null, "", " \t")) {
            val failure = assertFailsWith<IllegalStateException> { requireAdminPassword(password) }
            assertEquals("ADMIN_PASSWORD must be set to a non-blank value.", failure.message)
        }
    }

    @Test
    fun `configured admin password is used verbatim`() {
        assertEquals(" fixture with spaces ", requireAdminPassword(" fixture with spaces "))
    }
}
