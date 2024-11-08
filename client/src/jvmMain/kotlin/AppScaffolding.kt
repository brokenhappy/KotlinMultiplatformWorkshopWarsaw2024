@file:Suppress("FunctionName")

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

internal fun WorkshopApp(title: String, mainUi: @Composable () -> Unit) {
    application {
        Window(onCloseRequest = ::exitApplication, title = title) {
            MaterialTheme {
                mainUi()
            }
        }
    }
}