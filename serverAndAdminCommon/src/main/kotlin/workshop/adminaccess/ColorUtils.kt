package workshop.adminaccess

import kmpworkshop.common.SerializableColor
import kotlin.math.absoluteValue
import kotlin.math.roundToInt


fun SerializableColor.applyingDimming(dimmingRatio: Float): SerializableColor = transitionTo(
    other = if (dimmingRatio < 0) SerializableColor(0, 0, 0)
    else SerializableColor(255, 255, 255),
    ratio = dimmingRatio.absoluteValue,
)

fun SerializableColor.transitionTo(other: SerializableColor, ratio: Float): SerializableColor = SerializableColor(
    red = (this.red * (1 - ratio) + other.red * ratio).roundToInt(),
    green = (this.green * (1 - ratio) + other.green * ratio).roundToInt(),
    blue = (this.blue * (1 - ratio) + other.blue * ratio).roundToInt(),
)