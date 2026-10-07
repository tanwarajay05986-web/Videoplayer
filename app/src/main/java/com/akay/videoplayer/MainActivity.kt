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
    private val NAV = Color.parseColor("#0A0714")
    private val DIM = Color.parseColor("#8F89B0")

    private var all: List<MediaFile> = emptyList()
    private var loaded = false
    private var loading = false
    private var screen = "tabs"
    private var curTab = 0
    private var folderFilter = 0
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
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#1A1438"), Color.parseColor("#0E0A24"), Color.parseColor("#080512"))
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

    // ---------- tabs (image 1 style) ----------

    private fun iconBtn(glyph: Int, color: Int, bg: Int?, sizeDp: Int, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        if (bg != null) f.background = oval(bg)
        f.addView(GlyphView(this, glyph, color), FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
        f.setOnClickListener { onClick() }
        return f
    }

    private fun chip(text: String, glyph: Int, sel: Boolean, onClick: () -> Unit): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(14), dp(8), dp(16), dp(8))
        if (sel) {
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#E43CF7"), Color.parseColor("#A855F7")))
            g.cornerRadius = dp(24).toFloat()
            c.background = g
        } else {
            val g = GradientDrawable()
            g.cornerRadius = dp(24).toFloat()
            g.setColor(Color.TRANSPARENT)
            g.setStroke(dp(1), Color.parseColor("#3B3361"))
            c.background = g
        }
        val col = if (sel) Color.WHITE else Color.parseColor("#B9B3DB")
        c.addView(GlyphView(this, glyph, col), lp(dp(20), dp(20)))
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(col)
        if (sel) t.setTypeface(null, Typeface.BOLD)
        t.setPadding(dp(8), 0, 0, 0)
        c.addView(t, lp(WRAP, WRAP))
        c.setOnClickListener { onClick() }
        return c
    }

    private fun folderCard(
        name: String, sub: String, audio: Boolean, most: Boolean, fav: Boolean?,
        onStar: (() -> Unit)?, onClick: () -> Unit, onLong: (() -> Unit)?
    ): View {
        val st = Library.style(name, audio)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(12), dp(11), dp(8), dp(11))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#231C3D"))
        bg.cornerRadius = dp(20).toFloat()
        bg.setStroke(dp(1), Color.parseColor("#32295A"))
        card.background = bg
        val lpc = lp(MATCH, WRAP)
        lpc.topMargin = dp(9)
        card.layoutParams = lpc

        val tile = FrameLayout(this)
        val tb = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(st.c1, st.c2))
        tb.cornerRadius = dp(16).toFloat()
        tile.background = tb
        val gcol = if (name.lowercase().contains("snapchat")) Color.BLACK else Color.WHITE
        tile.addView(GlyphView(this, st.glyph, gcol), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        card.addView(tile, lp(dp(50), dp(50)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, dp(6), 0)
        val t = TextView(this)
        t.text = name
        t.textSize = 18f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.WHITE)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t, lp(MATCH, WRAP))
        val subRow = LinearLayout(this)
        subRow.orientation = LinearLayout.HORIZONTAL
        subRow.gravity = Gravity.CENTER_VERTICAL
        val s = TextView(this)
        s.text = sub
        s.textSize = 14f
        s.setTextColor(Color.parseColor("#A9A4C9"))
        subRow.addView(s, lp(WRAP, WRAP))
        if (most) {
            val bd = TextView(this)
            bd.text = "\uD83D\uDD25 Most files"
            bd.textSize = 12f
            bd.setTextColor(Color.parseColor("#FFC83D"))
            bd.setPadding(dp(8), dp(2), dp(8), dp(2))
            bd.background = rr(Color.parseColor("#3A2C16"), 12)
            val bl = lp(WRAP, WRAP)
            bl.leftMargin = dp(8)
            subRow.addView(bd, bl)
        }
        col.addView(subRow, lp(MATCH, WRAP))
        card.addView(col, lp(0, WRAP, 1f))

        if (fav != null) {
            val star = iconBtn(
                if (fav) G.STAR_FILL else G.STAR,
                if (fav) Color.parseColor("#FFC107") else DIM, null, 26
            ) { onStar?.invoke() }
            card.addView(star, lp(dp(40), dp(40)))
        }
        card.addView(GlyphView(this, G.CHEVRON, DIM), lp(dp(22), dp(22)))
        card.setOnClickListener { onClick() }
        if (onLong != null) {
            card.setOnLongClickListener {
                onLong()
                true
            }
        }
        return card
    }

    private fun readyBanner(count: Int): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(14), dp(12), dp(16), dp(12))
        val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#B01FDB"), Color.parseColor("#5A4BEA")))
        bg.cornerRadius = dp(22).toFloat()
        card.background = bg
        val lpc = lp(MATCH, WRAP)
        lpc.topMargin = dp(4)
        card.layoutParams = lpc
        val tile = FrameLayout(this)
        tile.background = rr(Color.parseColor("#33FFFFFF"), 14)
        tile.addView(GlyphView(this, G.DRIVE), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        card.addView(tile, lp(dp(44), dp(44)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, dp(8), 0)
        val t = TextView(this)
        t.text = "$count files ready"
        t.textSize = 18f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(Color.WHITE)
        col.addView(t, lp(WRAP, WRAP))
        val s = TextView(this)
        s.text = "Tap a folder to explore"
        s.textSize = 14f
        s.setTextColor(Color.parseColor("#E6D8FF"))
        col.addView(s, lp(WRAP, WRAP))
        card.addView(col, lp(0, WRAP, 1f))
        card.addView(GlyphView(this, G.SPARKLE, Color.parseColor("#FFD84D")), lp(dp(30), dp(30)))
        return card
    }

    private fun showTabs() {
        screen = "tabs"
        val audio = curTab == 1
        val hidden = Prefs.hidden()
        val visibleFiles = Library.visible(all, audio, hidden)
        val folders = Library.folders(all, audio, hidden)
        val favs = Prefs.favFolders()
        val shown = if (folderFilter == 1) folders.filter { favs.contains(it.path) } else folders
        val maxCount = folders.maxOfOrNull { it.files.size } ?: 0
        val firstMax = folders.firstOrNull { it.files.size == maxCount }

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        val hdr = LinearLayout(this)
        hdr.orientation = LinearLayout.HORIZONTAL
        hdr.gravity = Gravity.CENTER_VERTICAL
        hdr.setPadding(dp(22), dp(22), dp(22), dp(12))
        val hc = LinearLayout(this)
        hc.orientation = LinearLayout.VERTICAL
        val ht = TextView(this)
        ht.text = if (audio) "Music" else "Video"
        ht.textSize = 36f
        ht.setTypeface(null, Typeface.BOLD)
        ht.setTextColor(Color.WHITE)
        hc.addView(ht, lp(WRAP, WRAP))
        val hs = TextView(this)
        hs.text = if (!loaded) "Scanning..." else "${folders.size} folders \u00B7 ${visibleFiles.size} files"
        hs.textSize = 16f
        hs.setTextColor(Color.parseColor("#8B8FF5"))
        hc.addView(hs, lp(WRAP, WRAP))
        hdr.addView(hc, lp(0, WRAP, 1f))
        hdr.addView(iconBtn(G.SEARCH, Color.WHITE, Color.parseColor("#2D2950"), 24) { showSearch(visibleFiles) }, lp(dp(48), dp(48)))
        val ml = lp(dp(48), dp(48))
        ml.leftMargin = dp(10)
        hdr.addView(iconBtn(G.MORE_V, Color.WHITE, Color.parseColor("#2D2950"), 24) { mainMenu() }, ml)
        content.addView(hdr, lp(MATCH, WRAP))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(18), dp(2), dp(18), dp(16))
        if (!loaded) {
            val e = TextView(this)
            e.text = "Scanning your media..."
            e.textSize = 16f
            e.setTextColor(Color.parseColor("#9AA0CC"))
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(60), 0, 0)
            list.addView(e, lp(MATCH, WRAP))
        } else {
            list.addView(readyBanner(visibleFiles.size))
            val chips = LinearLayout(this)
            chips.orientation = LinearLayout.HORIZONTAL
            chips.setPadding(0, dp(12), 0, dp(2))
            chips.addView(chip("All folders", G.GRID, folderFilter == 0) {
                folderFilter = 0
                showTabs()
            }, lp(WRAP, WRAP))
            val cl = lp(WRAP, WRAP)
            cl.leftMargin = dp(10)
            chips.addView(chip("Favorites", G.FOLDER_HEART, folderFilter == 1) {
                folderFilter = 1
                showTabs()
            }, cl)
            list.addView(chips, lp(MATCH, WRAP))

            if (audio && folderFilter == 0 && visibleFiles.isNotEmpty()) {
                val lim = System.currentTimeMillis() - 7L * 86400000L
                val rc = visibleFiles.count { it.modified >= lim }
                list.addView(
                    folderCard("Recently Added", if (rc > 0) "$rc tracks added" else "No new tracks", true, false, null, null,
                        { openFolder("recent", "", true, "Recently Added") }, null), lp(MATCH, WRAP)
                )
                list.addView(
                    folderCard("All Audio", "${visibleFiles.size} tracks", true, false, null, null,
                        { openFolder("allaudio", "", true, "All Audio") },
                        { folderSheet("All Audio", visibleFiles, false, "", true) }), lp(MATCH, WRAP)
                )
            }
            for (f in shown) {
                val sub = f.files.size.toString() + if (audio) " tracks" else " videos"
                list.addView(
                    folderCard(f.name, sub, audio, f === firstMax && folders.size > 2 && maxCount > 1,
                        favs.contains(f.path),
                        {
                            Prefs.toggleFavFolder(f.path)
                            showTabs()
                        },
                        { openFolder("path", f.path, audio, f.name) },
                        { folderSheet(f.name, f.files, true, f.path, audio) }), lp(MATCH, WRAP)
                )
            }
            if (shown.isEmpty()) {
                val e = TextView(this)
                e.text = if (folderFilter == 1) "No favorite folders yet. Tap the star on a folder."
                else if (audio) "No music found" else "No videos found"
                e.textSize = 16f
                e.setTextColor(Color.parseColor("#9AA0CC"))
                e.gravity = Gravity.CENTER
                e.setPadding(dp(20), dp(50), dp(20), 0)
                list.addView(e, lp(MATCH, WRAP))
            }
        }
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(list)
        content.addView(sv, lp(MATCH, 0, 1f))
        content.addView(buildNav(), lp(MATCH, WRAP))
        setScreen(content, bgGradient(), Color.TRANSPARENT, NAV, false)
    }

    private fun buildNav(): View {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(NAV)
        nav.setPadding(dp(12), dp(8), dp(12), dp(10))
        for (i in 0..1) {
            val sel = curTab == i
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            val size = if (sel) 54 else 48
            val c = FrameLayout(this)
            if (sel) {
                val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#C04DFF"), Color.parseColor("#6A3DFF")))
                gd.shape = GradientDrawable.OVAL
                c.background = gd
                c.elevation = dp(8).toFloat()
                c.outlineSpotShadowColor = Color.parseColor("#9B4DFF")
            } else {
                c.background = oval(Color.parseColor("#2A2640"))
            }
            val gl = if (i == 0) G.CLAPPER else G.NOTE
            c.addView(GlyphView(this, gl, if (sel) Color.WHITE else DIM), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            val cl = lp(dp(size), dp(size))
            cl.topMargin = if (sel) 0 else dp(3)
            item.addView(c, cl)
            val t = TextView(this)
            t.text = if (i == 0) "Video" else "Music"
            t.textSize = 15f
            t.setTextColor(if (sel) Color.WHITE else DIM)
            if (sel) t.setTypeface(null, Typeface.BOLD)
            t.setPadding(0, dp(4), 0, 0)
            item.addView(t, lp(WRAP, WRAP))
            item.setOnClickListener {
                curTab = i
                folderFilter = 0
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
