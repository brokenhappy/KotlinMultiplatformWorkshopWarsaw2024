@file:Suppress("FunctionName")

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.client.workshopService
import kmpworkshop.common.ApiKey
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey

internal fun WorkshopApp(title: String, mainUi: @Composable (WorkshopServer) -> Unit) {
    application {
        Window(onCloseRequest = ::exitApplication, title = title) {
            MaterialTheme {
                mainUi(workshopService.asServer(ApiKey(clientApiKey!!)))
            }
        }
    }
}