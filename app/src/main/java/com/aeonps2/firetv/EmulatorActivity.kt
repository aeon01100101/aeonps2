package com.aeonps2.firetv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File
import java.security.MessageDigest

/**
 * Runs the emulator in its own process (android:process=":emu").
 *
 * If the native core crashes, this whole process dies. [CrashSentinel] is
 * marked before any native call and cleared only on a clean exit, so a
 * leftover marker always means "the emu died unexpectedly".
 *
 * This process NEVER touches SharedPreferences — crash and mode-switch
 * state travel through files only ([CrashSentinel], [ModeRelaunch]).
 */
class EmulatorActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "com.aeonps2.firetv.extra.PATH"

        /** "fat" | "slim" — chosen by the UI process, never read here. */
        const val EXTRA_MODE = "com.aeonps2.firetv.extra.MODE"

        /** BIOS-test mode: boot the shell with no game and report the result. */
        const val EXTRA_BIOS_TEST = "com.aeonps2.firetv.extra.BIOS_TEST"

        /** Result extra: true when the BIOS shell reached enough frames. */
        const val EXTRA_BIOS_OK = "com.aeonps2.firetv.extra.BIOS_OK"

        /** Result extra: true when the user backed out of the test. */
        const val EXTRA_BIOS_ABORTED = "com.aeonps2.firetv.extra.BIOS_ABORTED"

        /** Result extra: true when the core rejected the BIOS file
         * (VMManager::Initialize() failed — black frames). */
        const val EXTRA_BIOS_REJECTED = "com.aeonps2.firetv.extra.BIOS_REJECTED"

        /** Hold START this long to open the app pause menu (a quick
         * press goes to the game instead). */
        const val START_LONG_PRESS_MS = 600L

        /** A quick Start tap is held down this long in the core so its
         * per-frame input poll is guaranteed to see the press. */
        const val START_TAP_RELEASE_MS = 80L

        /** A PS2 BIOS dump is 4 MB — a .bin that size is a BIOS, not a game. */
        private const val BIOS_FILE_SIZE = 4194304L

        /** Disc images below this are too small to be PS2 games. */
        private const val MIN_DISC_SIZE = 16L * 1024L * 1024L

        /** Frames of clean BIOS-shell output before the test passes. */
        private const val BIOS_TEST_FRAMES = 600
    }

    /**
     * Libretro joypad ids — same table as AeonPS1 Reborn. The physical
     * layout is DualShock: A=Cross, B=Circle, X=Square, Y=Triangle.
     *
     * START reaches the core on a QUICK press (Kingdom Hearts' own
     * pause menu and FFX's menus are Start-driven). A LONG press
     * (~600 ms) opens the app pause menu instead — see onKeyDown /
     * onKeyUp, which never let a long-press leak a press to the core.
     */
    private object Pad {
        const val CROSS = 0
        const val TRIANGLE = 1
        const val SELECT = 2
        const val START = 3
        const val UP = 4
        const val DOWN = 5
        const val LEFT = 6
        const val RIGHT = 7
        const val CIRCLE = 8
        const val SQUARE = 9
        const val L1 = 10
        const val R1 = 11
        const val L2 = 12
        const val R2 = 13
        const val L3 = 14
        const val R3 = 15
    }

    /**
     * Per-game option table, keyed by normalized disc serial.
     *
     * v1.0 choice — deliberate, bench-backed: ZERO overrides for both
     * test titles. PCEE2 ships its own GameIndex.yaml and applies each
     * title's fixes itself: Final Fantasy X (SLUS-20312) is a compat-5
     * entry with its full EE/VU clamp + round-mode + FMV/roundSprite /
     * autoFlush / preloadFrameData stack, and Kingdom Hearts
     * (SLUS-20370) is a compat-5 entry with one clean nativeScaling fix.
     * The bench verified those entries exist in the exact core we ship,
     * so frontend overrides would only risk double-applying. Both
     * serials stay registered here so the serial -> option path is
     * exercised on every boot and visible in [Emulator log]; add real
     * overrides only if a future bench proves the core missed one.
     */
    private val PER_GAME_OVERRIDES: Map<String, Map<String, String>> = mapOf(
        "SLUS-20312" to emptyMap(), // Final Fantasy X (US)
        "SLUS-20370" to emptyMap(), // Kingdom Hearts (US)
    )

    @Volatile private var quitRequested = false
    @Volatile private var pausedByMenu = false
    @Volatile private var pausedByHome = false
    private var menuOpen = false
    private var biosTest = false
    private var testAborted = false
    private var mode = Store.MODE_FAT

    /**
     * START long-press handling (v1.0.1).
     *
     * A quick press goes to the CORE (it's an in-game button). Holding
     * START opens the app pause menu instead. The press is never sent
     * on key-down: we wait for key-up, so a long-press can never leak
     * a Start to the core. A quick tap is re-emitted as a clean
     * press+release held for START_TAP_RELEASE_MS so the core's
     * per-frame poll is guaranteed to see it at least once.
     */
    private val inputHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var startLongFired = false
    private val startLongRunnable = Runnable {
        startLongFired = true
        openMenu()
    }

    private var emuThread: Thread? = null
    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    /** PCEE2 (64-bit, main) or Play! (32-bit-only TV, fallback). */
    private var engine = Engines.PCEE2

    private var originalPath = ""
    private var resolvedPath = ""

    /** The mirrored ring-log file; Kotlin appends its header/lines here. */
    private var logFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        biosTest = intent.getBooleanExtra(EXTRA_BIOS_TEST, false)
        mode = intent.getStringExtra(EXTRA_MODE)
            ?.takeIf { it == Store.MODE_SLIM } ?: Store.MODE_FAT

        if (biosTest) {
            beginBiosTest()
            return
        }

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrEmpty()) {
            finish()
            return
        }
        originalPath = path

        // Sentinel check BEFORE touching any native code.
        val last = try {
            CrashSentinel.read(filesDir)
        } catch (e: Exception) {
            null
        }
        if (last == path) {
            val title = try {
                File(path).nameWithoutExtension.replace('_', ' ')
            } catch (e: Exception) {
                path
            }
            try {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.crashed_before_title))
                    .setMessage(getString(R.string.crashed_before_msg, title))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.try_again)) { _, _ -> begin() }
                    .setNegativeButton(getString(R.string.back_to_library)) { _, _ ->
                        try {
                            CrashSentinel.clear(filesDir)
                        } catch (e: Exception) {
                            // Ignore.
                        }
                        finish()
                    }
                    .show()
            } catch (e: Exception) {
                finish()
            }
            return
        }
        begin()
    }

    override fun onDestroy() {
        quitRequested = true
        try {
            EmuBridge.nativeSetSurface(null)
        } catch (e: Throwable) {
            // Ignore.
        }
        // Clean session end (quit, failed load, back-out): copy the emu log
        // where the user can browse it. A native crash never reaches here —
        // that export happens on the crash-sentinel path in MainActivity /
        // BiosActivity, which read the surviving log file on next launch.
        try {
            EmuLog.exportLog(this)
        } catch (e: Throwable) {
            // Ignore.
        }
        super.onDestroy()
    }

    /**
     * HOME pressed mid-game: pause emulation and audio instead of letting
     * the threads run behind the launcher. Never finish() here.
     */
    override fun onPause() {
        super.onPause()
        pausedByHome = true
        try {
            audioTrack?.pause()
        } catch (e: Throwable) {
            // Ignore.
        }
    }

    override fun onResume() {
        super.onResume()
        pausedByHome = false
        try {
            audioTrack?.play()
        } catch (e: Throwable) {
            // Ignore.
        }
    }

    // ---------- startup & validation (no native calls until the end) ----------

    private fun begin() {
        engine = Engines.currentEngineCode()
        // The Play! fallback engine can boot games with its own built-in
        // handling, so a missing BIOS is not a hard stop there — the
        // main PCEE2 engine cannot boot anything without a real PS2 BIOS.
        if (engine == Engines.PCEE2 && !SystemSetup.hasBios(filesDir)) {
            noBiosDialog()
            return
        }
        val file = File(originalPath)
        if (!file.isFile || !file.canRead()) {
            failDialog(getString(R.string.fail_unreadable))
            return
        }
        val size = try {
            file.length()
        } catch (e: Exception) {
            -1L
        }
        val ext = file.extension.lowercase()

        if (ext == "bin" && size == BIOS_FILE_SIZE) {
            offerUseAsBios(file)
            return
        }

        if (ext in DISC_IMAGE_EXTS && size < MIN_DISC_SIZE) {
            failDialog(getString(R.string.fail_too_small))
            return
        }

        if (ext == "cue") {
            val missing = missingCueFile(file)
            if (missing != null) {
                failDialog(getString(R.string.fail_missing_data, missing))
                return
            }
        } else if (ext == "m3u") {
            val missing = missingM3uFile(file)
            if (missing != null) {
                failDialog(getString(R.string.fail_missing_data, missing))
                return
            }
        }

        resolvedPath = originalPath
        try {
            CrashSentinel.mark(filesDir, originalPath)
        } catch (e: Exception) {
            // Ignore.
        }

        showSurface()
        startEmuThread()
    }

    private val DISC_IMAGE_EXTS = setOf("iso", "bin", "chd", "cso", "mdf", "nrg")

    /**
     * A 4 MB .bin is a PS2 BIOS dump, not a game: offer to adopt it as
     * the BIOS (the UI process records it) instead of failing on load.
     */
    private fun offerUseAsBios(file: File) {
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.bios_not_game_title))
                .setMessage(getString(R.string.bios_not_game_msg))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.use_as_bios)) { _, _ ->
                    adoptBios(file)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .show()
        } catch (e: Exception) {
            finish()
        }
    }

    private fun adoptBios(file: File) {
        try {
            val dest = File(SystemSetup.biosDir(filesDir).apply { mkdirs() }, file.name)
            file.copyTo(dest, overwrite = true)
            startActivity(Intent(this, BiosActivity::class.java))
        } catch (e: Exception) {
            failDialog(getString(R.string.fail_bios_copy))
            return
        }
        finish()
    }

    /**
     * BIOS-test mode: no game validation at all. The sentinel is marked
     * "BIOS_TEST" so a native crash mid-test is distinguishable from a
     * game crash; the shell boots with nativeLoadGame(null).
     */
    private fun beginBiosTest() {
        engine = Engines.currentEngineCode()
        if (!Engines.supportsBiosTest(engine)) {
            // The Play! fallback engine cannot boot without content
            // (its no-content path crashes the core) — the button for
            // this is hidden on 32-bit TVs, so this is only a guard.
            runOnUiThread {
                try {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.bios_title))
                        .setMessage(getString(R.string.test_bios_unavailable))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } catch (e: Exception) {
                    // Ignore.
                }
            }
            finish()
            return
        }
        if (!SystemSetup.hasBios(filesDir)) {
            noBiosDialog()
            return
        }
        try {
            CrashSentinel.mark(filesDir, "BIOS_TEST")
        } catch (e: Exception) {
            // Ignore.
        }
        showSurface()
        startEmuThread()
    }

    /** Plain words when there is no BIOS: point at the BIOS screen. */
    private fun noBiosDialog() {
        runOnUiThread {
            try {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.no_bios_title))
                    .setMessage(getString(R.string.no_bios_msg))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.open_bios_screen)) { _, _ ->
                        try {
                            startActivity(Intent(this, BiosActivity::class.java))
                        } catch (e: Exception) {
                            // Ignore.
                        }
                        finish()
                    }
                    .setNegativeButton(getString(R.string.back_to_library)) { _, _ -> finish() }
                    .show()
            } catch (e: Exception) {
                try {
                    finish()
                } catch (e2: Exception) {
                    // Ignore.
                }
            }
        }
    }

    private fun showSurface() {
        val root = FrameLayout(this)
        val surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    EmuBridge.nativeSetSurface(holder.surface)
                } catch (e: Throwable) {
                    // Ignore.
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                // No-op.
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                try {
                    EmuBridge.nativeSetSurface(null)
                } catch (e: Throwable) {
                    // Ignore.
                }
            }
        })
    }

    /**
     * Writes the session header to the log file the bridge just opened.
     *
     * The bridge exposes no log-call of its own, so Kotlin appends directly
     * to the same file the bridge will append to. This must run right after
     * nativeSetLogPath (which truncates) and before any native call.
     */
    private fun writeLogHeader(logFile: File) {
        try {
            val ts = try {
                java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.US
                ).apply {
                    timeZone = java.util.TimeZone.getDefault()
                }.format(java.util.Date())
            } catch (e: Exception) {
                "?"
            }
            val game = if (biosTest) "(bios boot)" else originalPath
            logFile.appendText("aeon: session $ts\n${EmuLog.PREFIX_GAME_PATH}$game\n")
            try {
                logFile.appendText("aeon: engine=${Engines.displayName(engine)}\n")
            } catch (e: Exception) {
                // Ignore.
            }
        } catch (e: Exception) {
            // Best effort; the bridge's own lines still land.
        }
    }

    /** Kotlin-side line into the same file the bridge appends to. */
    private fun appendLogLine(line: String) {
        try {
            logFile?.appendText(line + "\n")
        } catch (e: Exception) {
            // Ignore.
        }
    }

    /** Returns the name of a missing/empty referenced data file, or null if all good. */
    private fun missingCueFile(cue: File): String? {
        return try {
            val dir = cue.parentFile ?: return "unknown"
            val quoted = Regex("""(?i)^\s*FILE\s+"([^"]+)"""")
            val bare = Regex("""(?i)^\s*FILE\s+(\S+)""")
            for (line in cue.readLines()) {
                val name = quoted.find(line)?.groupValues?.get(1)
                    ?: bare.find(line)?.groupValues?.get(1)
                    ?: continue
                val ref = File(dir, name)
                if (!ref.isFile || ref.length() == 0L) return name
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Same check for .m3u playlists (one path per line, # = comment). */
    private fun missingM3uFile(m3u: File): String? {
        return try {
            val dir = m3u.parentFile ?: return "unknown"
            for (raw in m3u.readLines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val ref = File(dir, line)
                if (!ref.isFile || ref.length() == 0L) return line
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ---------- emulation thread ----------

    private fun startEmuThread() {
        emuThread = Thread({
            try {
                SystemSetup.ensure(this)
            } catch (e: Throwable) {
                // Ignore.
            }
            val systemDir = SystemSetup.systemDir(filesDir)
            val saveDir = SystemSetup.savesDir(filesDir)

            // BIOS re-check inside :emu (the UI check could race a removal).
            // The Play! fallback engine works without an imported BIOS.
            if (engine == Engines.PCEE2 && !SystemSetup.hasBios(filesDir)) {
                noBiosDialog()
                return@Thread
            }

            val corePath =
                File(applicationInfo.nativeLibraryDir, Engines.coreLibName(engine))
                    .absolutePath
            if (!File(corePath).isFile) {
                failDialog(getString(R.string.fail_core_missing))
                return@Thread
            }

            // Mirror the ring log to a file BEFORE any native call, so the
            // phase markers (INIT/LOAD_BEGIN/...) and the core's last words
            // survive a native crash. Best-effort: keep going if it fails.
            try {
                val lf = File(cacheDir, "last_emu_log.txt")
                logFile = lf
                EmuBridge.nativeSetLogPath(lf.absolutePath)
                // The bridge opens the file "w" (fresh each session), so a
                // Kotlin header written now always lands at the top of the
                // log. No native code logs between here and nativeInit, so
                // the ordering is deterministic.
                writeLogHeader(lf)
            } catch (e: Throwable) {
                // Ignore.
            }

            try {
                EmuBridge.nativeSetPhase("INIT")
            } catch (e: Throwable) {
                // Ignore.
            }
            val okInit = try {
                EmuBridge.nativeInit(corePath, systemDir.absolutePath, saveDir.absolutePath)
            } catch (e: Throwable) {
                false
            }
            if (!okInit) {
                failDialog(getString(R.string.fail_core_start))
                return@Thread
            }

            try {
                EmuBridge.nativeSetMode(if (biosTest) Store.MODE_FAT else mode)
            } catch (e: Throwable) {
                // Ignore.
            }

            // Disc serial + per-game option table (the serial line also
            // feeds the [Emulator log] viewer's header).
            val serial = if (biosTest) {
                ""
            } else {
                try {
                    SerialReader.extractSerial(File(resolvedPath))
                } catch (e: Exception) {
                    ""
                }
            }
            applyPerGameOptions(serial)

            try {
                EmuBridge.nativeSetPhase("LOAD_BEGIN")
            } catch (e: Throwable) {
                // Ignore.
            }
            val okLoad = try {
                // Null path = PS2 shell boot, no content (test mode only).
                EmuBridge.nativeLoadGame(if (biosTest) null else resolvedPath)
            } catch (e: Throwable) {
                false
            }
            if (!okLoad) {
                try {
                    EmuBridge.nativeShutdown()
                } catch (e: Throwable) {
                    // Ignore.
                }
                failDialog(getString(R.string.fail_game_load))
                return@Thread
            }

            if (biosTest) {
                runBiosTestLoop()
                return@Thread
            }

            startAudio()

            while (!quitRequested) {
                if (pausedByMenu || pausedByHome) {
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        // Ignore.
                    }
                    continue
                }
                try {
                    EmuBridge.nativePaceFrame()
                    EmuBridge.nativeRunFrame()
                } catch (e: Throwable) {
                    break
                }
            }

            stopAudio()
            try {
                EmuBridge.nativeShutdown()
            } catch (e: Throwable) {
                // Ignore.
            }
            try {
                CrashSentinel.clear(filesDir)
            } catch (e: Exception) {
                // Ignore.
            }
            runOnUiThread { if (!isFinishing) finish() }
        }, "aeon-emu")
        emuThread!!.start()
    }

    /**
     * Registers the disc serial with the core and applies this serial's
     * entry from [PER_GAME_OVERRIDES] (currently zero overrides for the
     * known titles — the core's own GameIndex owns their fixes; see the
     * table's comment). The registration itself is logged so the
     * mechanism stays visible in [Emulator log].
     */
    private fun applyPerGameOptions(serial: String) {
        try {
            EmuBridge.nativeSetGameSerial(serial)
        } catch (e: Throwable) {
            // Ignore.
        }
        appendLogLine("${EmuLog.PREFIX_DISC_SERIAL}$serial")
        val overrides = PER_GAME_OVERRIDES[serial]
        if (overrides == null) {
            appendLogLine("aeon: per-game table: no entry for serial \"$serial\" (core defaults)")
            return
        }
        appendLogLine(
            "aeon: per-game table: serial $serial registered, ${overrides.size} overrides"
        )
        for ((key, value) in overrides) {
            try {
                EmuBridge.nativeSetPerGameOption(serial, key, value)
            } catch (e: Throwable) {
                // Ignore.
            }
        }
    }

    /**
     * BIOS-test frame loop: the shell is already loaded (no content).
     * Enough successful frames = the BIOS works. Any native crash kills
     * this process, leaving the "BIOS_TEST" sentinel for BiosActivity.
     */
    private fun runBiosTestLoop() {
        var frames = 0
        while (!quitRequested && frames < BIOS_TEST_FRAMES) {
            if (pausedByHome) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    // Ignore.
                }
                continue
            }
            try {
                EmuBridge.nativePaceFrame()
                EmuBridge.nativeRunFrame()
            } catch (e: Throwable) {
                break
            }
            frames++
        }
        try {
            EmuBridge.nativeShutdown()
        } catch (e: Throwable) {
            // Ignore.
        }
        try {
            CrashSentinel.clear(filesDir)
        } catch (e: Exception) {
            // Ignore.
        }
        // The core "succeeds" load_game even when it rejects the BIOS
        // (it logs "VMManager::Initialize() failed." and renders black
        // frames). Frame count alone would call that a pass — check the
        // ring log for the rejection line before declaring victory.
        val biosRejected = try {
            EmuBridge.nativeGetRingLog().contains("VMManager::Initialize() failed")
        } catch (e: Throwable) {
            false
        }
        runOnUiThread {
            when {
                testAborted -> setResult(
                    RESULT_CANCELED,
                    Intent().putExtra(EXTRA_BIOS_ABORTED, true)
                )
                biosRejected -> setResult(
                    RESULT_CANCELED,
                    Intent().putExtra(EXTRA_BIOS_ABORTED, true)
                        .putExtra(EXTRA_BIOS_REJECTED, true)
                )
                frames >= BIOS_TEST_FRAMES -> setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_BIOS_OK, true)
                )
                else -> setResult(RESULT_CANCELED)
            }
            if (!isFinishing) finish()
        }
    }

    /** Back/Menu during the BIOS test: quit quietly, report "backed out". */
    private fun abortBiosTest() {
        testAborted = true
        quitRequested = true
    }

    // ---------- audio ----------

    private fun startAudio() {
        try {
            var rate = try {
                EmuBridge.nativeGetAudioRate()
            } catch (e: Throwable) {
                44100
            }
            if (rate <= 0) rate = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // About one second of stereo 16-bit audio.
            val bufSize = maxOf(minBuf * 2, rate * 4)
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
                AudioTrack.MODE_STREAM
            )
            track.play()
            audioTrack = track
            audioThread = Thread({
                val buf = ShortArray(4096)
                while (!quitRequested) {
                    val n = try {
                        EmuBridge.nativeDrainAudio(buf, buf.size)
                    } catch (e: Throwable) {
                        -1
                    }
                    if (n > 0) {
                        try {
                            track.write(buf, 0, n)
                        } catch (e: Throwable) {
                            // Ignore.
                        }
                    } else {
                        try {
                            Thread.sleep(5)
                        } catch (e: InterruptedException) {
                            // Ignore.
                        }
                    }
                }
            }, "aeon-audio")
            audioThread!!.start()
        } catch (e: Throwable) {
            // Audio is best-effort; the game still runs without it.
        }
    }

    private fun stopAudio() {
        try {
            audioTrack?.stop()
        } catch (e: Throwable) {
            // Ignore.
        }
        try {
            audioTrack?.release()
        } catch (e: Throwable) {
            // Ignore.
        }
        audioTrack = null
        val t = audioThread
        audioThread = null
        try {
            t?.join(2000)
        } catch (e: Throwable) {
            // Ignore.
        }
    }

    // ---------- gamepad ----------

    private fun padIdFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Pad.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Pad.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Pad.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Pad.RIGHT
        KeyEvent.KEYCODE_BUTTON_A -> Pad.CROSS // libretro B
        KeyEvent.KEYCODE_BUTTON_B -> Pad.CIRCLE // libretro A
        KeyEvent.KEYCODE_BUTTON_X -> Pad.SQUARE // libretro X
        KeyEvent.KEYCODE_BUTTON_Y -> Pad.TRIANGLE // libretro Y
        KeyEvent.KEYCODE_BUTTON_L1 -> Pad.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> Pad.R1
        KeyEvent.KEYCODE_BUTTON_L2 -> Pad.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> Pad.R2
        KeyEvent.KEYCODE_BUTTON_SELECT -> Pad.SELECT
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Pad.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Pad.R3
        // KEYCODE_BUTTON_START is handled in onKeyDown/onKeyUp
        // directly (quick press -> core, long press -> pause menu),
        // never through this table.
        else -> null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            // START is an in-game button first: a quick press goes to
            // the CORE, a long press (~600 ms) opens the app pause menu.
            // Nothing is sent on key-down — key-up decides which it was,
            // so a long-press can never leak a press to the core.
            if (biosTest) {
                if (event.repeatCount == 0) abortBiosTest()
                return true
            }
            if (event.repeatCount == 0) {
                startLongFired = false
                inputHandler.postDelayed(startLongRunnable, START_LONG_PRESS_MS)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_BUTTON_MODE ||
            keyCode == KeyEvent.KEYCODE_BACK
        ) {
            // During the BIOS test there is no pause menu: backing out
            // just ends the test quietly.
            if (biosTest) {
                if (event.repeatCount == 0) abortBiosTest()
                return true
            }
            // A plain Back press opens the pause menu: the Fire TV
            // remote has no Start button, so this is how remote-only
            // users pause. Holding Back repeats with repeatCount > 0
            // and lands on the same (idempotent) openMenu().
            if (event.repeatCount == 0) openMenu()
            return true
        }
        val id = padIdFor(keyCode)
        if (id != null) {
            try {
                EmuBridge.nativeSetPad(0, id, true)
            } catch (e: Throwable) {
                // Ignore.
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            inputHandler.removeCallbacks(startLongRunnable)
            if (startLongFired) {
                // The long-press already opened the pause menu and the
                // core never saw this press: swallow the release so
                // nothing leaks to the core.
                startLongFired = false
            } else if (!biosTest && !quitRequested) {
                // Quick tap: re-emit as a clean press held long enough
                // for the core's per-frame input poll to see it.
                try {
                    EmuBridge.nativeSetPad(0, Pad.START, true)
                } catch (e: Throwable) {
                    // Ignore.
                }
                inputHandler.postDelayed({
                    try {
                        EmuBridge.nativeSetPad(0, Pad.START, false)
                    } catch (e: Throwable) {
                        // Ignore.
                    }
                }, START_TAP_RELEASE_MS)
            }
            return true
        }
        val id = padIdFor(keyCode)
        if (id != null) {
            try {
                EmuBridge.nativeSetPad(0, id, false)
            } catch (e: Throwable) {
                // Ignore.
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            // Left stick -> left analog pair.
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            try {
                EmuBridge.nativeSetAnalog(0, (x * 32767).toInt(), (y * 32767).toInt())
            } catch (e: Throwable) {
                // Ignore.
            }
            // Right stick (v1.0.1) -> the core's
            // RETRO_DEVICE_INDEX_ANALOG_RIGHT pair. Modern gamepads
            // report it as AXIS_Z/AXIS_RZ; older/generic controllers
            // use AXIS_RX/AXIS_RY instead — probe the device and use
            // whichever it actually has.
            val device = event.device
            val (rxAxis, ryAxis) = if (device != null &&
                device.getMotionRange(MotionEvent.AXIS_Z) != null
            ) {
                MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
            } else {
                MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
            }
            val rx = event.getAxisValue(rxAxis)
            val ry = event.getAxisValue(ryAxis)
            try {
                EmuBridge.nativeSetAnalogRight(0, (rx * 32767).toInt(), (ry * 32767).toInt())
            } catch (e: Throwable) {
                // Ignore.
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---------- in-game menu ----------

    private fun openMenu() {
        if (menuOpen || quitRequested || biosTest) return
        menuOpen = true
        pausedByMenu = true
        val switchLabel = if (mode == Store.MODE_FAT) {
            getString(R.string.switch_to_slim)
        } else {
            getString(R.string.switch_to_fat)
        }
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.paused_title))
                .setMessage(getString(R.string.pause_start_help))
                .setItems(
                    arrayOf(
                        getString(R.string.resume),
                        getString(R.string.save_state),
                        getString(R.string.load_state),
                        switchLabel,
                        getString(R.string.quit_to_library)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> { /* Resume: the dismiss listener unpauses. */ }
                        1 -> confirmSaveState()
                        2 -> doLoadState()
                        3 -> switchMode()
                        4 -> quit()
                    }
                }
                .setOnDismissListener {
                    menuOpen = false
                    if (!quitRequested) pausedByMenu = false
                }
                .show()
        } catch (e: Exception) {
            menuOpen = false
            pausedByMenu = false
        }
    }

    /** Save states live per game and per mode: a Fat state never loads in Slim. */
    private fun stateFile(): File {
        val dir = File(File(SystemSetup.statesRoot(filesDir), stateGameKey()), mode)
            .apply { mkdirs() }
        return File(dir, "slot0.state")
    }

    private fun stateGameKey(): String {
        val base = try {
            File(originalPath).nameWithoutExtension
        } catch (e: Exception) {
            "game"
        }
        val clean = base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(24)
            .ifEmpty { "game" }
        val hash = try {
            MessageDigest.getInstance("SHA-256")
                .digest(originalPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
        } catch (e: Exception) {
            "000000000000"
        }
        return "$clean-$hash"
    }

    /** A PS2 save state is ~96 MB: warn before writing one. */
    private fun confirmSaveState() {
        val estBytes = try {
            EmuBridge.nativeStateSize()
        } catch (e: Throwable) {
            0L
        }
        val sizeText = if (estBytes > 0) {
            ExplorerLogic.formatSize(estBytes)
        } else {
            getString(R.string.about_96mb)
        }
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.save_state_title))
                .setMessage(getString(R.string.save_state_warn, sizeText))
                .setPositiveButton(getString(R.string.save)) { _, _ -> doSaveState() }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun doSaveState() {
        val ok = try {
            EmuBridge.nativeSaveState(stateFile().absolutePath)
        } catch (e: Throwable) {
            false
        }
        Toast.makeText(
            this,
            if (ok) getString(R.string.state_saved) else getString(R.string.state_save_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun doLoadState() {
        val f = stateFile()
        if (!f.isFile) {
            Toast.makeText(this, getString(R.string.no_state_yet), Toast.LENGTH_SHORT).show()
            return
        }
        val ok = try {
            EmuBridge.nativeLoadState(f.absolutePath)
        } catch (e: Throwable) {
            false
        }
        Toast.makeText(
            this,
            if (ok) getString(R.string.state_loaded) else getString(R.string.state_load_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Switching mode restarts the whole emulation with the other profile.
     * The :emu process only writes the request file; the UI process
     * (MainActivity) remembers the new mode and relaunches us.
     */
    private fun switchMode() {
        val other = if (mode == Store.MODE_FAT) Store.MODE_SLIM else Store.MODE_FAT
        try {
            ModeRelaunch.write(filesDir, originalPath, other)
        } catch (e: Exception) {
            // Ignore.
        }
        quit()
    }

    private fun quit() {
        quitRequested = true
        // The emu thread shuts down native, clears the sentinel, and finishes.
    }

    // ---------- failure dialog (never a bare toast for failures) ----------

    private fun failDialog(step: String) {
        val f = File(originalPath)
        val name = try {
            f.name
        } catch (e: Exception) {
            originalPath
        }
        val sizeText = try {
            if (f.exists()) getString(R.string.bytes_fmt, f.length()) else getString(R.string.unknown)
        } catch (e: Exception) {
            getString(R.string.unknown)
        }
        val log = try {
            EmuBridge.nativeGetRingLog().takeLast(4000)
        } catch (e: Throwable) {
            getString(R.string.log_unavailable)
        }
        val msg = "$step\n\n" +
            "${getString(R.string.fail_file)}: $name\n" +
            "${getString(R.string.fail_path)}: ${f.absolutePath}\n" +
            "${getString(R.string.fail_size)}: $sizeText\n\n" +
            "--- ${getString(R.string.core_log)} ---\n$log"
        runOnUiThread {
            try {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.fail_title))
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.back_to_library)) { _, _ ->
                        try {
                            CrashSentinel.clear(filesDir)
                        } catch (e: Exception) {
                            // Ignore.
                        }
                        // A failed BIOS test reports back as not-started.
                        if (biosTest) setResult(RESULT_CANCELED)
                        finish()
                    }
                    .show()
            } catch (e: Exception) {
                try {
                    if (biosTest) setResult(RESULT_CANCELED)
                    finish()
                } catch (e2: Exception) {
                    // Ignore.
                }
            }
        }
    }
}

/**
 * Fat/Slim switch request, written by the :emu process and consumed by the
 * UI process (MainActivity). File-based like [CrashSentinel]: the :emu
 * process never touches SharedPreferences.
 */
object ModeRelaunch {
    private const val DIR_NAME = "emu"
    private const val FILE_NAME = "relaunch.txt"

    fun write(filesDir: File, path: String, mode: String) {
        try {
            File(File(filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)
                .writeText("$path\n$mode")
        } catch (e: Exception) {
            // Best effort.
        }
    }

    /** Reads and deletes the request; null when there is none. */
    fun take(filesDir: File): Pair<String, String>? {
        return try {
            val f = File(File(filesDir, DIR_NAME), FILE_NAME)
            if (!f.isFile) return null
            val lines = f.readText().lines()
            f.delete()
            val path = lines.getOrNull(0).orEmpty()
            val mode = lines.getOrNull(1).orEmpty()
                .takeIf { it == Store.MODE_SLIM } ?: Store.MODE_FAT
            if (path.isBlank()) null else Pair(path, mode)
        } catch (e: Exception) {
            null
        }
    }
}
