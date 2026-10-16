package com.aeonps2.firetv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridPresenter
import java.io.File

/**
 * Leanback home screen: Fat/Slim mode picker cards, a row of buttons,
 * and the game library grid below.
 *
 * The library scan always runs on a background thread. Tapping a mode
 * card picks the default mode for games that don't remember their own
 * yet; each game remembers its own mode once played (pause menu can
 * switch it per game).
 */
class MainActivity : FragmentActivity() {

    private var gamesFragment: GamesFragment? = null
    private var emptyHint: TextView? = null
    private var gridHolder: FrameLayout? = null
    private var fatCard: Button? = null
    private var slimCard: Button? = null
    private var crashDialogShownFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First-run folder layout (BIOS / resources / memcards / games /
        // saves / states) plus the one-time copy of the core resources.
        Thread({
            try {
                SystemSetup.ensure(this)
            } catch (e: Exception) {
                // Ignore.
            }
        }, "aeon-setup").start()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(28))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTextColor(0xFFFFFFFF.toInt())
        })

        // Which engine this TV will run (PCEE2 on 64-bit, Play! fallback
        // on 32-bit-only). Plain words; also in Settings -> This TV.
        root.addView(TextView(this).apply {
            val engine = Engines.currentEngineCode()
            text = if (engine == Engines.PCEE2) {
                getString(R.string.engine_main_line)
            } else {
                getString(R.string.engine_fallback_line)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (engine == Engines.PCEE2) 0xFF9BE08A.toInt() else 0xFFFFD97A.toInt())
            setPadding(0, 0, 0, dp(8))
        })

        // Big Fat / Slim picker cards: remote-focusable, 1st-grader simple.
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(6))
        }
        fatCard = Button(this).apply {
            setOnClickListener { pickDefaultMode(Store.MODE_FAT) }
        }
        slimCard = Button(this).apply {
            setOnClickListener { pickDefaultMode(Store.MODE_SLIM) }
        }
        for (b in listOf(fatCard!!, slimCard!!)) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = dp(16)
            modes.addView(b, lp)
        }
        root.addView(modes)
        updateModeCards()

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val addBtn = Button(this).apply { text = getString(R.string.add_games) }
        val biosBtn = Button(this).apply { text = getString(R.string.bios) }
        val settingsBtn = Button(this).apply { text = getString(R.string.settings) }
        val logBtn = Button(this).apply { text = getString(R.string.emulator_log) }
        for (b in listOf(addBtn, biosBtn, settingsBtn, logBtn)) {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(16)
            buttons.addView(b, lp)
        }
        addBtn.setOnClickListener {
            startActivity(
                Intent(this, FileExplorerActivity::class.java)
                    .putExtra(FileExplorerActivity.EXTRA_MODE, FileExplorerActivity.MODE_ADD)
            )
        }
        biosBtn.setOnClickListener { startActivity(Intent(this, BiosActivity::class.java)) }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        logBtn.setOnClickListener { startActivity(Intent(this, EmuLogActivity::class.java)) }
        root.addView(buttons)

        emptyHint = TextView(this).apply {
            text = getString(R.string.no_games_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, dp(40), 0, 0)
            visibility = View.GONE
        }
        root.addView(emptyHint)

        gridHolder = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(
            gridHolder,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        setContentView(root)

        val frag = GamesFragment()
        frag.onItemClicked = { item ->
            val mode = try {
                Store.gameMode(this, item.path)
            } catch (e: Exception) {
                Store.MODE_FAT
            }
            startActivity(
                Intent(this, EmulatorActivity::class.java)
                    .putExtra(EmulatorActivity.EXTRA_PATH, item.path)
                    .putExtra(EmulatorActivity.EXTRA_MODE, mode)
            )
        }
        gamesFragment = frag
        supportFragmentManager.beginTransaction().replace(gridHolder!!.id, frag).commit()

        refreshLibrary()
    }

    /** Tapping a mode card makes it the default for new games. */
    private fun pickDefaultMode(mode: String) {
        try {
            Store.setDefaultMode(this, mode)
        } catch (e: Exception) {
            // Ignore.
        }
        updateModeCards()
    }

    private fun updateModeCards() {
        val def = try {
            Store.defaultMode(this)
        } catch (e: Exception) {
            Store.MODE_FAT
        }
        fatCard?.text =
            getString(if (def == Store.MODE_FAT) R.string.mode_fat_picked else R.string.mode_fat)
        slimCard?.text =
            getString(if (def == Store.MODE_SLIM) R.string.mode_slim_picked else R.string.mode_slim)
    }

    override fun onResume() {
        super.onResume()
        // A Fat/Slim switch from the in-game pause menu lands here: the
        // :emu process wrote the request, the UI process owns the relaunch.
        val relaunch = try {
            ModeRelaunch.take(filesDir)
        } catch (e: Exception) {
            null
        }
        if (relaunch != null) {
            try {
                Store.setGameMode(this, relaunch.first, relaunch.second)
            } catch (e: Exception) {
                // Ignore.
            }
            try {
                startActivity(
                    Intent(this, EmulatorActivity::class.java)
                        .putExtra(EmulatorActivity.EXTRA_PATH, relaunch.first)
                        .putExtra(EmulatorActivity.EXTRA_MODE, relaunch.second)
                )
            } catch (e: Exception) {
                // Ignore.
            }
        }
        // If the :emu process died without cleaning up, its sentinel file is
        // still here. Tell the user once, then clear it.
        val last = try {
            CrashSentinel.read(filesDir)
        } catch (e: Exception) {
            null
        }
        if (last == "BIOS_TEST") {
            // The BIOS test owns this case (BiosActivity reports it in plain
            // words); never show the game crash dialog for it.
            clearSentinel()
            crashDialogShownFor = last
        } else if (last != null && crashDialogShownFor != last) {
            crashDialogShownFor = last
            // The :emu process died mid-session; its mirrored log file
            // survived (the bridge flushes every line). Export it where the
            // user can browse it before showing the crash dialog.
            EmuLog.exportLog(this)
            val title = try {
                File(last).nameWithoutExtension.replace('_', ' ')
            } catch (e: Exception) {
                "that game"
            }
            showCrashEvidence(title)
        }
        updateModeCards()
        refreshLibrary()
    }

    /** Evidence screen: what the :emu process was doing when it died. */
    private fun showCrashEvidence(title: String) {
        val logTail = readLastEmuLogTail()
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(20))
            }

            // Lead with plain words about where the emulator died, taken
            // from the last "PHASE <name>" line of its mirrored log.
            val lead = phaseWords(lastPhase())
            if (lead != null) {
                root.addView(TextView(this).apply {
                    text = lead
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTextColor(0xFFFFD97A.toInt())
                    setPadding(0, 0, 0, dp(8))
                })
            }

            root.addView(TextView(this).apply {
                text = getString(R.string.crash_msg, title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(0xFFFFFFFF.toInt())
            })

            root.addView(TextView(this).apply {
                text = getString(R.string.crash_explainer)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(0xFFBBBBBB.toInt())
                setPadding(0, dp(14), 0, dp(14))
            })

            if (logTail != null) {
                root.addView(TextView(this).apply {
                    text = getString(R.string.last_words_label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(0, 0, 0, dp(8))
                })
                val logView = TextView(this).apply {
                    text = logTail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(0xFFCCCCCC.toInt())
                    setBackgroundColor(0xFF1A1A1A.toInt())
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isFocusable = true
                }
                val scroll = android.widget.ScrollView(this)
                scroll.addView(
                    logView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                root.addView(
                    scroll,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(240)
                    )
                )
            }

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.unexpected_shutdown_title))
                .setView(root)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.check_bios)) { _, _ ->
                    clearSentinel()
                    try {
                        startActivity(Intent(this, BiosActivity::class.java))
                    } catch (e: Exception) {
                        // Ignore.
                    }
                }
                .setNegativeButton(getString(R.string.back_to_library)) { _, _ ->
                    clearSentinel()
                }
                .show()
        } catch (e: Exception) {
            // Never let the evidence screen itself become a crash loop.
            clearSentinel()
        }
    }

    /**
     * The last "PHASE <name>" marker the emulator wrote before dying,
     * or null when the log has none (the line is then simply omitted).
     */
    private fun lastPhase(): String? {
        return try {
            val f = File(cacheDir, "last_emu_log.txt")
            if (!f.isFile) return null
            var last: String? = null
            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("PHASE ")) {
                        last = line.removePrefix("PHASE ").trim().ifEmpty { null }
                    }
                }
            }
            last
        } catch (e: Exception) {
            null
        }
    }

    /** Plain words for a phase marker; null when there is no marker. */
    private fun phaseWords(phase: String?): String? = when (phase) {
        "INIT" -> getString(R.string.phase_init)
        "CORE_READY" -> getString(R.string.phase_core_ready)
        "LOAD_BEGIN" -> getString(R.string.phase_load_begin)
        "FIRST_FRAME" -> getString(R.string.phase_first_frame)
        "RUNNING" -> getString(R.string.phase_running)
        else -> null
    }

    /** Last ~30 lines of the mirrored emu log, or null if unreadable. */
    private fun readLastEmuLogTail(): String? {
        return try {
            val f = File(cacheDir, "last_emu_log.txt")
            if (!f.isFile) return null
            val tail = f.readBytes().takeLast(16384).toByteArray()
            val lines = tail.toString(Charsets.UTF_8).lines()
            lines.takeLast(30).joinToString("\n")
        } catch (e: Exception) {
            null
        }
    }

    private fun clearSentinel() {
        try {
            CrashSentinel.clear(filesDir)
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun refreshLibrary() {
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
            val folders = if (gamesDir != null) setOf(gamesDir) else emptySet()
            val items = try {
                GameScanner.scan(folders, added) { path ->
                    try {
                        Store.gameMode(this, path)
                    } catch (e: Exception) {
                        Store.MODE_FAT
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                gamesFragment?.setItems(items)
                val empty = items.isEmpty()
                emptyHint?.visibility = if (empty) View.VISIBLE else View.GONE
                gridHolder?.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }, "aeon-scan").start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Single-column game grid; no headers, just cards. */
    class GamesFragment : VerticalGridSupportFragment() {
        private val gridAdapter = ArrayObjectAdapter(GameCardPresenter())
        var onItemClicked: ((GameScanner.Entry) -> Unit)? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val grid = VerticalGridPresenter()
            grid.setNumberOfColumns(4)
            setGridPresenter(grid)
            setAdapter(gridAdapter)
            setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
                (item as? GameScanner.Entry)?.let { onItemClicked?.invoke(it) }
            })
        }

        fun setItems(items: List<GameScanner.Entry>) {
            gridAdapter.clear()
            if (items.isNotEmpty()) gridAdapter.addAll(0, items)
        }
    }

    /** Big D-pad-friendly card: format chip on top, title, mode below. */
    class GameCardPresenter : Presenter() {
        private data class CardViews(
            val chip: TextView,
            val title: TextView,
            val subtitle: TextView
        )

        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                isFocusable = true
                isFocusableInTouchMode = true
                background = ctx.getDrawable(R.drawable.card_bg)
            }
            val chip = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF062A33.toInt())
                setBackgroundColor(0xFF35C4DC.toInt())
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            val title = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(10), 0, 0)
            }
            val subtitle = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF9FB3C8.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            }
            root.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(
                title,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            root.addView(
                subtitle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            root.layoutParams = ViewGroup.LayoutParams(dp(260), dp(150))
            root.tag = CardViews(chip, title, subtitle)
            return Presenter.ViewHolder(root)
        }

        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any) {
            val views = viewHolder.view.tag as CardViews
            val entry = item as GameScanner.Entry
            val ctx = viewHolder.view.context
            views.chip.text = entry.format
            views.title.text = entry.title
            views.subtitle.text = if (entry.mode == Store.MODE_SLIM) {
                ctx.getString(R.string.mode_name_slim)
            } else {
                ctx.getString(R.string.mode_name_fat)
            }
        }

        override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
            // Nothing to release.
        }
    }
}
