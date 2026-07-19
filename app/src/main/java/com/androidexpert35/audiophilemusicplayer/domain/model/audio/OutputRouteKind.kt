package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Describes the user-visible transport family carrying the engine output.
 *
 * The model intentionally contains no Android framework device types so routing
 * telemetry can cross the Data-to-Domain boundary without leaking platform APIs.
 */
enum class OutputRouteKind {
    /** Bluetooth Classic, Bluetooth LE Audio, SCO, or hearing-aid transport. */
    BLUETOOTH,

    /** USB audio routed either through Android or the app-owned hardware bypass. */
    USB,

    /** Analog or digital wired headphones, headset, or line output. */
    WIRED,

    /** Speaker or earpiece built into the Android device. */
    BUILT_IN,

    /** A resolved transport that does not belong to a dedicated UI family. */
    OTHER,

    /** Routing has not yet been resolved. */
    UNKNOWN,
}
