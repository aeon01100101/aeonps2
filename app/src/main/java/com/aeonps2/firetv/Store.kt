package com.aeonps2.firetv

import android.content.Context

/**
 * UI-process-only preferences. The :emu process must never touch these;
 * it communicates crash/mode-switch state through files instead
 * ([CrashSentinel], [ModeRelaunch]).
 */
object Store {
    private const val PREFS = "aeonps2"
    private const val KEY_GAMES = "games"
    private const val KEY_DEFAULT_MODE = "defaultMode"
    private const val KEY_BIOS_IMPORTED = "biosImported"
    private const val KEY_AUTO_TEST = "autoTestPending"
    private const val PREFIX_MODE = "mode:"

    const val MODE_FAT = "fat"
    const val MODE_SLIM = "slim"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Game file paths the user imported into the library. */
    fun games(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_GAMES, emptySet())?.toSet() ?: emptySet()

    fun addGame(ctx: Context, path: String) {
        val next = games(ctx).toMutableSet()
        if (next.add(path)) {
            prefs(ctx).edit().putStringSet(KEY_GAMES, next).apply()
        }
    }

    fun removeGame(ctx: Context, path: String) {
        val next = games(ctx).toMutableSet()
        if (next.remove(path)) {
            prefs(ctx).edit().putStringSet(KEY_GAMES, next).apply()
        }
    }

    /** The mode new games launch with until they remember their own. */
    fun defaultMode(ctx: Context): String =
        prefs(ctx).getString(KEY_DEFAULT_MODE, MODE_FAT)
            ?.takeIf { it == MODE_SLIM } ?: MODE_FAT

    fun setDefaultMode(ctx: Context, mode: String) {
        prefs(ctx).edit()
            .putString(KEY_DEFAULT_MODE, if (mode == MODE_SLIM) MODE_SLIM else MODE_FAT)
            .apply()
    }

    /** Per-game remembered mode; falls back to the default. */
    fun gameMode(ctx: Context, path: String): String =
        prefs(ctx).getString(PREFIX_MODE + path, null)
            ?.takeIf { it == MODE_SLIM || it == MODE_FAT }
            ?: defaultMode(ctx)

    fun setGameMode(ctx: Context, path: String, mode: String) {
        prefs(ctx).edit()
            .putString(PREFIX_MODE + path, if (mode == MODE_SLIM) MODE_SLIM else MODE_FAT)
            .apply()
    }

    /** True once the user has imported at least one BIOS file. */
    fun biosImported(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BIOS_IMPORTED, false)

    fun setBiosImported(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BIOS_IMPORTED, v).apply()
    }

    /**
     * True when a fresh BIOS import still owes its one automatic Test BIOS
     * run. Cleared the moment the test is launched, so it fires exactly
     * once even if the activity restarts in between.
     */
    fun autoTestPending(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_TEST, false)

    fun setAutoTestPending(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_TEST, v).apply()
    }
}
