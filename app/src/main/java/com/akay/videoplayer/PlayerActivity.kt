package com.akay.videoplayer

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
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
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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

object LibraryState {
    var dirty = false
}

class PlayerActivity : ComponentActivity() {

    private val GREEN = Color.parseColor("#14B800")
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
    private lateinit var controls: FrameLayout
    private lateinit var titleTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var curT: TextView
    private lateinit var durT: TextView
    private lateinit var playGlyph: GlyphView
    private lateinit var rotGlyph: GlyphView
    private lateinit var muteGlyph: GlyphView
    private lateinit var chevGlyph: GlyphView
    private lateinit var speedChip: TextView
    private lateinit var extraRow: LinearLayout
    private lateinit var shotBtn: View
    private lateinit var abLabel: TextView
    private lateinit var unlockBtn: View
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

    private val cellIcons = HashMap<String, GlyphView>()
    private val repeatBtns = ArrayList<FrameLayout>()
    private var transformer: Transformer? = null

    private var locked = false
    private var rotLocked = false
    private var seeking = false
    private var showShot = false
    private var enhance = false
    private var orientSet = false
    private var cfBright = 0
    private var cfContrast = 100
    private var cfSat = 100
    private var cfWarm = 0

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

    // ---------- small helpers ----------

    private fun lp(w: Int, h: Int, wt: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, wt)

    private fun lpm(w: Int, h: Int, left: Int): LinearLayout.LayoutParams {
        val l = lp(w, h)
        l.leftMargin = left
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
        p?.removeListener(listener)
        try {
            p?.clearVideoTextureView(tex)
        } catch (e: Throwable) {
        }
        if (isFinishing && !PlayerManager.backgroundMode) PlayerManager.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePanelWidth()
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
            syncUi()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                var r = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) r = 1f / r
                arFrame.setAspectRatio(r)
                if (!orientSet && !rotLocked) {
                    orientSet = true
                    requestedOrientation = if (r >= 1f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
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
        speedChip.text = speedText(pl.playbackParameters.speed)
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

    private fun lockScreen() {
        locked = true
        controls.visibility = View.GONE
        showUnlock()
        toast("Screen locked")
    }

    private fun unlock() {
        locked = false
        unlockBtn.visibility = View.GONE
        toast("Unlocked")
    }

    // ---------- UI ----------

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.setOnClickListener { toggleControls() }

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

        buildPanel()
        root.addView(panelWrap, FrameLayout.LayoutParams(MATCH, MATCH))

        unlockBtn = ring(G.UNLOCK, 52) { unlock() }
        unlockBtn.visibility = View.GONE
        val ul = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL)
        ul.leftMargin = dp(28)
        root.addView(unlockBtn, ul)
    }

    private fun buildControls() {
        controls = FrameLayout(this)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT))
        top.setPadding(dp(20), dp(12), dp(20), dp(28))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = GlyphView(this, G.BACK)
        back.setOnClickListener { finish() }
        bar.addView(back, lp(dp(38), dp(38)))
        titleTv = TextView(this)
        titleTv.textSize = 20f
        titleTv.setTextColor(Color.WHITE)
        titleTv.maxLines = 1
        titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
        titleTv.setPadding(dp(16), 0, dp(8), 0)
        bar.addView(titleTv, lp(0, WRAP, 1f))
        rotGlyph = plain(G.ROTATE) { toggleRotLock() }
        bar.addView(rotGlyph, lp(dp(38), dp(38)))
        bar.addView(plain(G.QUEUE) { queueDialog() }, lpm(dp(38), dp(38), dp(14)))
        bar.addView(plain(G.MORE_V) { showPanel() }, lpm(dp(38), dp(38), dp(8)))
        top.addView(bar, lp(MATCH, WRAP))

        val quick = LinearLayout(this)
        quick.orientation = LinearLayout.HORIZONTAL
        quick.gravity = Gravity.CENTER_VERTICAL
        quick.setPadding(0, dp(14), 0, 0)
        quick.addView(ring(G.ROTATE, 54) { manualRotate() }, lp(dp(54), dp(54)))
        val muteRing = ring(G.VOLUME, 54) { toggleMute() }
        muteGlyph = glyphOf(muteRing)
        quick.addView(muteRing, lpm(dp(54), dp(54), dp(14)))
        quick.addView(ring(G.HEADPHONE, 54) { goBackground() }, lpm(dp(54), dp(54), dp(14)))
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
        quick.addView(speedRing, lpm(dp(54), dp(54), dp(14)))
        val chevRing = ring(G.CHEVRON, 44) { toggleExtra() }
        chevGlyph = glyphOf(chevRing)
        quick.addView(chevRing, lpm(dp(44), dp(44), dp(14)))
        top.addView(quick, lp(MATCH, WRAP))

        extraRow = LinearLayout(this)
        extraRow.orientation = LinearLayout.HORIZONTAL
        extraRow.gravity = Gravity.CENTER_VERTICAL
        extraRow.setPadding(0, dp(12), 0, 0)
        extraRow.visibility = View.GONE
        shotBtn = ring(G.CAMERA, 48) { takeShot() }
        shotBtn.visibility = View.GONE
        extraRow.addView(shotBtn, lp(dp(48), dp(48)))
        extraRow.addView(ring(G.AB, 48) { abRepeat() }, lpm(dp(48), dp(48), dp(12)))
        extraRow.addView(ring(G.BOOKMARK, 48) { addBookmark() }, lpm(dp(48), dp(48), dp(12)))
        extraRow.addView(ring(G.PIP, 48) { enterPip() }, lpm(dp(48), dp(48), dp(12)))
        top.addView(extraRow, lp(MATCH, WRAP))
        controls.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.parseColor("#AA000000"), Color.TRANSPARENT))
        bottom.setPadding(dp(20), dp(28), dp(20), dp(14))

        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        sr.gravity = Gravity.CENTER_VERTICAL
        curT = TextView(this)
        curT.textSize = 17f
        curT.setTextColor(Color.WHITE)
        curT.text = "00:00"
        seek = SeekBar(this)
        styleSeek(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) curT.text = Fmt.dur(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
                main.removeCallbacks(hideRun)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                p?.seekTo(sb.progress.toLong())
                showControls()
            }
        })
        durT = TextView(this)
        durT.textSize = 17f
        durT.setTextColor(Color.WHITE)
        durT.text = "00:00"
        sr.addView(curT, lp(WRAP, WRAP))
        sr.addView(seek, lp(0, WRAP, 1f))
        sr.addView(durT, lp(WRAP, WRAP))
        bottom.addView(sr, lp(MATCH, WRAP))

        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        br.gravity = Gravity.CENTER_VERTICAL
        br.setPadding(0, dp(8), 0, 0)
        val lockB = GlyphView(this, G.UNLOCK)
        lockB.setOnClickListener { lockScreen() }
        br.addView(lockB, lp(dp(46), dp(46)))
        br.addView(View(this), lp(0, 1, 1f))
        br.addView(plain(G.REPLAY) { seekBy(-10000L) }, lp(dp(50), dp(50)))
        br.addView(plain(G.PREV) { prevItem() }, lpm(dp(46), dp(46), dp(10)))
        val playRing = FrameLayout(this)
        val pb = GradientDrawable()
        pb.shape = GradientDrawable.OVAL
        pb.setColor(Color.TRANSPARENT)
        pb.setStroke(dp(2), Color.WHITE)
        playRing.background = pb
        playGlyph = GlyphView(this, G.PLAY)
        playRing.addView(playGlyph, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        playRing.setOnClickListener {
            togglePlay()
            showControls()
        }
        br.addView(playRing, lpm(dp(66), dp(66), dp(10)))
        br.addView(plain(G.NEXT) { nextItem() }, lpm(dp(46), dp(46), dp(10)))
        br.addView(plain(G.FORWARD) { seekBy(10000L) }, lpm(dp(50), dp(50), dp(10)))
        br.addView(View(this), lp(0, 1, 1f))
        val speedTv = TextView(this)
        speedTv.text = "Speed"
        speedTv.textSize = 18f
        speedTv.setTextColor(Color.WHITE)
        speedTv.setPadding(dp(8), dp(8), dp(8), dp(8))
        speedTv.setOnClickListener {
            speedDialog()
            showControls()
        }
        br.addView(speedTv, lp(WRAP, WRAP))
        br.addView(plain(G.RESIZE) { cycleResize() }, lpm(dp(46), dp(46), dp(8)))
        bottom.addView(br, lp(MATCH, WRAP))
        controls.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
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
        muteGlyph.setShape(if (mute) G.MUTE else G.VOLUME)
    }

    private fun toggleRotLock() {
        rotLocked = !rotLocked
        rotGlyph.slashOn(rotLocked)
        rotGlyph.tint(if (rotLocked) GREEN else Color.WHITE)
        requestedOrientation = if (rotLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        toast(if (rotLocked) "Auto-rotation locked" else "Auto-rotation on")
    }

    private fun manualRotate() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        main.postDelayed({
            requestedOrientation = if (rotLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }, 1500)
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        arFrame.resizeMode = resizeModes[resizeIdx]
        toast(resizeNames[resizeIdx])
    }

    private fun toggleExtra() {
        val show = extraRow.visibility != View.VISIBLE
        extraRow.visibility = if (show) View.VISIBLE else View.GONE
        chevGlyph.rotation = if (show) 180f else 0f
    }

    private fun speedDialog() {
        val pl = p ?: return
        val c = pl.playbackParameters.speed
        val names = speeds.map { speedText(it) + if (Math.abs(it - c) < 0.01f) "  \u2713" else "" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Playback speed").setItems(names) { _, w ->
            pl.setPlaybackSpeed(speeds[w])
            speedChip.text = speedText(speeds[w])
        }.show()
    }

    private fun queueDialog() {
        val pl = p ?: return
        val ci = pl.currentMediaItemIndex
        val names = PlayerManager.queue.mapIndexed { i, f -> (if (i == ci) "\u25B6  " else "") + f.file.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Playlist").setItems(names) { _, w ->
            pl.seekTo(w, 0L)
            pl.playWhenReady = true
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
        panelWrap.setBackgroundColor(Color.parseColor("#55000000"))
        panelWrap.visibility = View.GONE
        panelWrap.setOnClickListener { hidePanel() }

        panelBox = LinearLayout(this)
        panelBox.orientation = LinearLayout.VERTICAL
        panelBox.setBackgroundColor(Color.parseColor("#F2000000"))
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
            cell("enh", G.HD, "Visual Enhancer") { toggleEnhance() }
        )), lp(MATCH, WRAP))
        col.addView(divider(), lp(MATCH, dp(1)))

        val rh = LinearLayout(this)
        rh.orientation = LinearLayout.HORIZONTAL
        rh.gravity = Gravity.CENTER_VERTICAL
        val rt = title("Repeat Mode")
        rh.addView(rt, lp(0, WRAP, 1f))
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
            b.setOnClickListener {
                PlayerManager.repeatKind = i
                PlayerManager.applyRepeat()
                Prefs.putInt("repeat", i)
                updateRepeatUi()
            }
            repeatBtns.add(b)
            seg.addView(b, lp(0, dp(64), 1f))
        }
        col.addView(seg, lp(MATCH, WRAP))
        col.addView(divider(), lpm(MATCH, dp(1), 0).also { it.topMargin = dp(18) })

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
        col.addView(divider(), lpm(MATCH, dp(1), 0).also { it.topMargin = dp(18) })

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
            shotBtn.visibility = if (showShot) View.VISIBLE else View.GONE
            toast(if (showShot) "Screenshot button added to the player" else "Screenshot button hidden")
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

    private fun updatePanelWidth() {
        val dm = resources.displayMetrics
        val land = dm.widthPixels > dm.heightPixels
        val l = panelBox.layoutParams as FrameLayout.LayoutParams
        l.width = if (land) minOf(dp(460), (dm.widthPixels * 0.62f).toInt()) else MATCH
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
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(minOf(v, 100) / 100f * max), 0)
        PlayerManager.setBoost(if (v > 100) (v - 100) * 12 else 0)
        volVal.text = v.toString()
    }

    private fun syncPanel() {
        val f = cur()
        cellIcons["fav"]?.tint(if (f != null && Prefs.isFavorite(f.path)) GREEN else Color.WHITE)
        cellIcons["enh"]?.tint(if (enhance) GREEN else Color.WHITE)
        cellIcons["ab"]?.tint(if (PlayerManager.abA >= 0) GREEN else Color.WHITE)
        updateRepeatUi()
        styleChip(hwChip, !PlayerManager.softDecoder)
        styleChip(swChip, PlayerManager.softDecoder)
        shotDot.visibility = if (showShot) View.VISIBLE else View.INVISIBLE
        val cb = window.attributes.screenBrightness
        val bv = if (cb >= 0f) (cb * 100).toInt() else try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
        } catch (e: Throwable) {
            50
        }
        brightSeek.progress = bv
        brightVal.text = bv.toString()
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cv = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(1, max)
        val vv = if (PlayerManager.boostGain > 0) 100 + PlayerManager.boostGain / 12 else cv
        volSeek.progress = vv
        volVal.text = vv.toString()
    }

    private fun showPanel() {
        syncPanel()
        updatePanelWidth()
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

    private fun toggleEnhance() {
        enhance = !enhance
        applyFilter()
        cellIcons["enh"]?.tint(if (enhance) GREEN else Color.WHITE)
        toast(if (enhance) "Visual enhancer on" else "Visual enhancer off")
    }

    private fun applyFilter() {
        val plain = cfBright == 0 && cfContrast == 100 && cfSat == 100 && cfWarm == 0 && !enhance
        if (plain) {
            tex.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        val sat = ColorMatrix()
        sat.setSaturation((cfSat + (if (enhance) 20 else 0)) / 100f)
        val c = (cfContrast + (if (enhance) 12 else 0)) / 100f
        val b = cfBright * 2.55f + (if (enhance) 6f else 0f)
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
