package kmpworkshop.client

import kmpworkshop.common.SlideResult

/**
 * Asks the server to move your slider.
 * Returns the position that the server slid you to, or null if there is no game in progress.
 */
suspend fun suggestSliderPosition(ratio: Double): Double? =
    when (val result = workshopService.setSlider(getApiKeyFromEnvironment(), ratio)) {
        SlideResult.InvalidApiKey -> wrongApiKeyConfigurationError()
        SlideResult.NoSliderGameInProgress -> null
        is SlideResult.Success -> result.setRatio
    }