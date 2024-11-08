package kmpworkshop.client

import kmpworkshop.common.*

/**
 * Asks the server to move your slider.
 * Returns the position that the server slid you to, or null if there is no game in progress.
 */ // TODO: Rename, it looks horrible in retrospect.
suspend fun suggestSliderPosition(ratio: Double): Double? =
    suggestSliderPosition(workshopService.asServer(ApiKey(clientApiKey!!)), ratio)

suspend fun suggestSliderPosition(server: WorkshopServer, ratio: Double): Double? =
    when (val result = server.setSlider(ratio)) {
        SlideResult.InvalidApiKey -> wrongApiKeyConfigurationError()
        SlideResult.NoSliderGameInProgress -> null
        is SlideResult.Success -> result.setRatio
    }