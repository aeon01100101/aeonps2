package com.aeonps2.firetv

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Scrollable viewer for the last emulator session's log.
 *
 * D-pad friendly: the log body is focusable inside a ScrollView, so
 * up/down arrows scroll it, and it takes focus automatically on open.
 * Long logs are capped at the last [EmuLog.VIEW_MAX_LINES] lines /
 * [EmuLog.VIEW_MAX_BYTES] bytes so the viewer can't OOM.
 *
 * The header shows the game the log belongs to and its disc serial,
 * extracted by grepping the file (see [EmuLog.summarize]).
 */
class EmuLogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.emulator_log)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(16))
        })

        val summary = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(12))
        }

        val body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFDDDDDD.toInt())
            // Focusable so D-pad up/down scrolls the content; takes focus
            // on open so scrolling works right away.
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(body)
        }

        root.addView(summary)
        root.addView(scroll)
        setContentView(root)

        val text = try {
            val f = EmuLog.internalLogFile(cacheDir)
            if (!f.isFile) null else cappedRead(f)
        } catch (e: Exception) {
            null
        }

        if (text.isNullOrEmpty()) {
            summary.text = getString(R.string.emu_log_no_log)
            body.text = ""
        } else {
            val s = EmuLog.summarize(text)
            summary.text = getString(
                R.string.emu_log_game_fmt,
                s.gamePath ?: getString(R.string.unknown)
            ) + "\n" + getString(
                R.string.emu_log_serial_fmt,
                s.serial ?: getString(R.string.unknown)
            )
            body.text = text
        }

        body.post { body.requestFocus() }
    }

    /** Last VIEW_MAX_LINES lines of the file, capped at VIEW_MAX_BYTES. */
    private fun cappedRead(file: File): String {
        val bytes = file.readBytes()
        val trimmed = bytes.size > EmuLog.VIEW_MAX_BYTES
        val tail = if (trimmed) {
            bytes.copyOfRange(bytes.size - EmuLog.VIEW_MAX_BYTES, bytes.size)
        } else {
            bytes
        }
        var lines = tail.toString(Charsets.UTF_8).lines()
        // A byte-trimmed tail starts mid-line; drop the partial first line.
        if (trimmed && lines.isNotEmpty()) lines = lines.drop(1)
        return lines.takeLast(EmuLog.VIEW_MAX_LINES).joinToString("\n")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
