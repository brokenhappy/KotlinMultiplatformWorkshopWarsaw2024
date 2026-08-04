package kmpworkshop.client

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.common.ApiKey
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey

fun main() {
    application {
        Window(onCloseRequest = ::exitApplication, title = "Workshop Client") {
            MaterialTheme {
                val key1 = clientApiKey
                if (key1 == null) Text("Finish registration first, then restart WorkshopClient.")
                else WorkshopClient(workshopService.asServer(ApiKey(key1)))
            }
        }
    }
}
