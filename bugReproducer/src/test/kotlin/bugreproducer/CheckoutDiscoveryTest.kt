package bugreproducer

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckoutDiscoveryTest {
    @Test
    fun `manager resolves repository root when started below the module directory`() {
        val root = Files.createTempDirectory("bug-reproducer-checkout")
        val module = root.resolve("bugReproducer").also { it.createDirectories() }
        root.resolve(".git").writeText("gitdir: metadata")

        assertEquals(root.toAbsolutePath().normalize(), findGitCheckout(module))
    }
}
