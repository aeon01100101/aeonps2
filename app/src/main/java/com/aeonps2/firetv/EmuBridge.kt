package com.aeonps2.firetv

import android.view.Surface

/**
 * JNI bridge to the native libretro frontend (aeonps2_bridge) driving the
 * PCEE2 core (libpcee2_libretro_android.so).
 * Only ever called from EmulatorActivity, which runs in the :emu process.
 */
object EmuBridge {
    init {
        System.loadLibrary("aeonps2_bridge")
    }

    external fun nativeInit(corePath: String?, systemDir: String, saveDir: String): Boolean

    /** "fat" | "slim" — tell the core which PlayStation 2 we are. */
    external fun nativeSetMode(mode: String)

    /** Normalized disc serial, e.g. "SLUS-20312". "" = none. */
    external fun nativeSetGameSerial(serial: String)

    /** One per-game option override, keyed on the serial. */
    external fun nativeSetPerGameOption(serial: String, key: String, value: String)

    /** Null path boots the PS2 shell with no content loaded. */
    external fun nativeLoadGame(path: String?): Boolean
    external fun nativeRunFrame()
    external fun nativeShutdown()
    external fun nativeSetSurface(surface: Surface?)
    external fun nativeSetPad(port: Int, button: Int, pressed: Boolean)
    external fun nativeSetAnalog(port: Int, x: Int, y: Int)

    /** Right analog stick — mirrors nativeSetAnalog for the core's
     * RETRO_DEVICE_INDEX_ANALOG_RIGHT pair (v1.0.1+). */
    external fun nativeSetAnalogRight(port: Int, x: Int, y: Int)
    external fun nativeSaveState(path: String): Boolean
    external fun nativeLoadState(path: String): Boolean

    /** Save-state size in bytes (~96 MB on this core). */
    external fun nativeStateSize(): Long
    external fun nativeDrainAudio(buf: ShortArray, maxShorts: Int): Int
    external fun nativeGetRingLog(): String
    external fun nativeSetLogPath(path: String)
    external fun nativeGetAudioRate(): Int

    /** Frame pacing: call once per frame BEFORE nativeRunFrame. */
    external fun nativePaceFrame()
    external fun nativeGetAudioOccupancy(): Int
    external fun nativeGetAudioDrops(): Long
    external fun nativeGetTargetFps(): Double

    /** Produced vs presented frame counters (must stay 1:1). */
    external fun nativeGetProducedFrames(): Long
    external fun nativeGetPresentedFrames(): Long

    /**
     * Appends a "PHASE <name>" line to the native ring log (mirrored to the
     * log file), so a crash's location survives in native code too.
     * Canonical order: INIT (before nativeInit) -> LOAD_BEGIN (before
     * nativeLoadGame) -> RUNNING (bridge, once frames flow). The bridge
     * logs the later markers itself.
     */
    external fun nativeSetPhase(phase: String)

    /** "key=default|value" lines, for the debug log viewer. */
    external fun nativeListOptions(): String
}
