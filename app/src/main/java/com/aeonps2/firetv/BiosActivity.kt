package com.aeonps2.firetv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Shows the imported PlayStation 2 BIOS files and lets the user import
 * more, remove them, or test one. A real PS2 BIOS is exactly 4 MB
 * (4,194,304 bytes).
 *
 * Every successful import auto-runs [Test BIOS] exactly once (the
 * [Store.autoTestPending] flag survives restarts, so the test can't be
 * skipped or doubled).
 */
class BiosActivity : Activity() {

    companion object {
        private const val REQ_PICK_BIOS = 101
        private const val REQ_BIOS_TEST = 102
    }

    private lateinit var bannerView: TextView
    private lateinit var statusView: TextView
    private lateinit var removeBtn: Button
    private lateinit var testBtn: Button
    private lateinit var fallbackNoteView: TextView

    /** True while a BIOS test is running in the :emu process. */
    private var biosTestPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.bios_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(20))
        })

        bannerView = TextView(this).apply {
            text = getString(R.string.no_bios_banner)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFFFFD97A.toInt())
            setPadding(0, 0, 0, dp(20))
            visibility = View.GONE
        }
        root.addView(bannerView)

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(0, 0, 0, dp(28))
        }
        root.addView(statusView)

        val importBtn = Button(this).apply {
            text = getString(R.string.import_bios)
            setOnClickListener {
                startActivityForResult(
                    Intent(this@BiosActivity, FileExplorerActivity::class.java)
                        .putExtra(
                            FileExplorerActivity.EXTRA_MODE,
                            FileExplorerActivity.MODE_BIOS_PICK
                        ),
                    REQ_PICK_BIOS
                )
            }
        }
        root.addView(
            importBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        )

        removeBtn = Button(this).apply {
            text = getString(R.string.remove_bios)
            setOnClickListener { confirmRemoveBios() }
        }
        root.addView(
            removeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        )

        testBtn = Button(this).apply {
            text = getString(R.string.test_bios)
            setOnClickListener { startBiosTest() }
        }
        root.addView(
            testBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Plain words on 32-bit TVs: the Play! fallback engine has no
        // no-content boot, so [Test BIOS] can't exist there. The button
        // is replaced by this note.
        fallbackNoteView = TextView(this).apply {
            text = getString(R.string.test_bios_unavailable)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFFFFD97A.toInt())
            setPadding(0, dp(8), 0, dp(16))
            visibility = View.GONE
        }
        root.addView(fallbackNoteView)

        setContentView(root)
        refresh()
        maybeAutoTest()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_BIOS && resultCode == RESULT_OK) {
            val path = data?.getStringExtra(FileExplorerActivity.EXTRA_PATH) ?: return
            importBios(File(path))
            return
        }
        if (requestCode == REQ_BIOS_TEST) {
            biosTestPending = false
            clearSentinel()
            val aborted =
                data?.getBooleanExtra(EmulatorActivity.EXTRA_BIOS_ABORTED, false) == true
            if (!aborted) {
                val rejected =
                    data?.getBooleanExtra(EmulatorActivity.EXTRA_BIOS_REJECTED, false) == true
                val ok = resultCode == RESULT_OK &&
                    data?.getBooleanExtra(EmulatorActivity.EXTRA_BIOS_OK, false) == true &&
                    !rejected
                showTestResult(ok, rejected)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (biosTestPending) {
            // The :emu process died during the test: its sentinel is still
            // here and no result was ever delivered.
            val s = try {
                CrashSentinel.read(filesDir)
            } catch (e: Exception) {
                null
            }
            if (s == "BIOS_TEST") {
                biosTestPending = false
                clearSentinel()
                // The :emu process died during the test; its mirrored log
                // survived. Export it before reporting the result.
                EmuLog.exportLog(this)
                showTestResult(false)
            }
        }
        refresh()
        maybeAutoTest()
    }

    /** True on 32-bit-only TVs, where the Play! fallback engine runs and
     * [Test BIOS] cannot work (Play! crashes on no-content boot). */
    private fun isFallbackEngine(): Boolean =
        Engines.currentEngineCode() == Engines.PLAY

    /** Fires the one automatic Test BIOS owed by a fresh import. */
    private fun maybeAutoTest() {
        if (isFallbackEngine()) {
            // Play! cannot boot without a game: the test would crash the
            // core. Clear the owed flag quietly instead of starting it.
            try {
                Store.setAutoTestPending(this, false)
            } catch (e: Exception) {
                // Ignore.
            }
            return
        }
        val pending = try {
            Store.autoTestPending(this)
        } catch (e: Exception) {
            false
        }
        if (!pending) return
        if (!SystemSetup.hasBios(filesDir)) return
        try {
            Store.setAutoTestPending(this, false)
        } catch (e: Exception) {
            // Ignore.
        }
        startBiosTest()
    }

    /** Boots the PlayStation 2 shell with no game and reports plain words. */
    private fun startBiosTest() {
        biosTestPending = true
        try {
            startActivityForResult(
                Intent(this, EmulatorActivity::class.java)
                    .putExtra(EmulatorActivity.EXTRA_BIOS_TEST, true),
                REQ_BIOS_TEST
            )
        } catch (e: Exception) {
            biosTestPending = false
            showTestResult(false)
        }
    }

    private fun showTestResult(ok: Boolean, rejected: Boolean = false) {
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.bios_title))
                .setMessage(
                    when {
                        ok -> getString(R.string.bios_test_ok)
                        rejected -> getString(R.string.bios_test_rejected)
                        else -> getString(R.string.bios_test_failed)
                    }
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun clearSentinel() {
        try {
            CrashSentinel.clear(filesDir)
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun refresh() {
        val files = try {
            SystemSetup.biosFiles(filesDir)
        } catch (e: Exception) {
            emptyList()
        }
        if (files.isNotEmpty()) {
            try {
                Store.setBiosImported(this, true)
            } catch (e: Exception) {
                // Ignore.
            }
            bannerView.visibility = View.GONE
            statusView.text = files.joinToString("\n") { f ->
                getString(
                    R.string.bios_list_fmt,
                    f.name,
                    ExplorerLogic.formatSize(f.length())
                )
            }
            removeBtn.visibility = View.VISIBLE
            if (isFallbackEngine()) {
                // Play! cannot boot with no content: hide the button the
                // engine can't honor and say why instead.
                testBtn.visibility = View.GONE
                fallbackNoteView.visibility = View.VISIBLE
            } else {
                testBtn.visibility = View.VISIBLE
                fallbackNoteView.visibility = View.GONE
            }
        } else {
            try {
                Store.setBiosImported(this, false)
            } catch (e: Exception) {
                // Ignore.
            }
            bannerView.visibility = View.VISIBLE
            statusView.text = ""
            removeBtn.visibility = View.GONE
            testBtn.visibility = View.GONE
            fallbackNoteView.visibility = View.GONE
        }
    }

    /**
     * The explorer already copied the picked file into the BIOS folder;
     * here it gets validated (warn-only) and the one automatic Test BIOS
     * is scheduled.
     */
    private fun importBios(file: File) {
        if (!file.isFile) {
            Toast.makeText(this, getString(R.string.bios_copy_failed), Toast.LENGTH_SHORT).show()
            return
        }
        if (file.length() != BiosCheck.BIOS_SIZE) {
            try {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.invalid_bios_title))
                    .setMessage(getString(R.string.invalid_bios_msg))
                    .setPositiveButton(getString(R.string.use_it_anyway)) { _, _ ->
                        checkBiosContent(file)
                    }
                    .setNegativeButton(getString(R.string.pick_another)) { _, _ ->
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            // Ignore.
                        }
                    }
                    .show()
            } catch (e: Exception) {
                // Ignore.
            }
            return
        }
        checkBiosContent(file)
    }

    private fun checkBiosContent(file: File) {
        when (BiosCheck.check(file)) {
            BiosCheck.Verdict.GENUINE -> finishImport(file)
            BiosCheck.Verdict.SUSPICIOUS ->
                biosWarning(file, getString(R.string.bios_suspicious_msg))
            BiosCheck.Verdict.INVALID ->
                biosWarning(file, getString(R.string.bios_invalid_msg))
        }
    }

    /** Warn-only: a bad-looking BIOS never blocks import. */
    private fun biosWarning(file: File, msg: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.bios_warn_title))
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.use_it_anyway)) { _, _ -> finishImport(file) }
                .setNegativeButton(getString(R.string.pick_another)) { _, _ ->
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        // Ignore.
                    }
                }
                .show()
        } catch (e: Exception) {
            // Ignore; user can pick again.
        }
    }

    private fun finishImport(file: File) {
        var ok = false
        try {
            Store.setBiosImported(this, true)
            Toast.makeText(
                this,
                getString(R.string.bios_ready, file.name),
                Toast.LENGTH_SHORT
            ).show()
            ok = true
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.bios_copy_failed), Toast.LENGTH_SHORT).show()
        }
        refresh()
        // A fresh BIOS gets tested once, right away. The flag makes it
        // exactly once, even across restarts.
        if (ok) {
            try {
                Store.setAutoTestPending(this, true)
            } catch (e: Exception) {
                // Ignore.
            }
            maybeAutoTest()
        }
    }

    private fun confirmRemoveBios() {
        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.remove_bios))
                .setMessage(getString(R.string.remove_bios_confirm))
                .setPositiveButton(getString(R.string.remove_bios)) { _, _ -> removeBios() }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun removeBios() {
        try {
            for (f in SystemSetup.biosFiles(filesDir)) {
                try {
                    f.delete()
                } catch (e: Exception) {
                    // Ignore.
                }
            }
            Store.setBiosImported(this, false)
            Toast.makeText(this, getString(R.string.bios_removed), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Ignore.
        }
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
