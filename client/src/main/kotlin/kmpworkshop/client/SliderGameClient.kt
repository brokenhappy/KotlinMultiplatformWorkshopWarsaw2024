@file:Suppress("FunctionName")

package kmpworkshop.client

import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    application {
        Window(onCloseRequest = ::exitApplication, title = "Slider Game Client") {
            MaterialTheme {
                SliderGameClient()
            }
        }
    }
}

@Composable
fun SliderGameClient() {
    var sliderValue by remember { mutableStateOf(0.0f) }

    LaunchedEffect(sliderValue) {
        val result = suggestSliderPosition(sliderValue.toDouble())
        println("Result: $result")
    }

    Button(onClick = { sliderValue = if (sliderValue == 0.0f) 1.0f else 0.0f }) {
        Text("Swap place")
    }
}