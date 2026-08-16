package kmpworkshop.client

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
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
            MaterialTheme {
                ClientEntryPoint(
                    clientMetadata = defaultClientMetadata,
                    clientSettings = clientSettings,
                    onClientSettingsChange = { newSettings -> clientSettings.value = newSettings },
                )
            }
        }
    }
}
