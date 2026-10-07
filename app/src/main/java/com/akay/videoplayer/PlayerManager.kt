package com.akay.videoplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import java.io.File

object PlayerManager {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    var player: ExoPlayer? = null
    var session: MediaSession? = null
    var queue: MutableList<MediaFile> = ArrayList()
    var backgroundMode = false
    var softDecoder = false
    var repeatKind = 0
    var abA = -1L
    var abB = -1L
    var sleepEndsAt = 0L
    var eq: Equalizer? = null
    var loud: LoudnessEnhancer? = null
    var boostGain = 0

    // bluetooth / headset button state
    private var hookClicks = 0
    private var hookDownAt = 0L
    private var hookLong = false

    fun init(c: Context) {
        app = c.applicationContext
    }

    fun subMime(ext: String): String = when (ext.lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    fun mediaItemFor(f: MediaFile): MediaItem {
        val b = MediaItem.Builder()
            .setUri(Uri.fromFile(File(f.path)))
            .setMediaId(f.path)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(f.title).build())
        if (!f.isAudio) {
            val subs = ArrayList<MediaItem.SubtitleConfiguration>()
            val dir = File(f.path).parentFile
            val base = File(f.path).nameWithoutExtension
            for (ext in listOf("srt", "vtt", "ass", "ssa")) {
                val sf = File(dir, "$base.$ext")
                if (sf.exists()) {
                    subs.add(
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sf))
                            .setMimeType(subMime(ext))
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                }
            }
            if (subs.isNotEmpty()) b.setSubtitleConfigurations(subs)
        }
        return b.build()
    }

    private fun isSoftName(n: String): Boolean = n.startsWith("OMX.google.") || n.startsWith("c2.android.")

    private fun selector(): MediaCodecSelector {
        val soft = softDecoder
        return MediaCodecSelector { mimeType, secure, tunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            val pick = if (soft) all.filter { isSoftName(it.name) } else all.filter { !isSoftName(it.name) }
            if (pick.isNotEmpty()) pick else all
        }
    }

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            setupEffects(audioSessionId)
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val rf = DefaultRenderersFactory(app)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(selector())
        val p = ExoPlayer.Builder(app, rf)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
        p.addListener(listener)
        return p
    }

    // ---------- media session (headset / bluetooth buttons) ----------

    private fun goNext(p: ExoPlayer) {
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
    }

    private fun goPrev(p: ExoPlayer) {
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(0L)
    }

    private val hookRun = Runnable {
        val c = hookClicks
        hookClicks = 0
        val p = player ?: return@Runnable
        when {
            c == 1 -> {
                if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
                    p.pause()
                } else {
                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
                    p.play()
                }
            }
            c == 2 -> goNext(p)
            c >= 3 -> goPrev(p)
        }
    }

    // single press = play/pause, double = next, triple = previous, long press = next
    private fun handleHook(ke: KeyEvent): Boolean {
        val p = player ?: return false
        if (ke.action == KeyEvent.ACTION_DOWN) {
            if (ke.repeatCount == 0 && !ke.isLongPress) {
                hookDownAt = SystemClock.uptimeMillis()
                hookLong = false
            } else if (!hookLong) {
                hookLong = true
                main.removeCallbacks(hookRun)
                hookClicks = 0
                goNext(p)
            }
        } else if (ke.action == KeyEvent.ACTION_UP) {
            if (hookLong) {
                hookLong = false
            } else if (SystemClock.uptimeMillis() - hookDownAt >= 650L) {
                main.removeCallbacks(hookRun)
                hookClicks = 0
                goNext(p)
            } else {
                hookClicks++
                main.removeCallbacks(hookRun)
                main.postDelayed(hookRun, 300L)
            }
        }
        return true
    }

    private val sessionCallback = object : MediaSession.Callback {
        @Suppress("DEPRECATION")
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val ke: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (ke == null) return false
            val code = ke.keyCode
            if (code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                return handleHook(ke)
            }
            return false
        }
    }

    private fun buildSession(p: ExoPlayer) {
        try {
            session?.release()
        } catch (e: Throwable) {
        }
        session = try {
            MediaSession.Builder(app, p)
                .setId("vp_" + System.currentTimeMillis())
                .setCallback(sessionCallback)
                .build()
        } catch (e: Throwable) {
            null
        }
    }

    private fun releaseSession() {
        main.removeCallbacks(hookRun)
        hookClicks = 0
        try {
            session?.release()
        } catch (e: Throwable) {
        }
        session = null
    }

    // ---------- audio effects ----------

    private fun setupEffects(id: Int) {
        try {
            eq?.release()
        } catch (e: Throwable) {
        }
        try {
            loud?.release()
        } catch (e: Throwable) {
        }
        eq = null
        loud = null
        if (id == 0) return
        try {
            val e = Equalizer(0, id)
            e.setEnabled(true)
            val saved = Prefs.getString("eq", "")
            if (saved.isNotEmpty()) {
                val parts = saved.split(",")
                val n = minOf(parts.size, e.numberOfBands.toInt())
                for (i in 0 until n) {
                    val v = parts[i].toShortOrNull() ?: continue
                    e.setBandLevel(i.toShort(), v)
                }
            }
            eq = e
        } catch (t: Throwable) {
        }
        try {
            val l = LoudnessEnhancer(id)
            l.setTargetGain(boostGain)
            l.setEnabled(boostGain > 0)
            loud = l
        } catch (t: Throwable) {
        }
    }

    fun setBoost(mb: Int) {
        boostGain = mb
        try {
            loud?.setTargetGain(mb)
            loud?.setEnabled(mb > 0)
        } catch (t: Throwable) {
        }
    }

    fun ensure(): ExoPlayer {
        var p = player
        if (p == null) {
            p = buildPlayer()
            player = p
            buildSession(p)
        }
        return p
    }

    fun start(files: List<MediaFile>, index: Int, positionMs: Long = 0L, play: Boolean = true) {
        val p = ensure()
        queue = ArrayList(files)
        abA = -1L
        abB = -1L
        p.setMediaItems(files.map { mediaItemFor(it) }, index, positionMs)
        p.prepare()
        applyRepeat()
        p.playWhenReady = play
    }

    fun applyRepeat() {
        val p = player ?: return
        when (repeatKind) {
            1 -> {
                p.repeatMode = Player.REPEAT_MODE_ONE
                p.shuffleModeEnabled = false
                p.pauseAtEndOfMediaItems = false
            }
            2 -> {
                p.repeatMode = Player.REPEAT_MODE_ALL
                p.shuffleModeEnabled = true
                p.pauseAtEndOfMediaItems = false
            }
            3 -> {
                p.repeatMode = Player.REPEAT_MODE_ALL
                p.shuffleModeEnabled = false
                p.pauseAtEndOfMediaItems = false
            }
            4 -> {
                p.repeatMode = Player.REPEAT_MODE_OFF
                p.shuffleModeEnabled = false
                p.pauseAtEndOfMediaItems = true
            }
            else -> {
                p.repeatMode = Player.REPEAT_MODE_OFF
                p.shuffleModeEnabled = false
                p.pauseAtEndOfMediaItems = false
            }
        }
    }

    fun rebuild() {
        val old = player ?: return
        val pos = old.currentPosition
        val idx = old.currentMediaItemIndex
        val pw = old.playWhenReady
        val speed = old.playbackParameters.speed
        old.removeListener(listener)
        releaseSession()
        old.release()
        player = null
        start(queue, idx, pos, pw)
        player?.setPlaybackSpeed(speed)
    }

    fun setVideoEnabled(on: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on)
            .build()
    }

    private val sleepRun = Runnable {
        player?.pause()
        sleepEndsAt = 0L
    }

    fun setSleepMinutes(min: Int) {
        main.removeCallbacks(sleepRun)
        sleepEndsAt = 0L
        player?.pauseAtEndOfMediaItems = (repeatKind == 4)
        if (min > 0) {
            sleepEndsAt = System.currentTimeMillis() + min * 60000L
            main.postDelayed(sleepRun, min * 60000L)
        }
    }

    fun sleepAfterCurrent() {
        main.removeCallbacks(sleepRun)
        player?.pauseAtEndOfMediaItems = true
        sleepEndsAt = -1L
    }

    fun enterBackground(ctx: Context) {
        backgroundMode = true
        setVideoEnabled(false)
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        } catch (e: Throwable) {
        }
    }

    fun exitBackground(ctx: Context) {
        if (!backgroundMode) return
        backgroundMode = false
        setVideoEnabled(true)
        try {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        } catch (e: Throwable) {
        }
    }

    fun stopBackground() {
        backgroundMode = false
        setVideoEnabled(true)
        player?.pause()
    }

    fun release() {
        main.removeCallbacks(sleepRun)
        sleepEndsAt = 0L
        try {
            eq?.release()
        } catch (e: Throwable) {
        }
        try {
            loud?.release()
        } catch (e: Throwable) {
        }
        eq = null
        loud = null
        releaseSession()
        player?.removeListener(listener)
        player?.release()
        player = null
        backgroundMode = false
    }
}

class PlaybackService : Service() {
    private var manager: PlayerNotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        val first = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Playing")
            .build()
        startForeground(NID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = PlayerManager.player
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        manager?.setPlayer(null)
        val m = PlayerNotificationManager.Builder(this, NID, CH)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence =
                    player.mediaMetadata.title ?: "Playing"

                override fun createCurrentContentIntent(player: Player): PendingIntent? {
                    val i = Intent(this@PlaybackService, PlayerActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    return PendingIntent.getActivity(
                        this@PlaybackService, 0, i,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                }

                override fun getCurrentContentText(player: Player): CharSequence? = null

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): Bitmap? = null
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(
                    notificationId: Int, notification: Notification, ongoing: Boolean
                ) {
                    if (ongoing) {
                        startForeground(
                            notificationId, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        stopForeground(Service.STOP_FOREGROUND_DETACH)
                    }
                }

                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    PlayerManager.stopBackground()
                    stopSelf()
                }
            })
            .setSmallIconResourceId(android.R.drawable.ic_media_play)
            .build()
        m.setUseStopAction(true)
        m.setPlayer(p)
        manager = m
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        manager?.setPlayer(null)
        manager = null
        super.onDestroy()
    }

    companion object {
        const val CH = "playback"
        const val NID = 4711
    }
}
