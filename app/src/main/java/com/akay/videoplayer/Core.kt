package com.akay.videoplayer

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.toast(m: String) {
    Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}

fun fileUri(ctx: Context, path: String): Uri =
    FileProvider.getUriForFile(ctx, "com.akay.videoplayer.fileprovider", File(path))

class MediaFile(val path: String, val isAudio: Boolean, val size: Long, val modified: Long) {
    val file: File get() = File(path)
    val title: String get() = file.nameWithoutExtension
    val folderPath: String get() = file.parent ?: ""
}

class FolderItem(val path: String, val name: String, val files: List<MediaFile>)

class FolderStyle(val c1: Int, val c2: Int, val glyph: Int)

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(c: Context) {
        if (sp == null) sp = c.applicationContext.getSharedPreferences("vp", Context.MODE_PRIVATE)
    }

    private fun s(): SharedPreferences = sp!!

    fun getSet(key: String): MutableSet<String> =
        HashSet(s().getStringSet(key, emptySet()) ?: emptySet())

    fun putSet(key: String, v: Set<String>) {
        s().edit().putStringSet(key, HashSet(v)).apply()
    }

    fun getInt(k: String, d: Int): Int = s().getInt(k, d)
    fun putInt(k: String, v: Int) {
        s().edit().putInt(k, v).apply()
    }

    fun getString(k: String, d: String): String = s().getString(k, d) ?: d
    fun putString(k: String, v: String) {
        s().edit().putString(k, v).apply()
    }

    fun hidden(): MutableSet<String> = getSet("hidden")

    fun isFavorite(path: String): Boolean = getSet("fav").contains(path)

    fun toggleFavorite(path: String): Boolean {
        val set = getSet("fav")
        val now = if (set.contains(path)) {
            set.remove(path)
            false
        } else {
            set.add(path)
            true
        }
        putSet("fav", set)
        return now
    }

    fun bookmarks(): List<Pair<String, Long>> {
        val res = ArrayList<Pair<String, Long>>()
        for (e in getSet("bm")) {
            val i = e.indexOf('|')
            if (i > 0) res.add(Pair(e.substring(i + 1), e.substring(0, i).toLongOrNull() ?: 0L))
        }
        return res.sortedBy { it.first + it.second }
    }

    fun addBookmark(path: String, pos: Long) {
        val set = getSet("bm")
        set.add("$pos|$path")
        putSet("bm", set)
    }

    fun removeBookmark(path: String, pos: Long) {
        val set = getSet("bm")
        set.remove("$pos|$path")
        putSet("bm", set)
    }
}

object Fmt {
    fun dur(ms: Long): String {
        val s = maxOf(0L, ms) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    fun size(b: Long): String {
        val kb = b / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 100 -> String.format(Locale.US, "%.0f MB", mb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun age(ms: Long): String {
        val days = (System.currentTimeMillis() - ms) / 86400000L
        return when {
            days <= 0 -> "Today"
            days < 30 -> "${days}d ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))
        }
    }
}

object Library {
    private val VIDEO = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "3g2", "flv", "wmv", "m4v",
        "ts", "mpg", "mpeg", "vob", "mts", "m2ts"
    )
    private val AUDIO = setOf(
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "wma", "mka",
        "m4b", "3ga", "awb"
    )
    private val AUDIO_SKIP = setOf("ringtones", "notifications", "alarms")

    private val PALETTE = listOf(
        Pair("#FF7A59", "#E5384C"), Pair("#4FACFE", "#2F6BFF"), Pair("#43E97B", "#14B87A"),
        Pair("#FA709A", "#E0409A"), Pair("#A18CFF", "#6C4DFF"), Pair("#FFC371", "#FF8A3D"),
        Pair("#30CFD0", "#2B7FD4"), Pair("#F093FB", "#B24DE8")
    )

    fun roots(ctx: Context): List<File> {
        val list = ArrayList<File>()
        val primary = Environment.getExternalStorageDirectory()
        list.add(primary)
        try {
            for (d in ctx.getExternalFilesDirs(null)) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android/")
                if (i > 0) {
                    val r = File(p.substring(0, i))
                    if (r.absolutePath != primary.absolutePath && r.isDirectory) list.add(r)
                }
            }
        } catch (e: Throwable) {
        }
        return list
    }

    fun scan(ctx: Context): List<MediaFile> {
        val out = ArrayList<MediaFile>()
        for (r in roots(ctx)) walk(r, r, out)
        return out
    }

    private fun walk(dir: File, root: File, out: MutableList<MediaFile>) {
        val list = dir.listFiles() ?: return
        for (f in list) if (f.name == ".nomedia") return
        val skipAudio = AUDIO_SKIP.contains(dir.name.lowercase(Locale.ROOT))
        for (f in list) {
            val n = f.name
            if (f.isDirectory) {
                if (n.startsWith(".")) continue
                if (dir.absolutePath == root.absolutePath && n == "Android") {
                    val m = File(f, "media")
                    if (m.isDirectory) walk(m, root, out)
                    continue
                }
                walk(f, root, out)
            } else {
                val dot = n.lastIndexOf('.')
                if (dot <= 0) continue
                val ext = n.substring(dot + 1).lowercase(Locale.ROOT)
                if (ext in VIDEO) {
                    out.add(MediaFile(f.absolutePath, false, f.length(), f.lastModified()))
                } else if (ext in AUDIO && !skipAudio) {
                    out.add(MediaFile(f.absolutePath, true, f.length(), f.lastModified()))
                }
            }
        }
    }

    fun folders(all: List<MediaFile>, audio: Boolean, hidden: Set<String>): List<FolderItem> {
        val map = LinkedHashMap<String, MutableList<MediaFile>>()
        for (f in all) {
            if (f.isAudio != audio) continue
            val fp = f.folderPath
            if (hidden.contains(fp)) continue
            map.getOrPut(fp) { ArrayList() }.add(f)
        }
        val res = ArrayList<FolderItem>()
        for ((k, v) in map) {
            val name = File(k).name
            if (audio && name.equals("CapCut Audio", true)) continue
            res.add(FolderItem(k, name, v))
        }
        res.sortBy { it.name.lowercase(Locale.ROOT) }
        return res
    }

    fun visibleAudio(all: List<MediaFile>, hidden: Set<String>): List<MediaFile> =
        all.filter {
            it.isAudio && !hidden.contains(it.folderPath) &&
                !File(it.folderPath).name.equals("CapCut Audio", true)
        }

    fun sort(list: List<MediaFile>, mode: Int): List<MediaFile> = when (mode) {
        0 -> list.sortedBy { it.file.name.lowercase(Locale.ROOT) }
        1 -> list.sortedByDescending { it.file.name.lowercase(Locale.ROOT) }
        2 -> list.sortedByDescending { it.modified }
        3 -> list.sortedBy { it.modified }
        4 -> list.sortedByDescending { it.size }
        else -> list.sortedBy { it.size }
    }

    fun style(name: String, audio: Boolean): FolderStyle {
        val n = name.lowercase(Locale.ROOT)
        fun st(a: String, b: String, g: Int) = FolderStyle(Color.parseColor(a), Color.parseColor(b), g)
        return when {
            n == "all audio" -> st("#C03BFF", "#9B00E8", G.DISC)
            n.contains("capcut") -> st("#2E3A4B", "#121923", G.SCISSORS)
            n.contains("download") -> st("#FFB300", "#FF6A00", G.DOWNLOAD)
            n.contains("edit") -> st("#8A5CFF", "#5B34E6", G.CLAPPER)
            n.contains("inshot") -> st("#00DD99", "#00B48A", G.FILM)
            n.contains("instagram") -> st("#FF2D75", "#B01CC6", G.APERTURE)
            n.contains("screenshot") || n.contains("screen record") -> st("#00C8FF", "#0072FF", G.IMAGE)
            n.contains("shareit") || n.contains("shareme") -> st("#8BE000", "#27BE3A", G.SHARE)
            n.contains("whatsapp") -> st("#2BE073", "#13A64A", G.CHAT)
            n.contains("telegram") -> st("#35B6F2", "#1C8EDB", G.PLANE)
            n.contains("facebook") || n.contains("messenger") -> st("#3B8BFF", "#1450D8", G.FB)
            n.contains("snapchat") -> st("#FFE600", "#FFC400", G.CAMERA)
            n.contains("youtube") || n.contains("tiktok") || n.contains("snaptube") ||
                n.contains("vidmate") -> st("#FF3B3B", "#D90000", G.PLAY_SQ)
            n.contains("record") -> st("#FF3B5C", "#F5163A", if (audio) G.NOTE else G.MIC)
            n.contains("camera") || n == "dcim" -> st("#5C6BC0", "#3949AB", G.CAMERA)
            n.contains("movie") -> st("#7C4DFF", "#512DA8", G.FILM)
            n.contains("music") || n.contains("song") -> st("#C03BFF", "#9B00E8", G.NOTE)
            n.contains("bluetooth") -> st("#2979FF", "#1565C0", G.SHARE)
            else -> {
                val pr = PALETTE[Math.abs(n.hashCode()) % PALETTE.size]
                st(pr.first, pr.second, if (audio) G.NOTE else G.CLAPPER)
            }
        }
    }
}

object Meta {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val durCache = ConcurrentHashMap<String, Long>()
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun duration(path: String): Long {
        val c = durCache[path]
        if (c != null) return c
        var d = 0L
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(path)
            d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
        } catch (e: Throwable) {
        }
        durCache[path] = d
        return d
    }

    fun loadThumb(f: MediaFile, cb: (Bitmap?, Long) -> Unit) {
        val cached = thumbCache.get(f.path)
        val cd = durCache[f.path]
        if (cached != null && cd != null) {
            cb(cached, cd)
            return
        }
        pool.execute {
            val d = duration(f.path)
            var bmp: Bitmap? = thumbCache.get(f.path)
            if (bmp == null) {
                try {
                    bmp = if (f.isAudio) audioArt(f.path)
                    else ThumbnailUtils.createVideoThumbnail(File(f.path), Size(360, 202), null)
                } catch (e: Throwable) {
                }
                if (bmp != null) thumbCache.put(f.path, bmp)
            }
            val res = bmp
            main.post { cb(res, d) }
        }
    }

    fun audioArt(path: String): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val b = r.embeddedPicture ?: return null
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / s > 500) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            return BitmapFactory.decodeByteArray(b, 0, b.size, o2)
        } catch (e: Throwable) {
            return null
        } finally {
            try {
                r.release()
            } catch (e: Throwable) {
            }
        }
    }
}
