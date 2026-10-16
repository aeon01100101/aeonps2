package com.aeonps2.firetv

import android.content.Context
import java.io.File

/**
 * Kotlin-side owner of the emulator's file-backed ring log.
 *
 * The native bridge mirrors every ring-log line into
 * `<cacheDir>/last_emu_log.txt` (EmulatorActivity -> 
 * EmuBridge.nativeSetLogPath, opened "w" so each session starts fresh).
 *
 * This object:
 *  - writes a small header (timestamp + original game path) at session
 *    start, so the viewer always knows which game a log belongs to;
 *  - exports the log to the user-browsable app external files dir after
 *    every session (clean exit AND crash), overwriting;
 *  - greps the log for the game path and disc serial for the viewer.
 *
 * The bridge exposes no nativeLog() call, so Kotlin writes go straight to
 * the same file the bridge appends to (see EmulatorActivity.writeLogHeader).
 */
object EmuLog {
    private const val INTERNAL_NAME = "last_emu_log.txt"
    private const val EXPORT_NAME = "emu_log_latest.txt"

    /** Viewer caps: a huge log must never OOM the viewer. */
    const val VIEW_MAX_LINES = 4000
    const val VIEW_MAX_BYTES = 262144 // 256 KB

    /** Prefix for the Kotlin-written header line (see writeLogHeader). */
    const val PREFIX_GAME_PATH = "aeon: game path "

    /** Marker for the disc serial line (written by EmulatorActivity). */
    const val PREFIX_DISC_SERIAL = "aeon: disc serial "

    fun internalLogFile(cacheDir: File): File = File(cacheDir, INTERNAL_NAME)

    fun exportFile(context: Context): File? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return null
            File(dir, EXPORT_NAME)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies last_emu_log.txt to
     * `getExternalFilesDir(null)/emu_log_latest.txt` (overwriting).
     * Best-effort: a failed copy just means the export isn't fresh.
     */
    fun exportLog(context: Context) {
        try {
            val src = internalLogFile(context.cacheDir)
            val dst = exportFile(context) ?: return
            if (!src.isFile) return
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        } catch (e: Exception) {
            // Ignore.
        }
    }

    data class Summary(val gamePath: String?, val serial: String?)

    /**
     * Greps the log text for the game path and disc serial (last match
     * wins). The bridge also logs `load_game ok: <path>` per load, which
     * is used as a fallback when the Kotlin header is absent.
     */
    fun summarize(text: String): Summary {
        var game: String? = null
        var serial: String? = null
        for (line in text.lines()) {
            when {
                line.startsWith(PREFIX_GAME_PATH) -> {
                    game = line.removePrefix(PREFIX_GAME_PATH).ifBlank { null }
                }
                line.startsWith("load_game ok: ") -> {
                    val p = line.removePrefix("load_game ok: ")
                    if (p != "(bios boot)") game = p
                }
                line.startsWith(PREFIX_DISC_SERIAL) -> {
                    serial = line.removePrefix(PREFIX_DISC_SERIAL).ifBlank { null }
                }
            }
        }
        return Summary(game, serial)
    }
}
