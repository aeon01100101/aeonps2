package com.aeonps2.firetv

import android.content.Context
import java.io.File

/**
 * The folder layout the PCEE2 core expects, all inside our own sandbox:
 *
 *   <filesDir>/system/pcsx2/bios       — his imported PS2 BIOS dumps (.bin)
 *   <filesDir>/system/pcsx2/resources  — copied once from the APK's
 *                                        "pcsx2resources" assets dir
 *   <filesDir>/system/pcsx2/memcards   — the core writes Mcd001.ps2 /
 *                                        Mcd002.ps2 here itself (shared
 *                                        across Fat and Slim, like two
 *                                        real memory cards)
 *   <filesDir>/saves                   — saveDir handed to nativeInit
 *   <filesDir>/games                   — imported game files live here
 *   <filesDir>/states                  — save states, per game and per mode
 */
object SystemSetup {

    fun systemDir(filesDir: File) = File(filesDir, "system")
    fun pcsx2Dir(filesDir: File) = File(systemDir(filesDir), "pcsx2")
    fun biosDir(filesDir: File) = File(pcsx2Dir(filesDir), "bios")
    fun resourcesDir(filesDir: File) = File(pcsx2Dir(filesDir), "resources")
    fun memcardsDir(filesDir: File) = File(pcsx2Dir(filesDir), "memcards")
    fun gamesDir(filesDir: File) = File(filesDir, "games")
    fun savesDir(filesDir: File) = File(filesDir, "saves")
    fun statesRoot(filesDir: File) = File(filesDir, "states")

    /** Imported PS2 BIOS dumps (any .bin in the bios folder). */
    fun biosFiles(filesDir: File): List<File> {
        return try {
            biosDir(filesDir)
                .listFiles { f -> f.isFile && f.extension.lowercase() == "bin" }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The core cannot boot anything without at least one BIOS file. */
    fun hasBios(filesDir: File): Boolean = biosFiles(filesDir).isNotEmpty()

    /** Creates every folder and copies the core resources once. */
    fun ensure(ctx: Context) {
        try {
            biosDir(ctx.filesDir).mkdirs()
            resourcesDir(ctx.filesDir).mkdirs()
            memcardsDir(ctx.filesDir).mkdirs()
            gamesDir(ctx.filesDir).mkdirs()
            savesDir(ctx.filesDir).mkdirs()
            statesRoot(ctx.filesDir).mkdirs()
            copyResourcesIfEmpty(ctx)
        } catch (e: Exception) {
            // Best effort; EmulatorActivity re-checks what it needs.
        }
    }

    private fun copyResourcesIfEmpty(ctx: Context) {
        val dest = resourcesDir(ctx.filesDir)
        try {
            val existing = dest.list()
            if (existing != null && existing.isNotEmpty()) return
            copyAssetDir(ctx, "pcsx2resources", dest)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, dest: File) {
        val am = ctx.assets
        val entries = try {
            am.list(assetPath)
        } catch (e: Exception) {
            null
        } ?: return
        if (entries.isEmpty()) {
            // A file, not a directory.
            try {
                dest.parentFile?.mkdirs()
                am.open(assetPath).use { ins ->
                    dest.outputStream().use { out -> ins.copyTo(out) }
                }
            } catch (e: Exception) {
                // Skip this file.
            }
            return
        }
        dest.mkdirs()
        for (name in entries) {
            copyAssetDir(ctx, "$assetPath/$name", File(dest, name))
        }
    }
}
