package kmpworkshop.client

import java.util.prefs.Preferences

/** Settings that affect the local workshop client UI. */
public data class ClientSettings(
    val zoom: Float = DefaultClientZoom,
)

internal const val MinClientZoom = 0.3f
internal const val MaxClientZoom = 3.0f
internal const val DefaultClientZoom = 1.0f
internal const val ClientZoomStep = 0.1f

private const val ZoomPreferenceKey = "zoom"

private val clientPreferences: Preferences by lazy {
    Preferences.userNodeForPackage(ClientSettings::class.java)
}

internal fun loadClientSettings(): ClientSettings {
    val zoom = runCatching {
        clientPreferences.get(ZoomPreferenceKey, DefaultClientZoom.toString()).toFloat()
    }.getOrDefault(DefaultClientZoom)

    return ClientSettings(zoom = zoom.coerceIn(MinClientZoom, MaxClientZoom))
}

internal fun persistClientSettings(settings: ClientSettings) {
    runCatching {
        clientPreferences.put(ZoomPreferenceKey, settings.zoom.toString())
    }
}
