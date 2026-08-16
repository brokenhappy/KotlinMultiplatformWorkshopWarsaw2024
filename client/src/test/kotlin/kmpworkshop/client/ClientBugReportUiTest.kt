package kmpworkshop.client

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kmpworkshop.common.ClientBugReportDraft
import kotlin.test.Test

class ClientBugReportUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `requires three reset clicks`() = runComposeUiTest {
        setContent {
            var draft by mutableStateOf(ClientBugReportDraft(description = "keep me"))
            MaterialTheme {
                ClientBugReportPage(
                    draft = draft,
                    settings = ClientSettings(),
                    onDraftChange = { draft = it },
                    onSubmit = { error("Not submitted in this test") },
                    onDismiss = {},
                    onReset = { draft = ClientBugReportDraft() },
                )
            }
        }

        val reset = onNodeWithTag("reset-bug-report-button")
        reset.performClick()
        onNodeWithText("Are you sure?").assertIsDisplayed()
        reset.performClick()
        onNodeWithText("Very very sure").assertIsDisplayed()
        reset.performClick()
        onNodeWithText("Bug report reset.").assertIsDisplayed()
    }
}
