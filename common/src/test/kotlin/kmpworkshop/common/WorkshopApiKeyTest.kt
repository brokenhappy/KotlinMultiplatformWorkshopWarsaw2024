package kmpworkshop.common

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkshopApiKeyTest {
    @Test
    fun `registration and client share credentials and can read an existing key`(@TempDir checkout: Path) {
        Files.createFile(checkout.resolve("settings.gradle.kts"))
        val registration = Files.createDirectory(checkout.resolve("registration"))
        val client = Files.createDirectory(checkout.resolve("client"))

        assertNull(readClientCredentials(client))
        Files.writeString(checkout.resolve(".workshop-api-key"), "legacy-fixture-key\n")
        val legacyCredentials = checkNotNull(readClientCredentials(client))
        assertEquals("legacy-fixture-key", legacyCredentials.apiKey)
        assertNull(legacyCredentials.invitationCode)

        saveClientCredentials(ApiKey("first-fixture-key"), "first-fixture-code", registration)
        val firstCredentials = checkNotNull(readClientCredentials(client))
        assertEquals("first-fixture-key", firstCredentials.apiKey)
        assertEquals("first-fixture-code", firstCredentials.invitationCode)
        assertFailsWith<IllegalArgumentException> {
            saveClientCredentials(ApiKey("invalid-fixture-key"), "code\non two lines", registration)
        }
        assertEquals("first-fixture-key", readClientCredentials(client)?.apiKey)

        saveClientCredentials(ApiKey("second-fixture-key"), " Łódź-✓ ", registration)
        val secondCredentials = checkNotNull(readClientCredentials(checkout))
        assertEquals("second-fixture-key", secondCredentials.apiKey)
        assertEquals("Łódź-✓", secondCredentials.invitationCode)
    }
}
