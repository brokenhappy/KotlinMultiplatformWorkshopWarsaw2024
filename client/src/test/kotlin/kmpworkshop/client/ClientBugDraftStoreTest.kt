package kmpworkshop.client

import kmpworkshop.common.BugImageAttachment
import kmpworkshop.common.ClientBugReportDraft
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientBugDraftStoreTest {
    @Test
    fun `draft survives save and load and can be cleared`() {
        val directory = createTempDirectory("bug-draft-test")
        val file = directory.resolve("draft.json").toFile()
        val store = ClientBugDraftStore(file)
        val draft = ClientBugReportDraft(
            description = "The puzzle got stuck",
            attachments = listOf(BugImageAttachment("image.png", "image/png", "aGVsbG8=")),
        )

        store.save(draft)
        assertEquals(draft, store.load())
        store.clear()
        assertEquals(ClientBugReportDraft(), store.load())
    }
}
