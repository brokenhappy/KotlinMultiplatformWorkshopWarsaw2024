@file:Suppress("FunctionName")

import androidx.compose.material.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kmpworkshop.client.suggestSliderPosition
import kmpworkshop.common.WorkshopServer

@Composable
internal fun SliderGameSolution(server: WorkshopServer) {
    var sliderValue by remember { mutableStateOf(0.0f) }

    LaunchedEffect(sliderValue) {
        suggestSliderPosition(server, sliderValue.toDouble())
    }

    Slider(sliderValue, onValueChange = { sliderValue = it }, valueRange = 0.0f..1.0f)
}