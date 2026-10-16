package com.aeonps2.firetv

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings: which PlayStation 2 each mode is, what this TV can do, the
 * game library with disc serials, and the emulator log viewer.
 *
 * Everything here is plain language. The Fat profile is the tested path;
 * Slim is labelled experimental until the bench proves it.
 */
class SettingsActivity : Activity() {

    private var gamesView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
        }

        addTitle(root, getString(R.string.settings_title))

        // ---- Fat profile ----
        addSection(root, getString(R.string.fat_section))
        addLine(root, getString(R.string.fat_desc), 0xFFDDDDDD.toInt())
        // Virtual hard drive: not in v1.0 — greyed out, not hidden, with
        // the plain reason why.
        addLine(root, getString(R.string.hdd_row), 0xFF777777.toInt(), dimmed = true)

        // ---- Slim profile ----
        addSection(root, getString(R.string.slim_section))
        addLine(root, getString(R.string.slim_desc), 0xFFDDDDDD.toInt())

        // ---- Buttons ----
        addSection(root, getString(R.string.buttons_section))
        addLine(root, getString(R.string.buttons_desc), 0xFFDDDDDD.toInt())

        // ---- This TV ----
        addSection(root, getString(R.string.this_tv))
        addLine(root, getString(R.string.abis_fmt, Build.SUPPORTED_ABIS.joinToString(", ")),
            0xFFDDDDDD.toInt())
        addLine(root, getString(R.string.ram_fmt, ExplorerLogic.formatSize(totalRam())),
            0xFFDDDDDD.toInt())
        // Which engine this TV will actually run, and why (plain words).
        if (Engines.currentEngineCode() == Engines.PCEE2) {
            addLine(root, getString(R.string.engine_main_line), 0xFF9BE08A.toInt())
        } else {
            addLine(root, getString(R.string.engine_fallback_line), 0xFFFFD97A.toInt())
        }
        addLine(root, getString(R.string.tv_note), 0xFFBBBBBB.toInt())

        // ---- Games + serials ----
        addSection(root, getString(R.string.games_section))
        gamesView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(0, 0, 0, dp(16))
            text = getString(R.string.loading)
        }
        root.addView(gamesView)
        loadGameSerials()

        // ---- Emulator log ----
        val logBtn = Button(this).apply { text = getString(R.string.emulator_log) }
        logBtn.setOnClickListener {
            startActivity(Intent(this, EmuLogActivity::class.java))
        }
        root.addView(
            logBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        addLine(root, getString(R.string.about_no_content), 0xFFBBBBBB.toInt())

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun loadGameSerials() {
        Thread({
            val gamesDir = try {
                SystemSetup.gamesDir(filesDir).absolutePath
            } catch (e: Exception) {
                null
            }
            val added = try {
                Store.games(this)
            } catch (e: Exception) {
                emptySet()
            }
            val entries = try {
                GameScanner.scan(
                    if (gamesDir != null) setOf(gamesDir) else emptySet(),
                    added
                )
            } catch (e: Exception) {
                emptyList()
            }
            val text = if (entries.isEmpty()) {
                getString(R.string.no_games_hint)
            } else {
                entries.joinToString("\n\n") { e ->
                    val serial =
                        if (e.serial.isNotEmpty()) e.serial else getString(R.string.serial_unknown)
                    val modeName = if (e.mode == Store.MODE_SLIM) {
                        getString(R.string.mode_name_slim)
                    } else {
                        getString(R.string.mode_name_fat)
                    }
                    getString(R.string.game_serial_fmt, e.title, serial, modeName)
                }
            }
            runOnUiThread {
                if (!isFinishing) gamesView?.text = text
            }
        }, "aeon-serials").start()
    }

    /** Total device RAM in bytes; 0 when unreadable. */
    private fun totalRam(): Long {
        return try {
            val am = getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        } catch (e: Exception) {
            0L
        }
    }

    private fun addTitle(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(24))
        })
    }

    private fun addSection(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(0xFF35C4DC.toInt())
            setPadding(0, dp(12), 0, dp(8))
            isFocusable = true
        })
    }

    private fun addLine(root: LinearLayout, text: String, color: Int, dimmed: Boolean = false) {
        root.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(color)
            setPadding(0, 0, 0, dp(16))
            if (dimmed) alpha = 0.55f
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
