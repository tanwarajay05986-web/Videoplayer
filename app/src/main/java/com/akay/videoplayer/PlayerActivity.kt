package com.akay.videoplayer

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

object LibraryState {
    var dirty = false
}

// vertical strip shown while changing brightness / volume
class StripHud(ctx: Context, glyph: Int, fillColor: Int) : FrameLayout(ctx) {
    private var level = 0f
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        bg.color = Color.parseColor("#E61E1E1E")
        track.color = Color.parseColor("#4A4A4A")
        fill.color = fillColor
        val l = LayoutParams(ctx.dp(28), ctx.dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        l.bottomMargin = ctx.dp(12)
        addView(GlyphView(ctx, glyph, Color.WHITE), l)
        visibility = GONE
    }

    fun setLevel(v: Float) {
        level = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRoundRect(RectF(0f, 0f, w, h), w * 0.12f, w * 0.12f, bg)
        val tw = w * 0.16f
        val left = (w - tw) / 2f
        val top = h * 0.07f
        val bottom = h * 0.70f
        c.drawRoundRect(RectF(left, top, left + tw, bottom), tw / 2f, tw / 2f, track)
        if (level > 0.001f) {
            val ft = bottom - (bottom - top) * level
            c.drawRoundRect(RectF(left, ft, left + tw, bottom), tw / 2f, tw / 2f, fill)
        }
    }
}

// small triangle under the seek preview box
class ArrowView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.color = Color.parseColor("#EB2B2B2B")
    }

    override fun onDraw(c: Canvas) {
        val path = Path()
        path.moveTo(0f, 0f)
        path.lineTo(width.toFloat(), 0f)
        path.lineTo(width / 2f, height.toFloat())
        path.close()
        c.drawPath(path, p)
    }
}

// sound-wave icon (Audio Effect)
class WaveGlyph(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        p.strokeWidth = 0.075f * s
        val hs = floatArrayOf(0.22f, 0.42f, 0.7f, 0.5f, 0.62f, 0.34f, 0.2f)
        for (i in hs.indices) {
            val x = ox + (0.16f + i * 0.113f) * s
            val half = hs[i] * s / 2f
            c.drawLine(x, oy + s / 2f - half, x, oy + s / 2f + half, p)
        }
    }
}

// moon icon (Night Mode)
class MoonGlyph(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var col = Color.WHITE

    init {
        p.style = Paint.Style.FILL
    }

    fun tint(c: Int) {
        col = c
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        p.color = col
        val a = Path()
        a.addCircle(ox + 0.46f * s, oy + 0.54f * s, 0.30f * s, Path.Direction.CW)
        val b = Path()
        b.addCircle(ox + 0.62f * s, oy + 0.42f * s, 0.26f * s, Path.Direction.CW)
        a.op(b, Path.Op.DIFFERENCE)
        c.drawPath(a, p)
        c.drawCircle(ox + 0.74f * s, oy + 0.68f * s, 0.05f * s, p)
        c.drawCircle(ox + 0.60f * s, oy + 0.22f * s, 0.04f * s, p)
    }
}

class PlayerActivity : ComponentActivity() {

    private val GREEN = Color.parseColor("#14B800")
    private val RED = Color.parseColor("#E53935")
    private val DARK_YELLOW = Color.parseColor("#C99700")
    private val RINGBG = Color.parseColor("#802A2A2A")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val main = Handler(Looper.getMainLooper())
    private var p: ExoPlayer? = null

    private lateinit var root: FrameLayout
    private lateinit var arFrame: AspectRatioFrameLayout
    private lateinit var tex: TextureView
    private lateinit var subs: SubtitleView
    private lateinit var artBox: LinearLayout
    private lateinit var artImg: ImageView
    private lateinit var artTitle: TextView
    private lateinit var nightOv: View
    private lateinit var gest: GestureLayer
    private lateinit var hud: TextView
    private lateinit var brightHud: StripHud
    private lateinit var volHud: StripHud
    private lateinit var controls: FrameLayout
    private lateinit var titleTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var curT: TextView
    private lateinit var durT: TextView
    private lateinit var playGlyph: GlyphView
    private lateinit var rotGlyph: GlyphView
    private lateinit var speedChip: TextView
    private lateinit var compactRow: LinearLayout
    private lateinit var exScroll: HorizontalScrollView
    private lateinit var exRow: LinearLayout
    private lateinit var centerG: LinearLayout
    private lateinit var abLabel: TextView
    private lateinit var unlockBtn: View
    private lateinit var btnBack10: View
    private lateinit var btnFwd10: View
    private lateinit var speedWord: View
    private lateinit var panelWrap: FrameLayout
    private lateinit var panelBox: LinearLayout
    private lateinit var brightVal: TextView
    private lateinit var brightSeek: SeekBar
    private lateinit var volVal: TextView
    private lateinit var volSeek: SeekBar
    private lateinit var hwChip: TextView
    private lateinit var swChip: TextView
    private lateinit var shotDot: View
    private lateinit var repeatName: TextView
    private lateinit var pvCard: LinearLayout
    private lateinit var pvImg: ImageView
    private lateinit var pvText: TextView
    private lateinit var pvArrow: View

    private val cellIcons = HashMap<String, GlyphView>()
    private val repeatBtns = ArrayList<FrameLayout>()
    private var transformer: Transformer? = null

    // expanded (arrow) row
    private val exIcons = HashMap<String, GlyphView>()
    private var exMoon: MoonGlyph? = null
    private var expSpeed: TextView? = null
    private var shotCompact: View? = null
    private var shotEx: View? = null
    private var nightOn = false
    private val exOrder = listOf(
        "night", "custom", "shuffle", "loop", "mute", "timer", "ab",
        "effect", "eq", "speed", "shot", "bg", "rot"
    )
    private val exLabels = mapOf(
        "night" to "Night Mode", "custom" to "Customise Items", "shuffle" to "Shuffle",
        "loop" to "Loop", "mute" to "Mute", "timer" to "Sleep Timer", "ab" to "A - B Repeat",
        "effect" to "Audio Effect", "eq" to "Equalizer", "speed" to "Speed",
        "shot" to "Screenshot", "bg" to "Background Play", "rot" to "Screen Rotation"
    )

    private var locked = false
    private var rotLocked = false
    private var seeking = false
    private var showShot = true
    private var cfBright = 0
    private var cfContrast = 100
    private var cfSat = 100
    private var cfWarm = 0
    private var zoom = 1f
    private var volLevelF = -1f
    private var holdPrev = 1f
    private var holdingFast = false
    private var seekStartPos = 0L

    // manual rotate: stay in the chosen orientation until the phone is physically turned
    private var orientListener: OrientationEventListener? = null
    private var waitingPhys = false
    private var wantLand = false

    // seek preview
    private val previewExec = Executors.newSingleThreadExecutor()
    private val prevLock = Any()
    private var previewBusy = false
    private var previewWant = -1L
    private var previewPath = ""
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverPath = ""

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private var resizeIdx = 0
    private val resizeNames = listOf("Fit", "Crop to fill", "Stretch")
    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
    private val repeatNames = listOf("Play In Order", "Repeat One", "Shuffle", "Loop All", "Play Once")

    private val subPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addSubtitle(uri)
    }

    private val hudHide = Runnable {
        hud.visibility = View.GONE
        brightHud.visibility = View.GONE
        volHud.visibility = View.GONE
    }

    // ---------- small helpers ----------

    private fun lp(w: Int, h: Int, wt: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, wt)

    private fun lpm(w: Int, h: Int, left: Int): LinearLayout.LayoutParams {
        val l = lp(w, h)
        l.leftMargin = left
        return l
    }

    private fun lpg(w: Int, h: Int, gap: Int): LinearLayout.LayoutParams {
        val l = lp(w, h)
        l.leftMargin = gap / 2
        l.rightMargin = gap / 2
        return l
    }

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

    private fun simpleSeek(on: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser) on(v)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }

    private fun styleSeek(sb: SeekBar) {
        sb.progressTintList = ColorStateList.valueOf(GREEN)
        sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sb.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#7A7A7A"))
        sb.secondaryProgressTintList = ColorStateList.valueOf(Color.parseColor("#9A9A9A"))
    }

    private fun ring(glyph: Int, sizeDp: Int, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.background = oval(RINGBG)
        val g = dp(sizeDp * 6 / 10)
        f.addView(GlyphView(this, glyph), FrameLayout.LayoutParams(g, g, Gravity.CENTER))
        f.setOnClickListener {
            onClick()
            showControls()
        }
        return f
    }

    // same round button, but with a bigger icon inside (used for the rows under the title)
    private fun ringBig(glyph: Int, sizeDp: Int, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.background = oval(RINGBG)
        val g = dp(sizeDp * 85 / 100)
        f.addView(GlyphView(this, glyph), FrameLayout.LayoutParams(g, g, Gravity.CENTER))
        f.setOnClickListener {
            onClick()
            showControls()
        }
        return f
    }

    private fun glyphOf(v: FrameLayout): GlyphView = v.getChildAt(0) as GlyphView

    private fun plain(glyph: Int, onClick: () -> Unit): GlyphView {
        val g = GlyphView(this, glyph)
        g.setOnClickListener {
            onClick()
            showControls()
        }
        return g
    }

    private fun cur(): MediaFile? = PlayerManager.queue.getOrNull(p?.currentMediaItemIndex ?: 0)

    private fun speedText(s: Float): String =
        (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "X"

    private fun updateSpeedLabels(s: Float) {
        val t = speedText(s)
        speedChip.text = t
        expSpeed?.text = t
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pl = PlayerManager.player
        if (pl == null || PlayerManager.queue.isEmpty()) {
            finish()
            return
        }
        p = pl
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val a = window.attributes
            a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = a
        } catch (e: Throwable) {
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        buildUi()
        setContentView(root)
        hideBars()
        attachPlayer(pl)
        applyOrientationUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    panelWrap.visibility == View.VISIBLE -> hidePanel()
                    locked -> showUnlock()
                    else -> finish()
                }
            }
        })
        main.post(ticker)
        showControls()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        if (PlayerManager.backgroundMode) {
            PlayerManager.exitBackground(this)
            p?.setVideoTextureView(tex)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode && !PlayerManager.backgroundMode) p?.pause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try {
            orientListener?.disable()
        } catch (e: Throwable) {
        }
        p?.removeListener(listener)
        try {
            p?.clearVideoTextureView(tex)
        } catch (e: Throwable) {
        }
        previewExec.execute {
            try {
                retriever?.release()
            } catch (e: Throwable) {
            }
            retriever = null
        }
        previewExec.shutdown()
        if (isFinishing && !PlayerManager.backgroundMode) PlayerManager.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutPanel()
        applyOrientationUi()
        hideBars()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            controls.visibility = View.GONE
            panelWrap.visibility = View.GONE
        } else {
            showControls()
        }
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, root)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // portrait: only lock, previous, play/pause, next, screen size. landscape: everything.
    private fun applyOrientationUi() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val v = if (land) View.VISIBLE else View.GONE
        btnBack10.visibility = v
        btnFwd10.visibility = v
        speedWord.visibility = v
        val g = if (land) dp(44) else dp(30)
        for (i in 0 until centerG.childCount) {
            val c = centerG.getChildAt(i)
            val l = c.layoutParams as LinearLayout.LayoutParams
            l.leftMargin = g / 2
            l.rightMargin = g / 2
            c.layoutParams = l
        }
    }

    private fun restoreOrientation() {
        requestedOrientation = if (rotLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    // ---------- player wiring ----------

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayIcon()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlayIcon()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayIcon()
            if (playbackState == Player.STATE_ENDED) showControls()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            resetAb()
            zoom = 1f
            arFrame.scaleX = 1f
            arFrame.scaleY = 1f
            syncUi()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                var r = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) r = 1f / r
                arFrame.setAspectRatio(r)
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            subs.setCues(cueGroup.cues)
        }

        override fun onPlayerError(error: PlaybackException) {
            toast("Cannot play this file (" + error.errorCodeName + "). Try the SW decoder in the menu.")
        }
    }

    private fun attachPlayer(pl: ExoPlayer) {
        p = pl
        pl.setVideoTextureView(tex)
        pl.addListener(listener)
        syncUi()
        updatePlayIcon()
        updateSpeedLabels(pl.playbackParameters.speed)
        refreshExIcons()
    }

    private fun updatePlayIcon() {
        val pl = p ?: return
        val playing = pl.playWhenReady && pl.playbackState != Player.STATE_ENDED
        playGlyph.setShape(if (playing) G.PAUSE else G.PLAY)
    }

    private fun syncUi() {
        val pl = p ?: return
        val f = PlayerManager.queue.getOrNull(pl.currentMediaItemIndex)
        titleTv.text = f?.file?.name ?: ""
        val audio = f?.isAudio == true
        artBox.visibility = if (audio) View.VISIBLE else View.GONE
        arFrame.visibility = if (audio) View.INVISIBLE else View.VISIBLE
        artTitle.text = f?.title ?: ""
        artImg.setImageBitmap(null)
        if (f != null && audio) {
            Meta.loadThumb(f) { bmp, _ ->
                if (bmp != null && PlayerManager.queue.getOrNull(pl.currentMediaItemIndex)?.path == f.path) {
                    artImg.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun resetAb() {
        PlayerManager.abA = -1L
        PlayerManager.abB = -1L
        abLabel.visibility = View.GONE
        cellIcons["ab"]?.tint(Color.WHITE)
        refreshExIcons()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val pl = p
            if (pl != null) {
                val d = pl.duration
                val pos = pl.currentPosition
                if (!seeking && d > 0) {
                    seek.max = d.toInt()
                    seek.progress = pos.toInt()
                    seek.secondaryProgress = pl.bufferedPosition.toInt()
                }
                if (!seeking) curT.text = Fmt.dur(pos)
                durT.text = if (d > 0) Fmt.dur(d) else "00:00"
                val a = PlayerManager.abA
                val b = PlayerManager.abB
                if (a >= 0 && b > a && pos >= b) pl.seekTo(a)
            }
            main.postDelayed(this, 250)
        }
    }

    private val hideRun = Runnable {
        val pl = p
        if (pl != null && pl.isPlaying && panelWrap.visibility != View.VISIBLE) {
            controls.visibility = View.GONE
            unlockBtn.visibility = View.GONE
        }
    }

    private fun showControls() {
        main.removeCallbacks(hideRun)
        if (locked) {
            showUnlock()
            return
        }
        controls.visibility = View.VISIBLE
        main.postDelayed(hideRun, 4000)
    }

    private fun showUnlock() {
        unlockBtn.visibility = View.VISIBLE
        controls.visibility = View.GONE
        main.removeCallbacks(hideRun)
        main.postDelayed(hideRun, 3000)
    }

    private fun toggleControls() {
        if (locked) {
            if (unlockBtn.visibility == View.VISIBLE) unlockBtn.visibility = View.GONE else showUnlock()
            return
        }
        if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showControls()
    }

    // screen lock: stops clicks AND rotation
    private fun lockScreen() {
        locked = true
        gest.enabledAll = false
        waitingPhys = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        controls.visibility = View.GONE
        showUnlock()
        toast("Screen locked")
    }

    private fun unlock() {
        locked = false
        gest.enabledAll = true
        restoreOrientation()
        unlockBtn.visibility = View.GONE
        toast("Unlocked")
    }

    // ---------- gestures ----------

    private fun showHud(text: String, ms: Long, strip: StripHud? = null, level: Float = 0f) {
        brightHud.visibility = View.GONE
        volHud.visibility = View.GONE
        if (strip != null) {
            strip.setLevel(level)
            strip.visibility = View.VISIBLE
        }
        hud.text = text
        hud.visibility = View.VISIBLE
        main.removeCallbacks(hudHide)
        main.postDelayed(hudHide, ms)
    }

    private fun setupGestures() {
        gest.cbTap = { toggleControls() }
        gest.cbDouble = { right ->
            if (!locked) {
                seekBy(if (right) 10000L else -10000L)
                showHud(if (right) "+10s" else "-10s", 600)
            }
        }
        gest.cbBrightness = { d -> adjustBrightness(d) }
        gest.cbVolume = { d -> adjustVolume(d) }
        gest.cbHold = { on -> holdFast(on) }
        gest.cbZoom = { f -> zoomBy(f) }
        gest.cbZoomEnd = { zoomEnd() }
        gest.cbSwipeEnd = { volLevelF = -1f }
    }

    private fun systemBright(): Float = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Throwable) {
        0.5f
    }

    private fun adjustBrightness(d: Float) {
        val a = window.attributes
        val base = if (a.screenBrightness >= 0f) a.screenBrightness else systemBright()
        val v = (base + d).coerceIn(0.01f, 1f)
        a.screenBrightness = v
        window.attributes = a
        showHud("Brightness :   " + (v * 100).roundToInt() + "%", 900, brightHud, v)
    }

    // volume level 0..200 (above 100 = boost)
    private fun currentVolLevel(): Int {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cv = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(1, max)
        return if (PlayerManager.boostGain > 0) 100 + PlayerManager.boostGain / 12 else cv
    }

    private fun applyVolumeLevel(v: Int) {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(minOf(v, 100) / 100f * max), 0)
        PlayerManager.setBoost(if (v > 100) (v - 100) * 12 else 0)
    }

    private fun adjustVolume(d: Float) {
        if (volLevelF < 0f) volLevelF = currentVolLevel().toFloat()
        volLevelF = (volLevelF + d * 200f).coerceIn(0f, 200f)
        val lvl = volLevelF.roundToInt()
        applyVolumeLevel(lvl)
        showHud("Volume :  $lvl", 900, volHud, lvl / 200f)
    }

    private fun holdFast(on: Boolean) {
        val pl = p ?: return
        if (on) {
            if (!pl.isPlaying) return
            holdPrev = pl.playbackParameters.speed
            pl.setPlaybackSpeed(2f)
            holdingFast = true
            showHud("2X  \u25B6\u25B6", 60000L)
        } else if (holdingFast) {
            pl.setPlaybackSpeed(holdPrev)
            holdingFast = false
            main.removeCallbacks(hudHide)
            hudHide.run()
        }
    }

    private fun zoomBy(f: Float) {
        zoom = (zoom * f).coerceIn(0.5f, 5f)
        arFrame.scaleX = zoom
        arFrame.scaleY = zoom
        showHud((zoom * 100).roundToInt().toString() + "%", 3000L)
    }

    private fun zoomEnd() {
        if (abs(zoom - 1f) < 0.04f) {
            zoom = 1f
            arFrame.scaleX = 1f
            arFrame.scaleY = 1f
            showHud("100%", 350L)
        } else {
            showHud((zoom * 100).roundToInt().toString() + "%", 700L)
        }
    }

    // ---------- UI ----------

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        arFrame = AspectRatioFrameLayout(this)
        tex = TextureView(this)
        arFrame.addView(tex, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(arFrame, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        subs = SubtitleView(this)
        root.addView(subs, FrameLayout.LayoutParams(MATCH, MATCH))

        artBox = LinearLayout(this)
        artBox.orientation = LinearLayout.VERTICAL
        artBox.gravity = Gravity.CENTER
        artBox.visibility = View.GONE
        val artFrame = FrameLayout(this)
        val ab = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#B04DFF"), Color.parseColor("#3D1FB5")))
        ab.cornerRadius = dp(28).toFloat()
        artFrame.background = ab
        artFrame.clipToOutline = true
        artFrame.addView(GlyphView(this, G.NOTE), FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
        artImg = ImageView(this)
        artImg.scaleType = ImageView.ScaleType.CENTER_CROP
        artFrame.addView(artImg, FrameLayout.LayoutParams(MATCH, MATCH))
        artBox.addView(artFrame, lp(dp(190), dp(190)))
        artTitle = TextView(this)
        artTitle.textSize = 18f
        artTitle.setTextColor(Color.WHITE)
        artTitle.gravity = Gravity.CENTER
        artTitle.maxLines = 2
        artTitle.setPadding(dp(24), dp(16), dp(24), 0)
        artBox.addView(artTitle, lp(MATCH, WRAP))
        root.addView(artBox, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        // night mode: warm dark layer over the picture (below the touch layer and the controls)
        nightOv = View(this)
        nightOv.setBackgroundColor(Color.parseColor("#66401A00"))
        nightOv.visibility = View.GONE
        root.addView(nightOv, FrameLayout.LayoutParams(MATCH, MATCH))

        gest = GestureLayer(this)
        setupGestures()
        root.addView(gest, FrameLayout.LayoutParams(MATCH, MATCH))

        buildControls()
        root.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))

        abLabel = TextView(this)
        abLabel.textSize = 15f
        abLabel.setTextColor(Color.WHITE)
        abLabel.setPadding(dp(14), dp(6), dp(14), dp(6))
        abLabel.background = rr(Color.parseColor("#99000000"), 16)
        abLabel.visibility = View.GONE
        val al = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        al.topMargin = dp(8)
        root.addView(abLabel, al)

        brightHud = StripHud(this, G.SUN, RED)
        val bl = FrameLayout.LayoutParams(dp(44), dp(176), Gravity.END or Gravity.CENTER_VERTICAL)
        bl.rightMargin = dp(16)
        root.addView(brightHud, bl)
        volHud = StripHud(this, G.VOLUME, DARK_YELLOW)
        val vl = FrameLayout.LayoutParams(dp(44), dp(176), Gravity.START or Gravity.CENTER_VERTICAL)
        vl.leftMargin = dp(16)
        root.addView(volHud, vl)

        hud = TextView(this)
        hud.textSize = 22f
        hud.setTextColor(Color.WHITE)
        hud.gravity = Gravity.CENTER
        hud.setPadding(dp(18), dp(12), dp(18), dp(12))
        hud.background = rr(Color.parseColor("#E62B2B2B"), 8)
        hud.visibility = View.GONE
        root.addView(hud, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        buildPanel()
        root.addView(panelWrap, FrameLayout.LayoutParams(MATCH, MATCH))

        unlockBtn = ring(G.UNLOCK, 42) { unlock() }
        unlockBtn.visibility = View.GONE
        val ul = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START or Gravity.CENTER_VERTICAL)
        ul.leftMargin = dp(28)
        root.addView(unlockBtn, ul)
    }

    // small row under the title bar (equalizer, 1X, screenshot, headphone, rotate, arrow)
    private fun buildCompact() {
        compactRow = LinearLayout(this)
        compactRow.orientation = LinearLayout.HORIZONTAL
        compactRow.gravity = Gravity.CENTER_VERTICAL
        compactRow.addView(ringBig(G.EQ, 40) { eqDialog() }, lp(dp(40), dp(40)))

        val speedRing = FrameLayout(this)
        speedRing.background = oval(RINGBG)
        speedChip = TextView(this)
        speedChip.text = "1X"
        speedChip.textSize = 17f
        speedChip.setTypeface(null, Typeface.BOLD)
        speedChip.setTextColor(Color.WHITE)
        speedChip.gravity = Gravity.CENTER
        speedRing.addView(speedChip, FrameLayout.LayoutParams(MATCH, MATCH))
        speedRing.setOnClickListener {
            speedDialog()
            showControls()
        }
        compactRow.addView(speedRing, lpm(dp(40), dp(40), dp(12)))

        val shot = ringBig(G.CAMERA, 40) { takeShot() }
        shotCompact = shot
        compactRow.addView(shot, lpm(dp(40), dp(40), dp(12)))
        compactRow.addView(ringBig(G.HEADPHONE, 40) { goBackground() }, lpm(dp(40), dp(40), dp(12)))
        compactRow.addView(ringBig(G.ROTATE, 40) { manualRotate() }, lpm(dp(40), dp(40), dp(12)))
        compactRow.addView(ringBig(G.CHEVRON, 34) { showExpanded(true) }, lpm(dp(34), dp(34), dp(12)))
    }

    private fun exItem(label: String, icon: View, lpi: FrameLayout.LayoutParams, act: () -> Unit): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        val r = FrameLayout(this)
        r.background = oval(RINGBG)
        r.addView(icon, lpi)
        c.addView(r, lp(dp(40), dp(40)))
        val t = TextView(this)
        t.text = label
        t.textSize = 12f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.maxLines = 2
        t.setPadding(0, dp(4), 0, 0)
        c.addView(t, lp(MATCH, WRAP))
        c.setOnClickListener {
            act()
            showControls()
        }
        return c
    }

    // big row that opens when the arrow is tapped (round buttons with names below)
    private fun buildExpanded() {
        exRow.removeAllViews()
        exIcons.clear()
        exMoon = null
        shotEx = null
        expSpeed = null
        val hidden = Prefs.getSet("qhide")
        fun gl(kind: Int, k: String): GlyphView {
            val g = GlyphView(this, kind)
            exIcons[k] = g
            return g
        }
        for (key in exOrder) {
            if (key != "custom" && hidden.contains(key)) continue
            val icon: View
            val act: () -> Unit
            var lpi = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
            when (key) {
                "night" -> {
                    val m = MoonGlyph(this)
                    exMoon = m
                    icon = m
                    act = { toggleNight() }
                }
                "custom" -> {
                    icon = GlyphView(this, G.PENCIL)
                    act = { customiseDialog() }
                }
                "shuffle" -> {
                    icon = gl(G.SHUFFLE, key)
                    act = { toggleShuffle() }
                }
                "loop" -> {
                    icon = gl(G.LOOP, key)
                    act = { cycleLoop() }
                }
                "mute" -> {
                    icon = gl(G.MUTE, key)
                    act = { toggleMute() }
                }
                "timer" -> {
                    icon = GlyphView(this, G.ALARM)
                    act = { timerDialog() }
                }
                "ab" -> {
                    icon = gl(G.AB, key)
                    act = { abRepeat() }
                }
                "effect" -> {
                    icon = WaveGlyph(this)
                    act = { audioEffectDialog() }
                }
                "eq" -> {
                    icon = GlyphView(this, G.EQ)
                    act = { eqDialog() }
                }
                "speed" -> {
                    val tv = TextView(this)
                    tv.text = speedText(p?.playbackParameters?.speed ?: 1f)
                    tv.textSize = 17f
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.setTextColor(Color.WHITE)
                    tv.gravity = Gravity.CENTER
                    expSpeed = tv
                    icon = tv
                    lpi = FrameLayout.LayoutParams(MATCH, MATCH)
                    act = { speedDialog() }
                }
                "shot" -> {
                    icon = GlyphView(this, G.CAMERA)
                    act = { takeShot() }
                }
                "bg" -> {
                    icon = GlyphView(this, G.HEADPHONE)
                    act = { goBackground() }
                }
                else -> {
                    icon = GlyphView(this, G.ROTATE)
                    act = { manualRotate() }
                }
            }
            val item = exItem(exLabels[key] ?: key, icon, lpi, act)
            if (key == "shot") shotEx = item
            exRow.addView(item, lp(dp(76), WRAP))
        }
        val back = ringBig(G.CHEVRON, 34) { showExpanded(false) }
        glyphOf(back).rotation = 180f
        val bl = lp(dp(34), dp(34))
        bl.topMargin = dp(3)
        bl.leftMargin = dp(8)
        exRow.addView(back, bl)
        applyShotVis()
        refreshExIcons()
    }

    private fun applyShotVis() {
        val v = if (showShot) View.VISIBLE else View.GONE
        shotCompact?.visibility = v
        shotEx?.visibility = v
    }

    private fun showExpanded(on: Boolean) {
        compactRow.visibility = if (on) View.GONE else View.VISIBLE
        exScroll.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            refreshExIcons()
            exScroll.scrollTo(0, 0)
        }
    }

    private fun refreshExIcons() {
        val pl = p
        val k = PlayerManager.repeatKind
        exMoon?.tint(if (nightOn) GREEN else Color.WHITE)
        exIcons["shuffle"]?.tint(if (k == 2) GREEN else Color.WHITE)
        exIcons["loop"]?.tint(if (k == 1 || k == 3) GREEN else Color.WHITE)
        exIcons["mute"]?.tint(if (pl != null && pl.volume == 0f) GREEN else Color.WHITE)
        exIcons["ab"]?.tint(if (PlayerManager.abA >= 0) GREEN else Color.WHITE)
    }

    private fun buildControls() {
        controls = FrameLayout(this)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT))
        top.setPadding(dp(20), dp(48), dp(20), dp(28))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = GlyphView(this, G.BACK)
        back.setOnClickListener { finish() }
        bar.addView(back, lp(dp(30), dp(30)))
        titleTv = TextView(this)
        titleTv.textSize = 17f
        titleTv.setTextColor(Color.WHITE)
        titleTv.maxLines = 2
        titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
        titleTv.setPadding(dp(14), 0, dp(22), 0)
        bar.addView(titleTv, lp(0, WRAP, 1f))
        rotGlyph = plain(G.ROTATE) { toggleRotLock() }
        bar.addView(rotGlyph, lp(dp(30), dp(30)))
        bar.addView(plain(G.NOTE) { audioTrackDialog() }, lpm(dp(30), dp(30), dp(18)))
        val wave = WaveGlyph(this)
        wave.setOnClickListener {
            audioEffectDialog()
            showControls()
        }
        bar.addView(wave, lpm(dp(30), dp(30), dp(18)))
        bar.addView(plain(G.MORE_V) { showPanel() }, lpm(dp(30), dp(30), dp(18)))
        top.addView(bar, lp(MATCH, WRAP))

        // row under the title: small row, and the big labelled row that opens with the arrow
        buildCompact()
        exScroll = HorizontalScrollView(this)
        exScroll.isHorizontalScrollBarEnabled = false
        exRow = LinearLayout(this)
        exRow.orientation = LinearLayout.HORIZONTAL
        exScroll.addView(exRow)
        exScroll.visibility = View.GONE
        val qHold = FrameLayout(this)
        qHold.addView(compactRow, FrameLayout.LayoutParams(WRAP, WRAP))
        qHold.addView(exScroll, FrameLayout.LayoutParams(MATCH, WRAP))
        buildExpanded()
        val qlp = lp(MATCH, WRAP)
        qlp.topMargin = dp(12)
        top.addView(qHold, qlp)
        controls.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT))
        bottom.setPadding(dp(20), dp(28), dp(20), dp(14))

        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        curT = TextView(this)
        curT.textSize = 13f
        curT.setTextColor(Color.WHITE)
        curT.text = "00:00"
        seek = SeekBar(this)
        styleSeek(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    curT.text = Fmt.dur(progress.toLong())
                    updatePreview(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
                seekStartPos = p?.currentPosition ?: 0L
                main.removeCallbacks(hideRun)
                updatePreview(sb.progress.toLong())
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                pvCard.visibility = View.GONE
                pvArrow.visibility = View.GONE
                synchronized(prevLock) { previewWant = -1L }
                p?.seekTo(sb.progress.toLong())
                showControls()
            }
        })
        durT = TextView(this)
        durT.textSize = 13f
        durT.setTextColor(Color.WHITE)
        durT.text = "00:00"
        sr.addView(curT, lp(WRAP, WRAP))
        sr.addView(seek, lp(0, WRAP, 1f))
        sr.addView(durT, lp(WRAP, WRAP))
        bottom.addView(sr, lp(MATCH, WRAP))

        // bottom row = left | centered play cluster | right
        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        br.gravity = Gravity.CENTER_VERTICAL
        br.setPadding(0, dp(8), 0, 0)

        val leftG = LinearLayout(this)
        leftG.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val lockB = GlyphView(this, G.UNLOCK)
        lockB.setOnClickListener { lockScreen() }
        leftG.addView(lockB, lp(dp(34), dp(34)))

        val gap = dp(44)
        centerG = LinearLayout(this)
        centerG.orientation = LinearLayout.HORIZONTAL
        centerG.gravity = Gravity.CENTER_VERTICAL
        btnBack10 = plain(G.REPLAY) { seekBy(-10000L) }
        centerG.addView(btnBack10, lpg(dp(38), dp(38), gap))
        centerG.addView(plain(G.PREV) { prevItem() }, lpg(dp(42), dp(42), gap))
        val playRing = FrameLayout(this)
        val pb = GradientDrawable()
        pb.shape = GradientDrawable.OVAL
        pb.setColor(Color.TRANSPARENT)
        pb.setStroke(dp(2), Color.WHITE)
        playRing.background = pb
        playGlyph = GlyphView(this, G.PLAY)
        playRing.addView(playGlyph, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        playRing.setOnClickListener {
            togglePlay()
            showControls()
        }
        centerG.addView(playRing, lpg(dp(46), dp(46), gap))
        centerG.addView(plain(G.NEXT) { nextItem() }, lpg(dp(42), dp(42), gap))
        btnFwd10 = plain(G.FORWARD) { seekBy(10000L) }
        centerG.addView(btnFwd10, lpg(dp(38), dp(38), gap))

        val rightG = LinearLayout(this)
        rightG.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val speedTv = TextView(this)
        speedTv.text = "Speed"
        speedTv.textSize = 16f
        speedTv.setTextColor(Color.WHITE)
        speedTv.setPadding(dp(8), dp(8), dp(8), dp(8))
        speedTv.setOnClickListener {
            speedDialog()
            showControls()
        }
        speedWord = speedTv
        rightG.addView(speedTv, lp(WRAP, WRAP))
        rightG.addView(plain(G.RESIZE) { cycleResize() }, lp(dp(32), dp(32)))

        br.addView(leftG, lp(0, WRAP, 1f))
        br.addView(centerG, lp(WRAP, WRAP))
        br.addView(rightG, lp(0, WRAP, 1f))
        bottom.addView(br, lp(MATCH, WRAP))
        controls.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // seek preview (small frame above the seek bar)
        pvCard = LinearLayout(this)
        pvCard.orientation = LinearLayout.VERTICAL
        pvCard.gravity = Gravity.CENTER_HORIZONTAL
        pvCard.setPadding(dp(14), dp(12), dp(14), dp(12))
        pvCard.background = rr(Color.parseColor("#EB2B2B2B"), 14)
        pvCard.visibility = View.GONE
        pvImg = ImageView(this)
        pvImg.scaleType = ImageView.ScaleType.FIT_CENTER
        pvImg.visibility = View.GONE
        pvCard.addView(pvImg, lp(dp(60), dp(100)))
        pvText = TextView(this)
        pvText.textSize = 18f
        pvText.setTextColor(Color.WHITE)
        pvText.setPadding(0, dp(8), 0, 0)
        pvCard.addView(pvText, lp(WRAP, WRAP))
        controls.addView(pvCard, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        pvArrow = ArrowView(this)
        pvArrow.visibility = View.GONE
        controls.addView(pvArrow, FrameLayout.LayoutParams(dp(20), dp(10), Gravity.TOP or Gravity.START))
    }

    // ---------- seek preview ----------

    private fun updatePreview(pos: Long) {
        val delta = pos - seekStartPos
        pvText.text = Fmt.dur(pos) + " [" + (if (delta >= 0) "+" else "-") + Fmt.dur(abs(delta)) + "]"
        pvCard.visibility = View.VISIBLE
        pvArrow.visibility = View.VISIBLE
        if (cur()?.isAudio == true) {
            pvImg.visibility = View.GONE
        } else {
            requestPreview(pos)
        }
        pvCard.post { placePreview() }
    }

    private fun placePreview() {
        if (pvCard.visibility != View.VISIBLE) return
        val ctr = IntArray(2)
        controls.getLocationOnScreen(ctr)
        val sl = IntArray(2)
        seek.getLocationOnScreen(sl)
        val avail = seek.width - seek.paddingLeft - seek.paddingRight
        val frac = if (seek.max > 0) seek.progress.toFloat() / seek.max else 0f
        val thumbX = (sl[0] - ctr[0]) + seek.paddingLeft + avail * frac
        pvCard.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = pvCard.measuredWidth
        val h = pvCard.measuredHeight
        var x = thumbX - w / 2f
        val maxX = (controls.width - w - dp(8)).toFloat()
        x = x.coerceIn(dp(8).toFloat(), maxOf(dp(8).toFloat(), maxX))
        val seekTop = sl[1] - ctr[1]
        val y = seekTop - h - dp(16)
        pvCard.x = x
        pvCard.y = y.toFloat()
        pvArrow.x = thumbX - dp(10)
        pvArrow.y = (y + h - dp(1)).toFloat()
    }

    private fun requestPreview(posMs: Long) {
        val f = cur() ?: return
        synchronized(prevLock) {
            previewWant = posMs
            previewPath = f.path
            if (previewBusy) return
            previewBusy = true
        }
        previewExec.execute {
            while (true) {
                var want = -1L
                var path = ""
                synchronized(prevLock) {
                    want = previewWant
                    path = previewPath
                    previewWant = -1L
                    if (want < 0) previewBusy = false
                }
                if (want < 0) return@execute
                val bmp = frameAt(path, want)
                runOnUiThread {
                    if (bmp != null && pvCard.visibility == View.VISIBLE) showPreviewFrame(bmp)
                }
            }
        }
    }

    private fun frameAt(path: String, posMs: Long): Bitmap? {
        try {
            var r = retriever
            if (r == null || retrieverPath != path) {
                try {
                    r?.release()
                } catch (e: Throwable) {
                }
                val nr = MediaMetadataRetriever()
                nr.setDataSource(path)
                retriever = nr
                retrieverPath = path
                r = nr
            }
            val ret = r ?: return null
            val full = ret.getFrameAtTime(posMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val sc = 260f / maxOf(full.width, full.height)
            if (sc >= 1f) return full
            val out = Bitmap.createScaledBitmap(
                full, maxOf(2, (full.width * sc).toInt()), maxOf(2, (full.height * sc).toInt()), true
            )
            if (out !== full) full.recycle()
            return out
        } catch (e: Throwable) {
            return null
        }
    }

    private fun showPreviewFrame(bmp: Bitmap) {
        val maxW = dp(170)
        val maxH = dp(104)
        val a = bmp.width.toFloat() / bmp.height
        var w = maxW
        var h = (maxW / a).toInt()
        if (h > maxH) {
            h = maxH
            w = (maxH * a).toInt()
        }
        val l = pvImg.layoutParams
        l.width = w
        l.height = h
        pvImg.layoutParams = l
        pvImg.setImageBitmap(bmp)
        pvImg.visibility = View.VISIBLE
        pvCard.post { placePreview() }
    }

    // ---------- basic controls ----------

    private fun togglePlay() {
        val pl = p ?: return
        if (pl.playbackState == Player.STATE_ENDED) {
            pl.seekTo(0L)
            pl.playWhenReady = true
        } else {
            pl.playWhenReady = !pl.playWhenReady
        }
    }

    private fun seekBy(d: Long) {
        val pl = p ?: return
        val dur = pl.duration
        var t = pl.currentPosition + d
        if (t < 0) t = 0
        if (dur > 0 && t > dur) t = dur
        pl.seekTo(t)
    }

    private fun prevItem() {
        val pl = p ?: return
        if (pl.currentPosition > 3000L || !pl.hasPreviousMediaItem()) pl.seekTo(0L) else pl.seekToPreviousMediaItem()
    }

    private fun nextItem() {
        val pl = p ?: return
        if (pl.hasNextMediaItem()) pl.seekToNextMediaItem() else toast("This is the last video")
    }

    private fun toggleMute() {
        val pl = p ?: return
        val mute = pl.volume > 0f
        pl.volume = if (mute) 0f else 1f
        refreshExIcons()
        toast(if (mute) "Muted" else "Sound on")
    }

    private fun toggleNight() {
        nightOn = !nightOn
        nightOv.visibility = if (nightOn) View.VISIBLE else View.GONE
        refreshExIcons()
        toast(if (nightOn) "Night mode on" else "Night mode off")
    }

    private fun setRepeatKind(k: Int) {
        PlayerManager.repeatKind = k
        PlayerManager.applyRepeat()
        Prefs.putInt("repeat", k)
        updateRepeatUi()
        refreshExIcons()
    }

    private fun toggleShuffle() {
        if (PlayerManager.repeatKind == 2) {
            setRepeatKind(0)
            toast("Shuffle off")
        } else {
            setRepeatKind(2)
            toast("Shuffle on")
        }
    }

    private fun cycleLoop() {
        when (PlayerManager.repeatKind) {
            3 -> {
                setRepeatKind(1)
                toast("Repeat One")
            }
            1 -> {
                setRepeatKind(0)
                toast("Loop off")
            }
            else -> {
                setRepeatKind(3)
                toast("Loop All")
            }
        }
    }

    private fun customiseDialog() {
        val keys = exOrder.filter { it != "custom" }
        val labels = keys.map { exLabels[it] ?: it }.toTypedArray()
        val hidden = Prefs.getSet("qhide")
        val checked = BooleanArray(keys.size) { !hidden.contains(keys[it]) }
        val dlg = AlertDialog.Builder(this).setTitle("Customise Items")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Done") { _, _ ->
                val h = HashSet<String>()
                for (i in keys.indices) if (!checked[i]) h.add(keys[i])
                Prefs.putSet("qhide", h)
                buildExpanded()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.tintButtons(GREEN)
    }

    private fun audioEffectDialog() {
        val names = arrayOf("Normal", "Bass boost", "Treble boost", "Vocal", "Loud (volume boost)")
        AlertDialog.Builder(this).setTitle("Audio Effect").setItems(names) { _, w -> applyEffect(w) }.show()
    }

    private fun applyEffect(w: Int) {
        val e = PlayerManager.eq
        if (e == null) {
            toast("Audio effect is not available right now")
            return
        }
        try {
            val range = e.bandLevelRange
            val lo = range[0].toInt()
            val hi = range[1].toInt()
            val n = e.numberOfBands.toInt()
            val parts = ArrayList<String>()
            for (i in 0 until n) {
                var lv = 0
                when (w) {
                    1 -> if (i < 2) lv = hi * 6 / 10
                    2 -> if (i >= n - 2) lv = hi * 6 / 10
                    3 -> lv = if (i == 0 || i == n - 1) lo / 3 else hi / 2
                    else -> lv = 0
                }
                lv = lv.coerceIn(lo, hi)
                e.setBandLevel(i.toShort(), lv.toShort())
                parts.add(lv.toString())
            }
            Prefs.putString("eq", parts.joinToString(","))
            if (w == 4) PlayerManager.setBoost(600)
            toast("Audio effect: " + arrayOf("Normal", "Bass boost", "Treble boost", "Vocal", "Loud")[w])
        } catch (t: Throwable) {
            toast("Audio effect is not available right now")
        }
    }

    // top-bar button: lock / unlock automatic rotation
    private fun toggleRotLock() {
        rotLocked = !rotLocked
        rotGlyph.slashOn(rotLocked)
        rotGlyph.tint(if (rotLocked) GREEN else Color.WHITE)
        waitingPhys = false
        try {
            orientListener?.disable()
        } catch (e: Throwable) {
        }
        restoreOrientation()
        toast(if (rotLocked) "Auto-rotation locked" else "Auto-rotation on")
    }

    // turn the screen by hand (works even when rotation is locked)
    private fun manualRotate() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        wantLand = !land
        requestedOrientation = if (wantLand) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (rotLocked) return
        waitingPhys = true
        if (orientListener == null) {
            orientListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(o: Int) {
                    if (!waitingPhys || o == ORIENTATION_UNKNOWN) return
                    val physLand = (o in 60..120) || (o in 240..300)
                    val physPort = (o <= 30) || (o >= 330) || (o in 150..210)
                    if ((wantLand && physLand) || (!wantLand && physPort)) {
                        waitingPhys = false
                        if (!rotLocked && !locked) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        disable()
                    }
                }
            }
        }
        orientListener?.enable()
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        arFrame.resizeMode = resizeModes[resizeIdx]
        toast(resizeNames[resizeIdx])
    }

    private fun speedDialog() {
        val pl = p ?: return
        val c = pl.playbackParameters.speed
        val names = speeds.map { speedText(it) + if (Math.abs(it - c) < 0.01f) "  \u2713" else "" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Playback speed").setItems(names) { _, w ->
            pl.setPlaybackSpeed(speeds[w])
            updateSpeedLabels(speeds[w])
        }.show()
    }

    private fun goBackground() {
        val pl = p ?: return
        PlayerManager.enterBackground(this)
        pl.playWhenReady = true
        toast("Playing in background")
        finish()
    }

    private fun enterPip() {
        if (cur()?.isAudio == true) {
            toast("Pop-up play is for videos")
            return
        }
        val pl = p ?: return
        var w = pl.videoSize.width
        var h = pl.videoSize.height
        if (w <= 0 || h <= 0) {
            w = 16
            h = 9
        }
        val r = (w.toFloat() / h).coerceIn(0.45f, 2.3f)
        try {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build()
            panelWrap.visibility = View.GONE
            enterPictureInPictureMode(params)
        } catch (e: Throwable) {
            toast("Pop-up play is not available")
        }
    }

    private fun takeShot() {
        try {
            val bmp = tex.bitmap
            if (bmp == null) {
                toast("No frame to capture")
                return
            }
            val v = ContentValues()
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "Shot_" + System.currentTimeMillis() + ".jpg")
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VideoPlayer")
            val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (u == null) {
                toast("Could not save screenshot")
                return
            }
            contentResolver.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            toast("Screenshot saved")
        } catch (e: Throwable) {
            toast("Screenshot failed")
        }
    }

    // ---------- 3-dot panel ----------

    private fun cell(key: String, glyph: Int, text: String, onClick: () -> Unit): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(0, dp(10), 0, dp(10))
        val ringF = FrameLayout(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.TRANSPARENT)
        bg.setStroke(dp(1), Color.parseColor("#88FFFFFF"))
        ringF.background = bg
        val g = GlyphView(this, glyph)
        ringF.addView(g, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        cellIcons[key] = g
        c.addView(ringF, lp(dp(60), dp(60)))
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.maxLines = 2
        t.setPadding(dp(2), dp(8), dp(2), 0)
        c.addView(t, lp(MATCH, WRAP))
        c.setOnClickListener { onClick() }
        return c
    }

    private fun gridRow(cells: List<View>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (c in cells) row.addView(c, lp(0, WRAP, 1f))
        for (i in cells.size until 4) row.addView(View(this), lp(0, 1, 1f))
        return row
    }

    private fun title(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 20f
        t.setTextColor(Color.WHITE)
        t.setPadding(0, dp(14), 0, dp(8))
        return t
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(Color.parseColor("#33FFFFFF"))
        return v
    }

    private fun styleChip(tv: TextView, sel: Boolean) {
        tv.background = rr(if (sel) GREEN else Color.parseColor("#2A2A2A"), 28)
    }

    private fun buildPanel() {
        panelWrap = FrameLayout(this)
        panelWrap.setBackgroundColor(Color.TRANSPARENT)
        panelWrap.visibility = View.GONE
        panelWrap.setOnClickListener { hidePanel() }

        panelBox = LinearLayout(this)
        panelBox.orientation = LinearLayout.VERTICAL
        panelBox.isClickable = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(16), dp(20), dp(16))

        col.addView(gridRow(listOf(
            cell("audio", G.NOTE, "Audio Track") { audioTrackDialog() },
            cell("sub", G.CC, "Subtitle") { subtitleDialog() },
            cell("bg", G.HEADPHONE, "Background Play") { goBackground() },
            cell("pip", G.PIP, "Pop-up Play") { enterPip() }
        )), lp(MATCH, WRAP))
        col.addView(gridRow(listOf(
            cell("cast", G.CAST, "Cast") { castSettings() },
            cell("del", G.TRASH, "Delete") { deleteCurrent() },
            cell("bm", G.BOOKMARK, "Bookmark") { addBookmark() },
            cell("fav", G.HEART, "Favorites") { toggleFav() }
        )), lp(MATCH, WRAP))
        col.addView(divider(), lp(MATCH, dp(1)))
        col.addView(title("Play option"), lp(MATCH, WRAP))
        col.addView(gridRow(listOf(
            cell("ab", G.AB, "AB Repeat") { abRepeat() },
            cell("eq", G.EQ, "Equalizer") { eqDialog() },
            cell("timer", G.ALARM, "Timer") { timerDialog() },
            cell("rot", G.ROTATE, "Rotate") { manualRotate() }
        )), lp(MATCH, WRAP))
        col.addView(divider(), lp(MATCH, dp(1)))

        val rh = LinearLayout(this)
        rh.orientation = LinearLayout.HORIZONTAL
        rh.gravity = Gravity.CENTER_VERTICAL
        rh.addView(title("Repeat Mode"), lp(0, WRAP, 1f))
        repeatName = TextView(this)
        repeatName.textSize = 20f
        repeatName.setTextColor(Color.WHITE)
        rh.addView(repeatName, lp(WRAP, WRAP))
        col.addView(rh, lp(MATCH, WRAP))
        val seg = LinearLayout(this)
        seg.orientation = LinearLayout.HORIZONTAL
        seg.background = rr(Color.parseColor("#2A2A2A"), 34)
        val kinds = intArrayOf(G.ORDER, G.ONE, G.SHUFFLE, G.LOOP, G.LOOP)
        for (i in kinds.indices) {
            val b = FrameLayout(this)
            val g = GlyphView(this, kinds[i])
            if (i == 4) g.slashOn(true)
            b.addView(g, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            b.setOnClickListener { setRepeatKind(i) }
            repeatBtns.add(b)
            seg.addView(b, lp(0, dp(64), 1f))
        }
        col.addView(seg, lp(MATCH, WRAP))
        val dv1 = lp(MATCH, dp(1))
        dv1.topMargin = dp(18)
        col.addView(divider(), dv1)

        col.addView(title("Brightness"), lp(MATCH, WRAP))
        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        br.gravity = Gravity.CENTER_VERTICAL
        br.addView(GlyphView(this, G.SUN), lp(dp(30), dp(30)))
        brightSeek = SeekBar(this)
        styleSeek(brightSeek)
        brightSeek.max = 100
        brightSeek.setOnSeekBarChangeListener(simpleSeek { setBrightness(it) })
        br.addView(brightSeek, lp(0, WRAP, 1f))
        brightVal = TextView(this)
        brightVal.textSize = 20f
        brightVal.setTextColor(Color.WHITE)
        brightVal.gravity = Gravity.CENTER
        br.addView(brightVal, lp(dp(56), WRAP))
        col.addView(br, lp(MATCH, WRAP))

        col.addView(title("Volume"), lp(MATCH, WRAP))
        val vr = LinearLayout(this)
        vr.orientation = LinearLayout.HORIZONTAL
        vr.gravity = Gravity.CENTER_VERTICAL
        vr.addView(GlyphView(this, G.VOLUME), lp(dp(30), dp(30)))
        volSeek = SeekBar(this)
        styleSeek(volSeek)
        volSeek.max = 200
        volSeek.setOnSeekBarChangeListener(simpleSeek { setVolumeLevel(it) })
        vr.addView(volSeek, lp(0, WRAP, 1f))
        volVal = TextView(this)
        volVal.textSize = 20f
        volVal.setTextColor(Color.WHITE)
        volVal.gravity = Gravity.CENTER
        vr.addView(volVal, lp(dp(56), WRAP))
        col.addView(vr, lp(MATCH, WRAP))
        val dv2 = lp(MATCH, dp(1))
        dv2.topMargin = dp(18)
        col.addView(divider(), dv2)

        col.addView(title("Decoder"), lp(MATCH, WRAP))
        val dr = LinearLayout(this)
        dr.orientation = LinearLayout.HORIZONTAL
        hwChip = chipView("HW Decoder") { setDecoder(false) }
        swChip = chipView("SW Decoder") { setDecoder(true) }
        dr.addView(hwChip, lp(WRAP, dp(56)))
        dr.addView(swChip, lpm(WRAP, dp(56), dp(16)))
        col.addView(dr, lp(MATCH, WRAP))

        col.addView(title("Custom"), lp(MATCH, WRAP))
        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        val radio = FrameLayout(this)
        val rb = GradientDrawable()
        rb.shape = GradientDrawable.OVAL
        rb.setColor(Color.TRANSPARENT)
        rb.setStroke(dp(2), Color.parseColor("#AAAAAA"))
        radio.background = rb
        shotDot = View(this)
        shotDot.background = oval(GREEN)
        shotDot.visibility = View.INVISIBLE
        radio.addView(shotDot, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        sr.addView(radio, lp(dp(30), dp(30)))
        val st = TextView(this)
        st.text = "Screenshot"
        st.textSize = 20f
        st.setTextColor(Color.WHITE)
        st.setPadding(dp(14), 0, 0, 0)
        sr.addView(st, lp(WRAP, WRAP))
        sr.setOnClickListener {
            showShot = !showShot
            shotDot.visibility = if (showShot) View.VISIBLE else View.INVISIBLE
            applyShotVis()
            toast(if (showShot) "Screenshot button shown on the player" else "Screenshot button hidden")
        }
        sr.setPadding(0, dp(6), 0, dp(18))
        col.addView(sr, lp(MATCH, WRAP))

        val sv = ScrollView(this)
        sv.addView(col)
        panelBox.addView(sv, lp(MATCH, 0, 1f))

        val fixed = LinearLayout(this)
        fixed.orientation = LinearLayout.HORIZONTAL
        fixed.setBackgroundColor(Color.BLACK)
        fixed.setPadding(dp(12), dp(4), dp(12), dp(4))
        val bottomCells = listOf(
            cell("edit", G.PENCIL, "Edit") { trimDialog() },
            cell("cf", G.COLORS, "Color Filter") { colorFilterDialog() },
            cell("share", G.SHARE, "Share") { shareCurrent() },
            cell("others", G.DOTS_H, "Others") { othersSheet() }
        )
        for (c in bottomCells) fixed.addView(c, lp(0, WRAP, 1f))
        panelBox.addView(fixed, lp(MATCH, WRAP))

        panelWrap.addView(panelBox, FrameLayout.LayoutParams(dp(460), MATCH, Gravity.END))
    }

    private fun chipView(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 20f
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(28), 0, dp(28), 0)
        t.setOnClickListener { onClick() }
        return t
    }

    // landscape: panel on the right side. portrait: bottom sheet.
    private fun layoutPanel() {
        val dm = resources.displayMetrics
        val land = dm.widthPixels > dm.heightPixels
        val l = panelBox.layoutParams as FrameLayout.LayoutParams
        if (land) {
            l.width = minOf(dp(460), (dm.widthPixels * 0.62f).toInt())
            l.height = MATCH
            l.gravity = Gravity.END
            panelBox.setBackgroundColor(Color.parseColor("#F2000000"))
        } else {
            l.width = MATCH
            l.height = (dm.heightPixels * 0.56f).toInt()
            l.gravity = Gravity.BOTTOM
            val g = GradientDrawable()
            g.setColor(Color.parseColor("#F0141414"))
            val r = dp(28).toFloat()
            g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            panelBox.background = g
        }
        panelBox.layoutParams = l
    }

    private fun updateRepeatUi() {
        val k = PlayerManager.repeatKind
        for (i in repeatBtns.indices) {
            repeatBtns[i].background = if (i == k) rr(GREEN, 34) else null
        }
        repeatName.text = repeatNames.getOrElse(k) { "" }
    }

    private fun setDecoder(soft: Boolean) {
        if (PlayerManager.softDecoder == soft) return
        PlayerManager.softDecoder = soft
        PlayerManager.rebuild()
        val np = PlayerManager.player ?: return
        attachPlayer(np)
        styleChip(hwChip, !soft)
        styleChip(swChip, soft)
        toast(if (soft) "Software decoder" else "Hardware decoder")
    }

    private fun setBrightness(v: Int) {
        val a = window.attributes
        a.screenBrightness = maxOf(0.01f, v / 100f)
        window.attributes = a
        brightVal.text = v.toString()
    }

    private fun setVolumeLevel(v: Int) {
        applyVolumeLevel(v)
        volVal.text = v.toString()
    }

    private fun syncPanel() {
        val f = cur()
        cellIcons["fav"]?.tint(if (f != null && Prefs.isFavorite(f.path)) GREEN else Color.WHITE)
        cellIcons["ab"]?.tint(if (PlayerManager.abA >= 0) GREEN else Color.WHITE)
        updateRepeatUi()
        styleChip(hwChip, !PlayerManager.softDecoder)
        styleChip(swChip, PlayerManager.softDecoder)
        shotDot.visibility = if (showShot) View.VISIBLE else View.INVISIBLE
        val cb = window.attributes.screenBrightness
        val bv = if (cb >= 0f) (cb * 100).toInt() else (systemBright() * 100).toInt()
        brightSeek.progress = bv
        brightVal.text = bv.toString()
        val vv = currentVolLevel()
        volSeek.progress = vv
        volVal.text = vv.toString()
    }

    private fun showPanel() {
        syncPanel()
        layoutPanel()
        panelWrap.visibility = View.VISIBLE
        controls.visibility = View.GONE
        main.removeCallbacks(hideRun)
    }

    private fun hidePanel() {
        panelWrap.visibility = View.GONE
        showControls()
    }

    // ---------- panel features ----------

    private fun trackName(f: Format, idx: Int): String {
        val lang = f.language
        val loc = if (lang != null && lang != "und") Locale(lang).displayLanguage else ""
        val parts = ArrayList<String>()
        val lab = f.label
        if (!lab.isNullOrEmpty()) parts.add(lab) else if (loc.isNotEmpty()) parts.add(loc)
        if (parts.isEmpty()) parts.add("Track " + (idx + 1))
        if (f.channelCount > 0) parts.add(f.channelCount.toString() + "ch")
        return parts.joinToString(" \u00B7 ")
    }

    private fun audioTrackDialog() {
        val pl = p ?: return
        val names = ArrayList<String>()
        val groups = ArrayList<Tracks.Group>()
        val idx = ArrayList<Int>()
        for (g in pl.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                names.add(trackName(g.getTrackFormat(i), names.size) + if (g.isTrackSelected(i)) "  \u2713" else "")
                groups.add(g)
                idx.add(i)
            }
        }
        if (names.isEmpty()) {
            toast("No audio track found")
            return
        }
        if (names.size == 1) {
            toast("Only one audio track in this file")
            return
        }
        AlertDialog.Builder(this).setTitle("Audio Track").setItems(names.toTypedArray()) { _, w ->
            pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(groups[w].mediaTrackGroup, idx[w]))
                .build()
        }.show()
    }

    private fun subtitleDialog() {
        val pl = p ?: return
        val names = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        names.add("Off")
        acts.add {
            pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        for (g in pl.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                names.add(trackName(g.getTrackFormat(i), names.size - 1) + if (g.isTrackSelected(i)) "  \u2713" else "")
                acts.add {
                    pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        names.add("Add subtitle file...")
        acts.add { subPicker.launch(arrayOf("*/*")) }
        AlertDialog.Builder(this).setTitle("Subtitle").setItems(names.toTypedArray()) { _, w -> acts[w]() }.show()
    }

    private fun addSubtitle(uri: Uri) {
        val pl = p ?: return
        var name = "sub.srt"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        } catch (e: Throwable) {
        }
        val ext = name.substringAfterLast('.', "srt")
        val sub = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(PlayerManager.subMime(ext))
            .setLanguage("und")
            .setLabel(name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val item = pl.currentMediaItem ?: return
        val old = item.localConfiguration?.subtitleConfigurations ?: emptyList<MediaItem.SubtitleConfiguration>()
        val newItem = item.buildUpon().setSubtitleConfigurations(old + sub).build()
        val i = pl.currentMediaItemIndex
        val pos = pl.currentPosition
        pl.replaceMediaItem(i, newItem)
        pl.seekTo(i, pos)
        pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        toast("Subtitle added")
    }

    private fun castSettings() {
        try {
            startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        } catch (e: Throwable) {
            toast("Cast is not available on this phone")
        }
    }

    private fun deleteCurrent() {
        val pl = p ?: return
        val i = pl.currentMediaItemIndex
        val f = PlayerManager.queue.getOrNull(i) ?: return
        val dlg = AlertDialog.Builder(this).setMessage("Delete \"" + f.file.name + "\"?")
            .setPositiveButton("Delete") { _, _ ->
                val ok = try {
                    f.file.delete()
                } catch (e: Throwable) {
                    false
                }
                if (!ok) {
                    toast("Could not delete this file")
                } else {
                    MediaScannerConnection.scanFile(this, arrayOf(f.path), null, null)
                    LibraryState.dirty = true
                    PlayerManager.queue.removeAt(i)
                    if (PlayerManager.queue.isEmpty()) {
                        finish()
                    } else {
                        pl.removeMediaItem(i)
                        toast("Deleted")
                    }
                }
            }.setNegativeButton("Cancel", null).create()
        dlg.show()
        dlg.tintButtons(GREEN)
    }

    private fun addBookmark() {
        val pl = p ?: return
        val f = cur() ?: return
        Prefs.addBookmark(f.path, pl.currentPosition)
        toast("Bookmark added at " + Fmt.dur(pl.currentPosition))
    }

    private fun toggleFav() {
        val f = cur() ?: return
        val now = Prefs.toggleFavorite(f.path)
        cellIcons["fav"]?.tint(if (now) GREEN else Color.WHITE)
        toast(if (now) "Added to favorites" else "Removed from favorites")
    }

    private fun abRepeat() {
        val pl = p ?: return
        val pos = pl.currentPosition
        if (PlayerManager.abA < 0) {
            PlayerManager.abA = pos
            PlayerManager.abB = -1L
            abLabel.text = "A " + Fmt.dur(pos) + " \u2192 B ?"
            abLabel.visibility = View.VISIBLE
            toast("Point A set. Tap AB Repeat again to set B")
        } else if (PlayerManager.abB < 0) {
            if (pos <= PlayerManager.abA + 500) {
                toast("B must be after A")
                return
            }
            PlayerManager.abB = pos
            abLabel.text = "A " + Fmt.dur(PlayerManager.abA) + " \u2192 B " + Fmt.dur(pos)
            toast("Repeating A to B")
        } else {
            resetAb()
            toast("AB repeat off")
            return
        }
        cellIcons["ab"]?.tint(GREEN)
        refreshExIcons()
    }

    private fun eqDialog() {
        val e = PlayerManager.eq
        if (e == null) {
            toast("Equalizer is not available right now")
            return
        }
        val range = e.bandLevelRange
        val lo = range[0].toInt()
        val hi = range[1].toInt()
        val n = e.numberOfBands.toInt()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        val seeks = ArrayList<SeekBar>()
        for (i in 0 until n) {
            val hz = e.getCenterFreq(i.toShort()) / 1000
            val label = TextView(this)
            label.text = if (hz >= 1000) String.format(Locale.US, "%.1f kHz", hz / 1000f) else "$hz Hz"
            label.setPadding(0, dp(10), 0, 0)
            box.addView(label, lp(MATCH, WRAP))
            val sb = SeekBar(this)
            styleSeek(sb)
            sb.max = hi - lo
            sb.progress = e.getBandLevel(i.toShort()).toInt() - lo
            sb.setOnSeekBarChangeListener(simpleSeek { v -> e.setBandLevel(i.toShort(), (v + lo).toShort()) })
            seeks.add(sb)
            box.addView(sb, lp(MATCH, WRAP))
        }
        val sv = ScrollView(this)
        sv.addView(box)
        val dlg = AlertDialog.Builder(this).setTitle("Equalizer").setView(sv)
            .setNeutralButton("Reset", null)
            .setPositiveButton("Done", null)
            .create()
        dlg.setOnDismissListener {
            val parts = ArrayList<String>()
            for (i in 0 until n) parts.add(e.getBandLevel(i.toShort()).toString())
            Prefs.putString("eq", parts.joinToString(","))
        }
        dlg.show()
        dlg.tintButtons(GREEN)
        dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            for (i in 0 until n) {
                e.setBandLevel(i.toShort(), 0.toShort())
                seeks[i].progress = -lo
            }
        }
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { dlg.dismiss() }
    }

    private fun timerDialog() {
        val opts = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes", "After this video")
        AlertDialog.Builder(this).setTitle("Sleep timer").setItems(opts) { _, w ->
            when (w) {
                0 -> {
                    PlayerManager.setSleepMinutes(0)
                    toast("Timer off")
                }
                6 -> {
                    PlayerManager.sleepAfterCurrent()
                    toast("Will pause after this video")
                }
                else -> {
                    val m = intArrayOf(0, 15, 30, 45, 60, 90)[w]
                    PlayerManager.setSleepMinutes(m)
                    toast("Will pause in $m minutes")
                }
            }
        }.show()
    }

    private fun applyFilter() {
        val plainView = cfBright == 0 && cfContrast == 100 && cfSat == 100 && cfWarm == 0
        if (plainView) {
            tex.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        val sat = ColorMatrix()
        sat.setSaturation(cfSat / 100f)
        val c = cfContrast / 100f
        val b = cfBright * 2.55f
        val t = (-0.5f * c + 0.5f) * 255f + b
        val con = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        val w = cfWarm * 0.8f
        val warm = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, w, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, -w, 0f, 0f, 0f, 1f, 0f))
        val m = ColorMatrix()
        m.postConcat(sat)
        m.postConcat(con)
        m.postConcat(warm)
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(m)
        tex.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    private fun colorFilterDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        val seeks = ArrayList<SeekBar>()
        fun row(label: String, max: Int, prog: Int, on: (Int) -> Unit) {
            val t = TextView(this)
            t.text = label
            t.setPadding(0, dp(12), 0, 0)
            box.addView(t, lp(MATCH, WRAP))
            val sb = SeekBar(this)
            styleSeek(sb)
            sb.max = max
            sb.progress = prog
            sb.setOnSeekBarChangeListener(simpleSeek { v ->
                on(v)
                applyFilter()
            })
            seeks.add(sb)
            box.addView(sb, lp(MATCH, WRAP))
        }
        row("Brightness", 100, cfBright + 50) { cfBright = it - 50 }
        row("Contrast", 100, cfContrast - 50) { cfContrast = it + 50 }
        row("Saturation", 200, cfSat) { cfSat = it }
        row("Warmth", 100, cfWarm + 50) { cfWarm = it - 50 }
        val dlg = AlertDialog.Builder(this).setTitle("Color Filter").setView(box)
            .setNeutralButton("Reset", null)
            .setPositiveButton("Done", null)
            .create()
        dlg.show()
        dlg.tintButtons(GREEN)
        dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            cfBright = 0
            cfContrast = 100
            cfSat = 100
            cfWarm = 0
            seeks[0].progress = 50
            seeks[1].progress = 50
            seeks[2].progress = 100
            seeks[3].progress = 50
            applyFilter()
        }
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { dlg.dismiss() }
    }

    private fun shareCurrent() {
        val f = cur() ?: return
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = if (f.isAudio) "audio/*" else "video/*"
            i.putExtra(Intent.EXTRA_STREAM, fileUri(this, f.path))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Throwable) {
            toast("Could not share this file")
        }
    }

    private fun trimDialog() {
        val pl = p ?: return
        val f = cur() ?: return
        val d = pl.duration
        if (d <= 2000L) {
            toast("This file is too short to trim")
            return
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        val sT = TextView(this)
        val eT = TextView(this)
        val sS = SeekBar(this)
        val eS = SeekBar(this)
        styleSeek(sS)
        styleSeek(eS)
        sS.max = d.toInt()
        eS.max = d.toInt()
        sS.progress = 0
        eS.progress = d.toInt()
        fun upd() {
            sT.text = "Start  " + Fmt.dur(sS.progress.toLong())
            eT.text = "End  " + Fmt.dur(eS.progress.toLong())
        }
        upd()
        sS.setOnSeekBarChangeListener(simpleSeek { upd() })
        eS.setOnSeekBarChangeListener(simpleSeek { upd() })
        sT.setPadding(0, dp(10), 0, 0)
        eT.setPadding(0, dp(14), 0, 0)
        box.addView(sT, lp(MATCH, WRAP))
        box.addView(sS, lp(MATCH, WRAP))
        box.addView(eT, lp(MATCH, WRAP))
        box.addView(eS, lp(MATCH, WRAP))
        val dlg = AlertDialog.Builder(this).setTitle("Trim").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val s = sS.progress.toLong()
                val e = eS.progress.toLong()
                if (e - s < 1000L) toast("Selected part must be at least 1 second") else startTrim(f, s, e)
            }.setNegativeButton("Cancel", null).create()
        dlg.show()
        dlg.tintButtons(GREEN)
    }

    private fun startTrim(f: MediaFile, s: Long, e: Long) {
        val dir = f.file.parentFile ?: return
        val ext = if (f.isAudio) "m4a" else "mp4"
        var out = File(dir, f.title + "_trim." + ext)
        var n = 1
        while (out.exists()) {
            n++
            out = File(dir, f.title + "_trim" + n + "." + ext)
        }
        val outFile = out
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s).setEndPositionMs(e).build()
        val mi = MediaItem.Builder().setUri(Uri.fromFile(f.file)).setClippingConfiguration(clip).build()
        val edited = EditedMediaItem.Builder(mi).build()
        val t = Transformer.Builder(this).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                MediaScannerConnection.scanFile(this@PlayerActivity, arrayOf(outFile.absolutePath), null, null)
                LibraryState.dirty = true
                toast("Saved: " + outFile.name)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                toast("Trim failed: " + exportException.errorCodeName)
            }
        }).build()
        transformer = t
        toast("Trimming...")
        t.start(edited, outFile.absolutePath)
    }

    private fun othersSheet() {
        showSheet(this, "Others", listOf(
            SheetItem(G.INFO, "File info") { infoDialog() },
            SheetItem(G.BOOKMARK, "Bookmarks") { bookmarksDialog() },
            SheetItem(G.CC, "Add subtitle file") { subPicker.launch(arrayOf("*/*")) }
        ))
    }

    private fun infoDialog() {
        val pl = p ?: return
        val f = cur() ?: return
        val vs = pl.videoSize
        val sb = StringBuilder()
        sb.append("Name: ").append(f.file.name).append("\n\n")
        sb.append("Path: ").append(f.path).append("\n\n")
        sb.append("Size: ").append(Fmt.size(f.size)).append("\n")
        sb.append("Duration: ").append(Fmt.dur(pl.duration)).append("\n")
        if (!f.isAudio && vs.width > 0) sb.append("Resolution: ").append(vs.width).append(" x ").append(vs.height).append("\n")
        sb.append("Modified: ").append(Fmt.age(f.modified))
        AlertDialog.Builder(this).setTitle("File info").setMessage(sb.toString()).setPositiveButton("Close", null).show()
    }

    private fun bookmarksDialog() {
        val pl = p ?: return
        val f = cur() ?: return
        val list = Prefs.bookmarks().filter { it.first == f.path }
        if (list.isEmpty()) {
            toast("No bookmarks for this file")
            return
        }
        val names = list.map { Fmt.dur(it.second) }.toTypedArray()
        val dlg = AlertDialog.Builder(this).setTitle("Bookmarks (long press to remove)")
            .setItems(names) { _, w -> pl.seekTo(list[w].second) }
            .create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
            Prefs.removeBookmark(f.path, list[pos].second)
            dlg.dismiss()
            toast("Bookmark removed")
            true
        }
    }
}
