package com.aeonps2.firetv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * SAF-based importer for the TV, D-pad friendly.
 *
 * Modes come in through the String extra "mode":
 *  - "add" (default): the user picks a game file; it is copied into
 *    <filesDir>/games/ and registered in the library.
 *  - "bios_pick": the user picks a .bin; it is copied into
 *    <filesDir>/system/pcsx2/bios/ and its path returns through
 *    EXTRA_PATH for BiosActivity to validate and adopt.
 *
 * Storage Access Framework needs no file permission: the system picker
 * grants one-shot read access to exactly the file the user chose.
 */
class FileExplorerActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_ADD = "add"
        const val MODE_BIOS_PICK = "bios_pick"
        const val EXTRA_PATH = "path"
        private const val REQ_PICK = 201
    }

    private var biosPick = false
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var pickBtn: Button

    @Volatile private var copying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biosPick = intent.getStringExtra(EXTRA_MODE) == MODE_BIOS_PICK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(40))
        }

        root.addView(TextView(this).apply {
            text = getString(
                if (biosPick) R.string.bios_pick_title else R.string.pick_game_title
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(20))
        })

        root.addView(TextView(this).apply {
            text = getString(
                if (biosPick) R.string.bios_pick_explain else R.string.pick_game_explain
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFFDDDDDD.toInt())
            setPadding(0, 0, 0, dp(28))
        })

        pickBtn = Button(this).apply {
            text = getString(R.string.pick_file)
            setOnClickListener { launchPicker() }
        }
        root.addView(
            pickBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(24) }
        )

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        )

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF9FB3C8.toInt())
            visibility = View.GONE
        }
        root.addView(statusView)

        setContentView(root)
    }

    private fun launchPicker() {
        if (copying) return
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.copy_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        copyPicked(uri)
    }

    /** Copies the picked document into our own folder on a worker thread. */
    private fun copyPicked(uri: Uri) {
        if (copying) return
        copying = true
        pickBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressBar.progress = 0
        statusView.visibility = View.VISIBLE
        statusView.text = getString(R.string.copying)

        Thread({
            var ok = false
            var destPath: String? = null
            var failReason: String? = null
            try {
                val (displayName, size) = queryNameAndSize(uri)
                val safeName = ExplorerLogic.sanitizeName(displayName.ifEmpty { "game" })
                val destDir = if (biosPick) {
                    SystemSetup.biosDir(filesDir).apply { mkdirs() }
                } else {
                    SystemSetup.gamesDir(filesDir).apply { mkdirs() }
                }
                val dest = ExplorerLogic.uniqueDest(destDir, safeName)
                copyStream(uri, dest, size)
                destPath = dest.absolutePath
                ok = true
            } catch (e: Exception) {
                failReason = e.message
            }
            val finalOk = ok
            val finalPath = destPath
            runOnUiThread {
                copying = false
                if (isFinishing) return@runOnUiThread
                progressBar.visibility = View.GONE
                if (finalOk && finalPath != null) {
                    onCopyDone(finalPath)
                } else {
                    pickBtn.isEnabled = true
                    statusView.text = getString(R.string.copy_failed)
                    if (failReason != null) {
                        statusView.text = "${getString(R.string.copy_failed)}: $failReason"
                    }
                }
            }
        }, "aeon-import").start()
    }

    private fun onCopyDone(destPath: String) {
        if (biosPick) {
            // BiosActivity validates, adopts, and auto-tests.
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, destPath))
            finish()
            return
        }
        try {
            Store.addGame(this, destPath)
        } catch (e: Exception) {
            // Ignore; the file is in the games folder either way.
        }
        Toast.makeText(this, getString(R.string.added_to_library), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = ""
        var size = -1L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: ""
                    if (si >= 0) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            // Ignore.
        }
        return Pair(name, size)
    }

    private fun copyStream(uri: Uri, dest: File, totalSize: Long) {
        contentResolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1024 * 1024)
                var copied = 0L
                var lastUi = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    // Throttle UI updates to ~4/sec.
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 250) {
                        lastUi = now
                        val done = copied
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            if (totalSize > 0) {
                                progressBar.isIndeterminate = false
                                progressBar.progress =
                                    ((done * 100) / totalSize).toInt().coerceIn(0, 100)
                                statusView.text = getString(
                                    R.string.copying_fmt,
                                    ExplorerLogic.formatSize(done),
                                    ExplorerLogic.formatSize(totalSize)
                                )
                            } else {
                                statusView.text = getString(
                                    R.string.copying_unknown_fmt,
                                    ExplorerLogic.formatSize(done)
                                )
                            }
                        }
                    }
                }
                out.flush()
            }
        } ?: throw IllegalStateException("openInputStream returned null")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
