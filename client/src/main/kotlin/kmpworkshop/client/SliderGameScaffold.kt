package kmpworkshop.client

import kmpworkshop.common.SlideResult
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.asServer

/**
 * Asks the server to move your slider.
 * Returns the position that the server slid you to, or null if there is no game in progress.
 */
suspend fun suggestSliderPosition(ratio: Double): Double? =
    suggestSliderPosition(workshopService.asServer(getApiKeyFromEnvironment()), ratio)

suspend fun suggestSliderPosition(server: WorkshopServer, ratio: Double): Double? =
    when (val result = server.setSlider(ratio)) {
        SlideResult.InvalidApiKey -> wrongApiKeyConfigurationError()
        SlideResult.NoSliderGameInProgress -> null
        is SlideResult.Success -> result.setRatio
    }