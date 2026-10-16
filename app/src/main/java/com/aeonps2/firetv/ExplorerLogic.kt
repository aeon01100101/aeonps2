package com.aeonps2.firetv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure file-explorer/importer logic: no Android imports on purpose, so it
 * can be compiled and unit-tested on the host JVM. [FileExplorerActivity]
 * feeds picked files through these functions.
 */
object ExplorerLogic {

    /** A real PlayStation 2 BIOS is exactly this many bytes (4 MB). */
    const val BIOS_SIZE = 4194304L

    /** Extensions AeonPS2 treats as playable games. */
    val GAME_EXTENSIONS: Set<String> =
        setOf("iso", "chd", "cue", "m3u", "cso", "bin", "mdf", "nrg", "elf")

    /**
     * "/storage/emulated/0/Download" -> ["storage", "emulated", "0",
     * "Download"]. The root "/" yields an empty list.
     */
    fun breadcrumbSegments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /** 1536 -> "1.5 KB". */
    fun formatSize(bytes: Long): String {
        val b = if (bytes < 0) 0L else bytes
        return when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L ->
                "%.1f KB".format(Locale.US, b / 1024.0)
            b < 1024L * 1024L * 1024L ->
                "%.1f MB".format(Locale.US, b / (1024.0 * 1024.0))
            else ->
                "%.1f GB".format(Locale.US, b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /** Human date/time in plain words, e.g. "Oct 16, 2026, 9:24 AM". */
    fun prettyDate(millis: Long): String =
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(millis))

    /**
     * Makes a file name safe for our own folders: no path separators,
     * no control characters, capped length.
     */
    fun sanitizeName(name: String): String {
        var s = name.replace('/', '_').replace('\\', '_').trim()
        s = s.replace(Regex("[\\x00-\\x1f\\x7f]"), "_")
        if (s.isEmpty()) s = "file"
        return s.take(120)
    }

    /**
     * Picks a non-colliding destination inside [dir] for [name]:
     * "game.iso", "game (2).iso", "game (3).iso", ...
     */
    fun uniqueDest(dir: java.io.File, name: String): java.io.File {
        var candidate = java.io.File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            candidate = java.io.File(dir, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }
}
