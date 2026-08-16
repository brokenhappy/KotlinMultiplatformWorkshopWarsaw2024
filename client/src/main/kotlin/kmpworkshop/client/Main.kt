package kmpworkshop.client

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect

fun main() {
    val clientSettings = MutableStateFlow(loadClientSettings())

    application {
        LaunchedEffect(clientSettings) {
            clientSettings.drop(1).collect(::persistClientSettings)
        }

        Window(onCloseRequest = ::exitApplication, title = "Workshop Client") {
            var openReportBug by remember { mutableStateOf(false) }
            MenuBar {
                Menu("Help") {
                    Item("Report Bug", onClick = { openReportBug = true })
                }
            }
            MaterialTheme {
                ClientEntryPoint(
                    clientMetadata = defaultClientMetadata,
                    clientSettings = clientSettings,
                    onClientSettingsChange = { newSettings -> clientSettings.value = newSettings },
                    openReportBug = openReportBug,
                    onReportBugClosed = { openReportBug = false },
                )
            }
        }
    }
}
