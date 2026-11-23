package kmpworkshop.common

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkshopApiKeyTest {
    @Test
    fun `registration and client use the same local key file`(@TempDir checkout: Path) {
        Files.createFile(checkout.resolve("settings.gradle.kts"))
        val registration = Files.createDirectory(checkout.resolve("registration"))
        val client = Files.createDirectory(checkout.resolve("client"))

        assertNull(readClientApiKey(client))
        saveClientApiKey(ApiKey("first-fixture-key"), registration)
        assertEquals("first-fixture-key", readClientApiKey(client))
        saveClientApiKey(ApiKey("second-fixture-key"), registration)
        assertEquals("second-fixture-key", readClientApiKey(checkout))
    }
}
