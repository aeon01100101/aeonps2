package com.aeonps2.firetv

import java.io.File

/**
 * Crash marker shared between the UI process and the :emu process.
 *
 * File-based on purpose: the emulator runs in its own process, and if the
 * native core crashes, that whole process dies — SharedPreferences writes
 * from it would be unreliable. Both processes share the same filesDir.
 *
 * Protocol: EmulatorActivity writes the path when a game starts and deletes
 * it on clean exit. If the file is still there later, the emu died
 * unexpectedly.
 */
object CrashSentinel {
    private const val DIR_NAME = "emu"
    private const val FILE_NAME = "last_game.txt"

    private fun sentinelFile(filesDir: File): File = File(File(filesDir, DIR_NAME), FILE_NAME)

    fun mark(filesDir: File, path: String) {
        try {
            val dir = File(filesDir, DIR_NAME)
            dir.mkdirs()
            sentinelFile(filesDir).writeText(path)
        } catch (e: Exception) {
            // Best effort; a missing marker just means no crash dialog later.
        }
    }

    fun clear(filesDir: File) {
        try {
            sentinelFile(filesDir).delete()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    fun read(filesDir: File): String? {
        return try {
            val f = sentinelFile(filesDir)
            if (f.isFile) {
                f.readText().trim().ifEmpty { null }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
