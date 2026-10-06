package com.akay.videoplayer

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FileAdapter(
    private val items: List<MediaFile>,
    private val onClick: (Int) -> Unit,
    private val onMore: (MediaFile) -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {
    class VH(val row: MediaRow) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val r = MediaRow(parent.context)
        r.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return VH(r)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        h.row.bind(items[pos])
        h.row.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onClick(p)
        }
        h.row.more.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p >= 0) onMore(items[p])
        }
    }
}

class MainActivity : ComponentActivity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val GREEN = Color.parseColor("#14B800")
    private val NAV = Color.parseColor("#0A0B1E")

    private var all: List<MediaFile> = emptyList()
    private var loaded = false
    private var loading = false
    private var screen = "tabs"
    private var curTab = 0
    private var openKind = "path"
    private var openPath = ""
    private var openAudio = false
    private var openTitle = ""

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---------- helpers ----------

    private fun lp(w: Int, h: Int, wt: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, wt)

    private fun rr(color: Int, r: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(r).toFloat()
        return d
    }

    private fun oval(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    private fun bgGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#2A1048"), Color.parseColor("#0C0E26"), Color.parseColor("#1E1650"))
    )

    private fun setScreen(content: View, wrapBg: Drawable?, top: Int, bottom: Int, lightBars: Boolean) {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        if (wrapBg != null) wrap.background = wrapBg
        val tp = View(this)
        tp.setBackgroundColor(top)
        val bp = View(this)
        bp.setBackgroundColor(bottom)
        wrap.addView(tp, lp(MATCH, 0))
        wrap.addView(content, lp(MATCH, 0, 1f))
        wrap.addView(bp, lp(MATCH, 0))
        ViewCompat.setOnApplyWindowInsetsListener(wrap) { _, ins ->
            val sb = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            (tp.layoutParams as LinearLayout.LayoutParams).height = sb.top
            (bp.layoutParams as LinearLayout.LayoutParams).height = maxOf(sb.bottom, ime.bottom)
            tp.requestLayout()
            bp.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
        setContentView(wrap)
        val c = WindowInsetsControllerCompat(window, wrap)
        c.isAppearanceLightStatusBars = lightBars
        c.isAppearanceLightNavigationBars = lightBars
        ViewCompat.requestApplyInsets(wrap)
    }

    private fun hasAll(): Boolean = Environment.isExternalStorageManager()

    // ---------- lifecycle ----------

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        PlayerManager.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        try {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
        } catch (e: Throwable) {
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == "tabs" || screen == "perm") finish() else showTabs()
            }
        })
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        showTabs()
    }

    override fun onResume() {
        super.onResume()
        if (!hasAll()) {
            if (screen != "perm") showPermission()
            return
        }
        if (!loaded || LibraryState.dirty) reload() else if (screen == "perm") showTabs()
    }

    private fun reload() {
        if (loading) return
        loading = true
        if (!loaded) showTabs()
        Thread {
            val res = try {
                Library.scan(this)
            } catch (e: Throwable) {
                emptyList()
            }
            runOnUiThread {
                all = res
                loaded = true
                loading = false
                LibraryState.dirty = false
                refreshScreen()
            }
        }.start()
    }

    private fun refreshScreen() {
        when (screen) {
            "list" -> showList()
            "tabs", "perm" -> showTabs()
            else -> {
            }
        }
    }

    // ---------- permission screen ----------

    private fun showPermission() {
        screen = "perm"
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        c.setPadding(dp(36), dp(36), dp(36), dp(36))
        c.addView(GlyphView(this, G.FOLDER), lp(dp(90), dp(90)))
        val t = TextView(this)
        t.text = "Allow file access"
        t.textSize = 28f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(24), 0, dp(10))
        c.addView(t, lp(MATCH, WRAP))
        val s = TextView(this)
        s.text = "To show every video and music folder on your phone, this app needs All files access. Tap the button and turn it on."
        s.textSize = 16f
        s.setTextColor(Color.parseColor("#9AA0CC"))
        s.gravity = Gravity.CENTER
        c.addView(s, lp(MATCH, WRAP))
        val b = TextView(this)
        b.text = "Allow access"
        b.textSize = 18f
        b.setTypeface(null, Typeface.BOLD)
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.CENTER
        b.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#7B2FFF"), Color.parseColor("#1E5BFF"))).also {
            it.cornerRadius = dp(30).toFloat()
        }
        b.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Throwable) {
                    toast("Open Settings > Apps > Video Player > Permissions")
                }
            }
        }
        val bl = lp(MATCH, dp(58))
        bl.topMargin = dp(32)
        c.addView(b, bl)
        setScreen(c, bgGradient(), Color.TRANSPARENT, Color.TRANSPARENT, false)
    }

    // ---------- tabs ----------

    private fun iconBtn(glyph: Int, color: Int, bg: Int?, sizeDp: Int, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        if (bg != null) f.background = oval(bg)
        f.addView(GlyphView(this, glyph, color), FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        return f
    }

    private fun folderCard(name: String, sub: String, audio: Boolean, onClick: () -> Unit, onMore: (() -> Unit)?): View {
        val st = Library.style(name, audio)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(16), dp(16), dp(8), dp(16))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#1D2048"))
        bg.cornerRadius = dp(24).toFloat()
        bg.setStroke(dp(1), Color.parseColor("#2B2F63"))
        card.background = bg
        val lpc = lp(MATCH, WRAP)
        lpc.topMargin = dp(10)
        card.layoutParams = lpc
        val tile = FrameLayout(this)
        val tb = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(st.c1, st.c2))
        tb.cornerRadius = dp(18).toFloat()
        tile.background = tb
        tile.addView(GlyphView(this, st.glyph, if (name.lowercase().contains("snapchat")) Color.BLACK else Color.WHITE), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        card.addView(tile, lp(dp(58), dp(58)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), 0, dp(8), 0)
        val t = TextView(this)
        t.text = name
        t.textSize = 20f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.WHITE)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t, lp(MATCH, WRAP))
        val s = TextView(this)
        s.text = sub
        s.textSize = 16f
        s.setTextColor(Color.parseColor("#9AA0CC"))
        s.setPadding(0, dp(3), 0, 0)
        col.addView(s, lp(MATCH, WRAP))
        card.addView(col, lp(0, WRAP, 1f))
        if (onMore != null) card.addView(iconBtn(G.MORE_V, Color.parseColor("#8A90B8"), null, 26) { onMore() }, lp(dp(44), dp(44)))
        card.setOnClickListener { onClick() }
        return card
    }

    private fun showTabs() {
        screen = "tabs"
        val audio = curTab == 1
        val hidden = Prefs.hidden()
        val folders = Library.folders(all, audio, hidden)
        val audioAll = Library.visibleAudio(all, hidden)
        val videoCount = folders.sumOf { it.files.size }

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        val hdr = LinearLayout(this)
        hdr.orientation = LinearLayout.HORIZONTAL
        hdr.gravity = Gravity.CENTER_VERTICAL
        hdr.setPadding(dp(24), dp(28), dp(24), dp(14))
        val hc = LinearLayout(this)
        hc.orientation = LinearLayout.VERTICAL
        val ht = TextView(this)
        ht.text = if (audio) "Music" else "Video"
        ht.textSize = 38f
        ht.setTypeface(null, Typeface.BOLD)
        ht.setTextColor(Color.WHITE)
        hc.addView(ht, lp(WRAP, WRAP))
        val hs = TextView(this)
        hs.text = when {
            !loaded -> "Scanning..."
            audio -> "${folders.size + 1} folders \u00B7 ${audioAll.size} files"
            else -> "${folders.size} folders \u00B7 $videoCount files"
        }
        hs.textSize = 17f
        hs.setTextColor(Color.parseColor("#7F8CFF"))
        hc.addView(hs, lp(WRAP, WRAP))
        hdr.addView(hc, lp(0, WRAP, 1f))
        val base = if (audio) audioAll else all.filter { !it.isAudio && !hidden.contains(it.folderPath) }
        hdr.addView(iconBtn(G.SEARCH, Color.WHITE, Color.parseColor("#272A55"), 26) { showSearch(base) }, lp(dp(52), dp(52)))
        val mb = iconBtn(G.MORE_V, Color.WHITE, Color.parseColor("#272A55"), 26) { mainMenu() }
        val ml = lp(dp(52), dp(52))
        ml.leftMargin = dp(10)
        hdr.addView(mb, ml)
        content.addView(hdr, lp(MATCH, WRAP))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(18), dp(4), dp(18), dp(16))
        if (!loaded) {
            val e = TextView(this)
            e.text = "Scanning your media..."
            e.textSize = 16f
            e.setTextColor(Color.parseColor("#9AA0CC"))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(60), 0, 0)
            list.addView(e, lp(MATCH, WRAP))
        } else {
            if (audio && audioAll.isNotEmpty()) {
                val lim = System.currentTimeMillis() - 7L * 86400000L
                val rc = audioAll.count { it.modified >= lim }
                list.addView(recentBanner(rc))
                list.addView(
                    folderCard("All Audio", "${audioAll.size} tracks", true,
                        { openFolder("allaudio", "", true, "All Audio") },
                        { folderSheet("All Audio", audioAll, false, "", true) }), lp(MATCH, WRAP)
                )
            }
            for (f in folders) {
                val sub = f.files.size.toString() + if (audio) " tracks" else " videos"
                list.addView(
                    folderCard(f.name, sub, audio,
                        { openFolder("path", f.path, audio, f.name) },
                        { folderSheet(f.name, f.files, true, f.path, audio) }), lp(MATCH, WRAP)
                )
            }
            if (folders.isEmpty() && !(audio && audioAll.isNotEmpty())) {
                val e = TextView(this)
                e.text = if (audio) "No music found" else "No videos found"
                e.textSize = 16f
                e.setTextColor(Color.parseColor("#9AA0CC"))
                e.gravity = Gravity.CENTER
                e.setPadding(0, dp(60), 0, 0)
                list.addView(e, lp(MATCH, WRAP))
            }
        }
        val sv = ScrollView(this)
        sv.addView(list)
        content.addView(sv, lp(MATCH, 0, 1f))
        content.addView(buildNav(), lp(MATCH, WRAP))
        setScreen(content, bgGradient(), Color.TRANSPARENT, NAV, false)
    }

    private fun recentBanner(count: Int): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(18), dp(18), dp(18), dp(18))
        val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#7B2FFF"), Color.parseColor("#1E5BFF")))
        bg.cornerRadius = dp(26).toFloat()
        card.background = bg
        card.elevation = dp(8).toFloat()
        card.outlineSpotShadowColor = Color.parseColor("#7B2FFF")
        val lpc = lp(MATCH, WRAP)
        lpc.setMargins(0, dp(4), 0, dp(10))
        card.layoutParams = lpc
        val tile = FrameLayout(this)
        tile.background = rr(Color.parseColor("#33FFFFFF"), 18)
        tile.addView(GlyphView(this, G.CLOCK), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        card.addView(tile, lp(dp(58), dp(58)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), 0, dp(8), 0)
        val t = TextView(this)
        t.text = "Recently Added"
        t.textSize = 21f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.WHITE)
        col.addView(t, lp(WRAP, WRAP))
        val s = TextView(this)
        s.text = if (count > 0) "$count tracks added" else "No new tracks"
        s.textSize = 16f
        s.setTextColor(Color.parseColor("#D8D8FF"))
        col.addView(s, lp(WRAP, WRAP))
        card.addView(col, lp(0, WRAP, 1f))
        card.addView(GlyphView(this, G.CHEVRON, Color.parseColor("#D8D8FF")), lp(dp(28), dp(28)))
        card.setOnClickListener { openFolder("recent", "", true, "Recently Added") }
        return card
    }

    private fun buildNav(): View {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(NAV)
        nav.setPadding(dp(12), dp(10), dp(12), dp(12))
        for (i in 0..1) {
            val sel = curTab == i
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            val c = FrameLayout(this)
            if (sel) c.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#C04DFF"), Color.parseColor("#6A3DFF"))).also { it.shape = GradientDrawable.OVAL }
            val gl = if (i == 0) G.CLAPPER else G.NOTE
            c.addView(GlyphView(this, gl, if (sel) Color.WHITE else Color.parseColor("#8A8FB5")), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            item.addView(c, lp(dp(54), dp(54)))
            val t = TextView(this)
            t.text = if (i == 0) "Video" else "Music"
            t.textSize = 16f
            t.setTextColor(if (sel) Color.WHITE else Color.parseColor("#8A8FB5"))
            if (sel) t.setTypeface(null, Typeface.BOLD)
            t.setPadding(0, dp(4), 0, 0)
            item.addView(t, lp(WRAP, WRAP))
            item.setOnClickListener {
                curTab = i
                showTabs()
            }
            nav.addView(item, lp(0, WRAP, 1f))
        }
        return nav
    }

    private fun mainMenu() {
        showSheet(this, "Menu", listOf(
            SheetItem(G.CLOCK, "Refresh") {
                LibraryState.dirty = true
                reload()
            },
            SheetItem(G.HEART, "Favorites") { openFolder("favs", "", false, "Favorites") },
            SheetItem(G.BOOKMARK, "Bookmarks") { showBookmarks() },
            SheetItem(G.EYE_OFF, "Hidden folders") { showHidden() }
        ))
    }

    // ---------- playing ----------

    private fun play(files: List<MediaFile>, index: Int, pos: Long = 0L) {
        if (files.isEmpty()) return
        PlayerManager.exitBackground(this)
        PlayerManager.repeatKind = Prefs.getInt("repeat", 0)
        PlayerManager.start(files, index, pos, true)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun bgPlay(files: List<MediaFile>) {
        if (files.isEmpty()) return
        PlayerManager.exitBackground(this)
        PlayerManager.repeatKind = Prefs.getInt("repeat", 0)
        PlayerManager.start(Library.sort(files, Prefs.getInt("sort", 2)), 0, 0L, true)
        PlayerManager.enterBackground(this)
        toast("Playing in background")
    }

    // ---------- file lists (light screens) ----------

    private fun openFolder(kind: String, path: String, audio: Boolean, title: String) {
        openKind = kind
        openPath = path
        openAudio = audio
        openTitle = title
        showList()
    }

    private fun currentFiles(): List<MediaFile> {
        val hidden = Prefs.hidden()
        val base: List<MediaFile> = when (openKind) {
            "path" -> all.filter { it.folderPath == openPath && it.isAudio == openAudio }
            "allaudio" -> Library.visibleAudio(all, hidden)
            "recent" -> {
                val lim = System.currentTimeMillis() - 7L * 86400000L
                Library.visibleAudio(all, hidden).filter { it.modified >= lim }
            }
            else -> all.filter { Prefs.isFavorite(it.path) }
        }
        return if (openKind == "recent") base.sortedByDescending { it.modified }
        else Library.sort(base, Prefs.getInt("sort", 2))
    }

    private fun lightBar(title: String, search: (() -> Unit)?, more: (() -> Unit)?): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(10), dp(8), dp(10))
        bar.addView(iconBtn(G.BACK, Color.parseColor("#1A1A1A"), null, 28) { showTabs() }, lp(dp(48), dp(48)))
        val t = TextView(this)
        t.text = title
        t.textSize = 24f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.parseColor("#1A1A1A"))
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.setPadding(dp(8), 0, dp(8), 0)
        bar.addView(t, lp(0, WRAP, 1f))
        if (search != null) bar.addView(iconBtn(G.SEARCH, Color.parseColor("#1A1A1A"), null, 26) { search() }, lp(dp(48), dp(48)))
        if (more != null) bar.addView(iconBtn(G.MORE_V, Color.parseColor("#1A1A1A"), null, 26) { more() }, lp(dp(48), dp(48)))
        return bar
    }

    private fun showList() {
        screen = "list"
        val files = currentFiles()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)
        root.addView(lightBar(openTitle, { showSearch(files) }, { sortSheet() }), lp(MATCH, WRAP))
        if (files.isEmpty()) {
            val e = TextView(this)
            e.text = "Nothing here yet"
            e.textSize = 16f
            e.setTextColor(Color.parseColor("#888888"))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(80), 0, 0)
            root.addView(e, lp(MATCH, WRAP))
        } else {
            val rv = RecyclerView(this)
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = FileAdapter(files, { i -> play(files, i) }, { f -> fileSheet(f) })
            root.addView(rv, lp(MATCH, 0, 1f))
        }
        setScreen(root, null, Color.WHITE, Color.WHITE, true)
    }

    private fun sortSheet() {
        val names = listOf("Name (A to Z)", "Name (Z to A)", "Newest first", "Oldest first", "Largest first", "Smallest first")
        showSheet(this, "Sort by", names.mapIndexed { i, n ->
            SheetItem(G.ORDER, n) {
                Prefs.putInt("sort", i)
                showList()
            }
        })
    }

    private fun showSearch(base: List<MediaFile>) {
        screen = "search"
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(10), dp(16), dp(10))
        bar.addView(iconBtn(G.BACK, Color.parseColor("#1A1A1A"), null, 28) { showTabs() }, lp(dp(48), dp(48)))
        val et = EditText(this)
        et.hint = "Search"
        et.textSize = 18f
        et.setSingleLine(true)
        et.setTextColor(Color.parseColor("#1A1A1A"))
        et.setHintTextColor(Color.parseColor("#999999"))
        et.background = null
        bar.addView(et, lp(0, WRAP, 1f))
        root.addView(bar, lp(MATCH, WRAP))
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        root.addView(rv, lp(MATCH, 0, 1f))
        fun refresh(q: String) {
            val r = if (q.isBlank()) base else base.filter { it.file.name.contains(q.trim(), true) }
            rv.adapter = FileAdapter(r, { i -> play(r, i) }, { f -> fileSheet(f) })
        }
        refresh("")
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh(s?.toString() ?: "")
            }
        })
        setScreen(root, null, Color.WHITE, Color.WHITE, true)
    }

    private fun simpleRows(title: String, rows: List<Triple<String, String, () -> Unit>>, longPress: ((Int) -> Unit)?) {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)
        root.addView(lightBar(title, null, null), lp(MATCH, WRAP))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (rows.isEmpty()) {
            val e = TextView(this)
            e.text = "Nothing here yet"
            e.textSize = 16f
            e.setTextColor(Color.parseColor("#888888"))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(80), 0, 0)
            col.addView(e, lp(MATCH, WRAP))
        }
        for ((i, r) in rows.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(20), dp(14), dp(20), dp(14))
            val t = TextView(this)
            t.text = r.first
            t.textSize = 17f
            t.setTextColor(Color.parseColor("#1A1A1A"))
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            row.addView(t, lp(MATCH, WRAP))
            val s = TextView(this)
            s.text = r.second
            s.textSize = 14f
            s.setTextColor(Color.parseColor("#7A7A7A"))
            row.addView(s, lp(MATCH, WRAP))
            row.setOnClickListener { r.third() }
            if (longPress != null) {
                row.setOnLongClickListener {
                    longPress(i)
                    true
                }
            }
            col.addView(row, lp(MATCH, WRAP))
        }
        val sv = ScrollView(this)
        sv.addView(col)
        root.addView(sv, lp(MATCH, 0, 1f))
        setScreen(root, null, Color.WHITE, Color.WHITE, true)
    }

    private fun showBookmarks() {
        screen = "bookmarks"
        val list = Prefs.bookmarks().filter { File(it.first).exists() }
        val rows = list.map { b ->
            Triple(File(b.first).name, "at " + Fmt.dur(b.second) + "  (long press to remove)") {
                val f = all.find { it.path == b.first }
                if (f != null) play(listOf(f), 0, b.second) else toast("File not found")
            }
        }
        simpleRows("Bookmarks", rows) { i ->
            val b = list[i]
            val dlg = AlertDialog.Builder(this).setMessage("Remove this bookmark?")
                .setPositiveButton("Remove") { _, _ ->
                    Prefs.removeBookmark(b.first, b.second)
                    showBookmarks()
                }.setNegativeButton("Cancel", null).create()
            dlg.show()
            dlg.tintButtons(GREEN)
        }
    }

    private fun showHidden() {
        screen = "hidden"
        val list = Prefs.hidden().toList().sorted()
        val rows = list.map { p ->
            Triple(File(p).name, p + "  (tap to unhide)") {
                val dlg = AlertDialog.Builder(this).setMessage("Show this folder again?")
                    .setPositiveButton("Unhide") { _, _ ->
                        val h = Prefs.hidden()
                        h.remove(p)
                        Prefs.putSet("hidden", h)
                        showHidden()
                    }.setNegativeButton("Cancel", null).create()
                dlg.show()
                dlg.tintButtons(GREEN)
            }
        }
        simpleRows("Hidden folders", rows, null)
    }

    // ---------- sheets and file actions ----------

    private fun folderSheet(title: String, files: List<MediaFile>, real: Boolean, path: String, audio: Boolean) {
        val items = ArrayList<SheetItem>()
        items.add(SheetItem(G.HEADPHONE, "Background Play") { bgPlay(files) })
        if (real) {
            items.add(SheetItem(G.EYE_OFF, "Hide from Scan List") {
                val h = Prefs.hidden()
                h.add(path)
                Prefs.putSet("hidden", h)
                toast("Hidden. Show it again from Menu > Hidden folders")
                showTabs()
            })
            items.add(SheetItem(G.PENCIL, "Rename") { renameFolder(path, title) })
            items.add(SheetItem(G.TRASH, "Delete") { deleteFiles(files, path, "this folder (" + files.size + " files)") })
        }
        items.add(SheetItem(G.SHARE, "Share") { shareFiles(files) })
        showSheet(this, title, items)
    }

    private fun fileSheet(f: MediaFile) {
        val fav = Prefs.isFavorite(f.path)
        showSheet(this, f.file.name, listOf(
            SheetItem(G.HEADPHONE, "Background Play") { bgPlay(listOf(f)) },
            SheetItem(G.HEART, if (fav) "Remove from Favorites" else "Add to Favorites") {
                Prefs.toggleFavorite(f.path)
                refreshScreen()
            },
            SheetItem(G.PENCIL, "Rename") { renameFile(f) },
            SheetItem(G.TRASH, "Delete") { deleteFiles(listOf(f), null, "\"" + f.file.name + "\"") },
            SheetItem(G.SHARE, "Share") { shareFiles(listOf(f)) }
        ))
    }

    private fun cleanName(s: String): String = s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun nameDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.setText(initial)
        et.setSelectAllOnFocus(true)
        val box = FrameLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(et)
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = cleanName(et.text.toString())
                if (n.isNotEmpty()) onOk(n)
            }.setNegativeButton("Cancel", null).create()
        dlg.show()
        dlg.tintButtons(GREEN)
    }

    private fun renameFile(f: MediaFile) {
        nameDialog("Rename", f.file.nameWithoutExtension) { n ->
            val dst = File(f.file.parentFile, n + "." + f.file.extension)
            if (dst.exists()) {
                toast("A file with this name already exists")
            } else if (f.file.renameTo(dst)) {
                MediaScannerConnection.scanFile(this, arrayOf(f.path, dst.absolutePath), null, null)
                toast("Renamed")
                LibraryState.dirty = true
                reload()
            } else {
                toast("Rename failed")
            }
        }
    }

    private fun renameFolder(path: String, name: String) {
        nameDialog("Rename folder", name) { n ->
            val src = File(path)
            val dst = File(src.parentFile, n)
            if (dst.exists()) {
                toast("A folder with this name already exists")
            } else if (src.renameTo(dst)) {
                toast("Renamed")
                screen = "tabs"
                LibraryState.dirty = true
                reload()
            } else {
                toast("Rename failed")
            }
        }
    }

    private fun deleteFiles(files: List<MediaFile>, folderPath: String?, label: String) {
        val dlg = AlertDialog.Builder(this).setMessage("Delete $label?")
            .setPositiveButton("Delete") { _, _ ->
                var n = 0
                val paths = ArrayList<String>()
                for (f in files) {
                    val ok = try {
                        f.file.delete()
                    } catch (e: Throwable) {
                        false
                    }
                    if (ok) {
                        n++
                        paths.add(f.path)
                    }
                }
                if (folderPath != null) {
                    val d = File(folderPath)
                    if (d.isDirectory && d.list()?.isEmpty() == true) d.delete()
                    screen = "tabs"
                }
                if (paths.isNotEmpty()) MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
                toast("$n deleted")
                LibraryState.dirty = true
                reload()
            }.setNegativeButton("Cancel", null).create()
        dlg.show()
        dlg.tintButtons(GREEN)
    }

    private fun shareFiles(files: List<MediaFile>) {
        if (files.isEmpty()) return
        try {
            val list = files.take(50)
            val uris = ArrayList<Uri>()
            for (f in list) uris.add(fileUri(this, f.path))
            val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            i.type = if (list.all { it.isAudio }) "audio/*" else if (list.none { it.isAudio }) "video/*" else "*/*"
            if (uris.size == 1) i.putExtra(Intent.EXTRA_STREAM, uris[0]) else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
            if (files.size > 50) toast("Only the first 50 files were shared")
        } catch (e: Throwable) {
            toast("Could not share")
        }
    }
}
