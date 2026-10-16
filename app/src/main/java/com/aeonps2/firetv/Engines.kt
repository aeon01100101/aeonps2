package com.aeonps2.firetv

import android.os.Build

/**
 * Engine selection by the ABI the OS actually supports.
 *
 * v1.0.1 was arm64-only, which refused to install on 32-bit Fire OS
 * sticks (a 64-bit-capable CPU can still run a 32-bit userland).
 * v1.0.2 ships both: PCEE2 (the PCSX2 engine) for 64-bit devices,
 * and Play! as the honest fallback for 32-bit-only devices.
 *
 * The selection truth table is a pure function ([selectEngineCode])
 * so it is unit-testable; it mirrors aeonps2_select_engine_code()
 * in the native bridge. The :emu process isolation covers either
 * core crashing — the engine choice cannot brick the app.
 */
object Engines {
    const val PCEE2 = 0
    const val PLAY = 1

    /**
     * Pure selection: arm64-v8a anywhere on the list means the process
     * can load the PCEE2 core. Anything else means this process is
     * 32-bit and must use Play!.
     */
    fun selectEngineCode(abis: List<String>): Int =
        if (abis.any { it == "arm64-v8a" }) PCEE2 else PLAY

    /** The engine this process would actually load. */
    fun currentEngineCode(): Int =
        selectEngineCode(Build.SUPPORTED_ABIS.toList())

    /** Native library file name for the engine, relative to the app's
     * nativeLibraryDir. */
    fun coreLibName(engine: Int): String =
        if (engine == PCEE2) "libpcee2_libretro_android.so"
        else "libplay_libretro_android.so"

    /** True when the engine can boot with no content ([Test BIOS]). */
    fun supportsBiosTest(engine: Int): Boolean = engine == PCEE2

    /** Plain-language name for settings cards and log headers. */
    fun displayName(engine: Int): String =
        if (engine == PCEE2) "PCEE2 (the PCSX2 engine)" else "Play! (fallback engine)"
}
