package kmpworkshop.common

import kotlin.test.Test
import kotlin.test.assertFails

class AdminPasswordTest {
    @Test
    fun assertSecretPasswordIsNotAccidentallyPersisted() {
        assertFails { superSecretFallbackPassword() }
    }
}
