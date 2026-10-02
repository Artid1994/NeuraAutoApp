package com.example.neuraauto.data

/**
 * Ambient conditions captured at the moment an app came to the foreground.
 *
 * Kept as one value object so the recorder's supplier returns a single
 * snapshot rather than a growing tuple. Read on the recorder's IO thread
 * because each field costs a system-service binder call.
 */
data class AmbientState(
    /** Device was plugged in. */
    val isCharging: Boolean,

    /** Active transport was Wi-Fi. */
    val isWifiConnected: Boolean,

    /**
     * SSID of the connected Wi-Fi network, or null.
     *
     * Null when not on Wi-Fi, or when the platform refuses the read: since
     * Android 8.1 `WifiManager.getSSID()` requires a granted location
     * permission, and Android 13+ additionally requires location services to
     * be enabled. The value is treated as "unknown context", never as an
     * error — feature hashing handles a missing SSID as its own bucket.
     */
    val wifiSsid: String?,

    /** Battery percentage 0..100, or -1 when unavailable. */
    val batteryPercent: Int
)
