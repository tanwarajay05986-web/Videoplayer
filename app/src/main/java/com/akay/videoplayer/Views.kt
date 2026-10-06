package com.akay.videoplayer

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

object G {
    const val BACK = 0
    const val SEARCH = 1
    const val MORE_V = 2
    const val CHEVRON = 3
    const val CLOSE = 4
    const val SCISSORS = 10
    const val DOWNLOAD = 11
    const val CLAPPER = 12
    const val FILM = 13
    const val APERTURE = 14
    const val IMAGE = 15
    const val SHARE = 16
    const val PLAY_SQ = 17
    const val CAMERA = 18
    const val MIC = 19
    const val NOTE = 20
    const val DISC = 21
    const val CLOCK = 22
    const val CHAT = 23
    const val PLANE = 24
    const val FOLDER = 25
    const val FB = 26
    const val LOCK = 30
    const val UNLOCK = 31
    const val ROTATE = 32
    const val MUTE = 33
    const val VOLUME = 34
    const val HEADPHONE = 35
    const val REPLAY = 36
    const val FORWARD = 37
    const val PREV = 38
    const val NEXT = 39
    const val PLAY = 40
    const val PAUSE = 41
    const val RESIZE = 42
    const val QUEUE = 43
    const val CC = 50
    const val PIP = 51
    const val CAST = 52
    const val TRASH = 53
    const val BOOKMARK = 54
    const val HEART = 55
    const val AB = 56
    const val EQ = 57
    const val ALARM = 58
    const val HD = 59
    const val ORDER = 60
    const val ONE = 61
    const val SHUFFLE = 62
    const val LOOP = 63
    const val SUN = 65
    const val PENCIL = 66
    const val COLORS = 67
    const val DOTS_H = 68
    const val EYE_OFF = 69
    const val INFO = 70
}

class GlyphView(context: Context, var kind: Int, private var color: Int = Color.WHITE) : View(context) {
    private var slash = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun tint(c: Int) {
        color = c
        invalidate()
    }

    fun slashOn(v: Boolean) {
        slash = v
        invalidate()
    }

    fun setShape(k: Int) {
        kind = k
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.065f * s
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        f.color = color
        f.style = Paint.Style.FILL
        t.color = color
        t.typeface = Typeface.DEFAULT_BOLD
        t.textAlign = Paint.Align.CENTER

        fun px(v: Float): Float = ox + v * s
        fun py(v: Float): Float = oy + v * s
        fun line(a: Float, b: Float, c: Float, d: Float) {
            cv.drawLine(px(a), py(b), px(c), py(d), p)
        }
        fun pl(vararg v: Float) {
            path.reset()
            path.moveTo(px(v[0]), py(v[1]))
            var k = 2
            while (k < v.size) {
                path.lineTo(px(v[k]), py(v[k + 1]))
                k += 2
            }
            cv.drawPath(path, p)
        }
        fun pg(fill: Boolean, vararg v: Float) {
            path.reset()
            path.moveTo(px(v[0]), py(v[1]))
            var k = 2
            while (k < v.size) {
                path.lineTo(px(v[k]), py(v[k + 1]))
                k += 2
            }
            path.close()
            cv.drawPath(path, if (fill) f else p)
        }
        fun circ(x: Float, y: Float, r: Float, fill: Boolean = false) {
            cv.drawCircle(px(x), py(y), r * s, if (fill) f else p)
        }
        fun rr(l: Float, tp: Float, r: Float, b: Float, rad: Float, fill: Boolean = false) {
            cv.drawRoundRect(RectF(px(l), py(tp), px(r), py(b)), rad * s, rad * s, if (fill) f else p)
        }
        fun arc(x: Float, y: Float, r: Float, start: Float, sweep: Float) {
            cv.drawArc(RectF(px(x - r), py(y - r), px(x + r), py(y + r)), start, sweep, false, p)
        }
        fun txt(str: String, x: Float, y: Float, size: Float) {
            t.textSize = size * s
            cv.drawText(str, px(x), py(y), t)
        }
        fun head(x: Float, y: Float, dx: Float, dy: Float, size: Float) {
            val nx = -dy
            val ny = dx
            path.reset()
            path.moveTo(px(x + dx * size), py(y + dy * size))
            path.lineTo(px(x + nx * size * 0.7f), py(y + ny * size * 0.7f))
            path.lineTo(px(x - nx * size * 0.7f), py(y - ny * size * 0.7f))
            path.close()
            cv.drawPath(path, f)
        }

        when (kind) {
            G.BACK -> {
                line(.78f, .5f, .24f, .5f)
                pl(.48f, .26f, .24f, .5f, .48f, .74f)
            }
            G.SEARCH -> {
                circ(.45f, .45f, .2f)
                line(.6f, .6f, .78f, .78f)
            }
            G.MORE_V -> {
                circ(.5f, .24f, .06f, true)
                circ(.5f, .5f, .06f, true)
                circ(.5f, .76f, .06f, true)
            }
            G.DOTS_H -> {
                circ(.24f, .5f, .06f, true)
                circ(.5f, .5f, .06f, true)
                circ(.76f, .5f, .06f, true)
            }
            G.CHEVRON -> pl(.38f, .22f, .66f, .5f, .38f, .78f)
            G.CLOSE -> {
                line(.28f, .28f, .72f, .72f)
                line(.72f, .28f, .28f, .72f)
            }
            G.SCISSORS -> {
                circ(.3f, .3f, .1f)
                circ(.3f, .7f, .1f)
                line(.37f, .36f, .8f, .78f)
                line(.37f, .64f, .8f, .22f)
            }
            G.DOWNLOAD -> {
                line(.5f, .2f, .5f, .6f)
                pl(.34f, .46f, .5f, .62f, .66f, .46f)
                pl(.2f, .64f, .2f, .8f, .8f, .8f, .8f, .64f)
            }
            G.CLAPPER -> {
                rr(.2f, .44f, .8f, .78f, .06f)
                pg(false, .2f, .44f, .25f, .24f, .8f, .3f, .8f, .44f)
                line(.4f, .43f, .45f, .27f)
                line(.6f, .43f, .65f, .28f)
            }
            G.FILM -> {
                rr(.2f, .2f, .8f, .8f, .07f)
                line(.38f, .2f, .38f, .8f)
                line(.62f, .2f, .62f, .8f)
                for (y in floatArrayOf(.34f, .5f, .66f)) {
                    line(.2f, y, .38f, y)
                    line(.62f, y, .8f, y)
                }
            }
            G.APERTURE -> {
                circ(.5f, .5f, .3f)
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0)
                    val b = Math.toRadians(i * 60.0 + 60.0)
                    line(
                        .5f + .08f * cos(a).toFloat(), .5f + .08f * sin(a).toFloat(),
                        .5f + .3f * cos(b).toFloat(), .5f + .3f * sin(b).toFloat()
                    )
                }
            }
            G.IMAGE -> {
                rr(.2f, .22f, .8f, .78f, .08f)
                circ(.37f, .4f, .05f, true)
                pl(.22f, .72f, .42f, .5f, .56f, .64f, .66f, .54f, .78f, .68f)
            }
            G.SHARE -> {
                circ(.28f, .5f, .075f)
                circ(.72f, .26f, .075f)
                circ(.72f, .74f, .075f)
                line(.35f, .46f, .65f, .3f)
                line(.35f, .54f, .65f, .7f)
            }
            G.PLAY_SQ -> {
                rr(.2f, .26f, .8f, .74f, .12f)
                pg(true, .44f, .38f, .64f, .5f, .44f, .62f)
            }
            G.CAMERA -> {
                rr(.18f, .32f, .82f, .76f, .08f)
                circ(.5f, .54f, .14f)
                pl(.36f, .32f, .4f, .24f, .6f, .24f, .64f, .32f)
            }
            G.MIC -> {
                rr(.4f, .16f, .6f, .56f, .1f)
                arc(.5f, .48f, .22f, 20f, 140f)
                line(.5f, .7f, .5f, .82f)
                line(.38f, .82f, .62f, .82f)
            }
            G.NOTE -> {
                line(.48f, .72f, .48f, .24f)
                pl(.48f, .24f, .72f, .32f, .72f, .44f)
                circ(.36f, .72f, .11f)
            }
            G.DISC -> {
                circ(.5f, .5f, .3f)
                circ(.5f, .5f, .08f)
                arc(.5f, .5f, .18f, 200f, 70f)
                arc(.5f, .5f, .18f, 20f, 70f)
            }
            G.CLOCK -> {
                circ(.5f, .5f, .3f)
                line(.5f, .5f, .5f, .32f)
                line(.5f, .5f, .62f, .58f)
            }
            G.CHAT -> {
                rr(.2f, .24f, .8f, .66f, .1f)
                pl(.34f, .66f, .3f, .8f, .48f, .66f)
            }
            G.PLANE -> {
                pg(false, .2f, .52f, .8f, .24f, .62f, .78f, .5f, .58f)
                line(.5f, .58f, .8f, .24f)
            }
            G.FOLDER -> pg(false, .18f, .3f, .4f, .3f, .46f, .38f, .82f, .38f, .82f, .74f, .18f, .74f)
            G.FB -> {
                t.textSize = 0.78f * s
                cv.drawText("f", px(.54f), py(.76f), t)
            }
            G.LOCK -> {
                rr(.28f, .46f, .72f, .8f, .07f)
                arc(.5f, .4f, .14f, 180f, 180f)
                line(.36f, .4f, .36f, .46f)
                line(.64f, .4f, .64f, .46f)
                circ(.5f, .63f, .04f, true)
            }
            G.UNLOCK -> {
                rr(.28f, .46f, .72f, .8f, .07f)
                arc(.5f, .3f, .14f, 180f, 180f)
                line(.36f, .3f, .36f, .46f)
                circ(.5f, .63f, .04f, true)
            }
            G.ROTATE -> {
                arc(.5f, .54f, .3f, -30f, 300f)
                head(.5f, .24f, 1f, 0f, .14f)
                rr(.4f, .4f, .6f, .66f, .03f)
            }
            G.MUTE -> {
                pg(true, .2f, .4f, .34f, .4f, .5f, .26f, .5f, .74f, .34f, .6f, .2f, .6f)
                line(.62f, .4f, .82f, .6f)
                line(.82f, .4f, .62f, .6f)
            }
            G.VOLUME -> {
                pg(true, .18f, .4f, .32f, .4f, .48f, .26f, .48f, .74f, .32f, .6f, .18f, .6f)
                arc(.5f, .5f, .16f, -50f, 100f)
                arc(.5f, .5f, .28f, -50f, 100f)
            }
            G.HEADPHONE -> {
                arc(.5f, .55f, .28f, 180f, 180f)
                rr(.2f, .55f, .34f, .78f, .05f, true)
                rr(.66f, .55f, .8f, .78f, .05f, true)
            }
            G.FORWARD, G.REPLAY -> {
                cv.save()
                if (kind == G.REPLAY) cv.scale(-1f, 1f, ox + s / 2f, oy + s / 2f)
                arc(.5f, .56f, .28f, -30f, 300f)
                head(.5f, .28f, 1f, 0f, .14f)
                cv.restore()
                txt("10", .5f, .65f, .26f)
            }
            G.PREV -> {
                line(.3f, .26f, .3f, .74f)
                pg(true, .74f, .28f, .4f, .5f, .74f, .72f)
            }
            G.NEXT -> {
                line(.7f, .26f, .7f, .74f)
                pg(true, .26f, .28f, .6f, .5f, .26f, .72f)
            }
            G.PLAY -> pg(true, .34f, .22f, .78f, .5f, .34f, .78f)
            G.PAUSE -> {
                rr(.28f, .24f, .44f, .76f, .03f, true)
                rr(.56f, .24f, .72f, .76f, .03f, true)
            }
            G.RESIZE -> {
                rr(.18f, .28f, .82f, .72f, .05f)
                pl(.3f, .46f, .3f, .38f, .38f, .38f)
                pl(.7f, .54f, .7f, .62f, .62f, .62f)
            }
            G.QUEUE -> {
                line(.2f, .3f, .64f, .3f)
                line(.2f, .5f, .64f, .5f)
                line(.2f, .7f, .44f, .7f)
                pg(true, .64f, .52f, .84f, .66f, .64f, .8f)
            }
            G.CC -> {
                rr(.16f, .28f, .84f, .72f, .08f)
                txt("CC", .5f, .6f, .26f)
            }
            G.PIP -> {
                rr(.16f, .26f, .84f, .74f, .06f)
                rr(.5f, .5f, .76f, .66f, .03f, true)
                pl(.28f, .48f, .28f, .38f, .38f, .38f)
                line(.28f, .38f, .42f, .52f)
            }
            G.CAST -> {
                pl(.2f, .34f, .2f, .28f, .8f, .28f, .8f, .72f, .58f, .72f)
                arc(.2f, .72f, .14f, -90f, 90f)
                arc(.2f, .72f, .26f, -90f, 90f)
                circ(.2f, .72f, .03f, true)
            }
            G.TRASH -> {
                line(.28f, .33f, .72f, .33f)
                pl(.42f, .33f, .42f, .26f, .58f, .26f, .58f, .33f)
                pl(.33f, .39f, .37f, .74f, .63f, .74f, .67f, .39f)
                line(.46f, .47f, .46f, .66f)
                line(.54f, .47f, .54f, .66f)
            }
            G.BOOKMARK -> pg(false, .3f, .2f, .7f, .2f, .7f, .8f, .5f, .64f, .3f, .8f)
            G.HEART -> {
                path.reset()
                path.moveTo(px(.5f), py(.76f))
                path.cubicTo(px(.08f), py(.5f), px(.22f), py(.2f), px(.5f), py(.38f))
                path.cubicTo(px(.78f), py(.2f), px(.92f), py(.5f), px(.5f), py(.76f))
                cv.drawPath(path, p)
            }
            G.AB -> {
                rr(.12f, .32f, .88f, .68f, .18f)
                txt("AB", .5f, .58f, .26f)
            }
            G.EQ -> {
                line(.3f, .22f, .3f, .78f)
                line(.5f, .22f, .5f, .78f)
                line(.7f, .22f, .7f, .78f)
                circ(.3f, .6f, .07f, true)
                circ(.5f, .38f, .07f, true)
                circ(.7f, .52f, .07f, true)
            }
            G.ALARM -> {
                circ(.5f, .56f, .26f)
                line(.5f, .56f, .5f, .42f)
                line(.5f, .56f, .6f, .62f)
                circ(.24f, .26f, .07f)
                circ(.76f, .26f, .07f)
            }
            G.HD -> {
                rr(.12f, .28f, .88f, .72f, .1f)
                txt("HD", .5f, .6f, .26f)
            }
            G.ORDER -> {
                line(.2f, .38f, .72f, .38f)
                head(.8f, .38f, 1f, 0f, .1f)
                line(.2f, .62f, .72f, .62f)
                head(.8f, .62f, 1f, 0f, .1f)
            }
            G.ONE, G.LOOP -> {
                pl(.26f, .56f, .26f, .4f, .68f, .4f)
                head(.76f, .4f, 1f, 0f, .1f)
                pl(.74f, .44f, .74f, .6f, .32f, .6f)
                head(.24f, .6f, -1f, 0f, .1f)
                if (kind == G.ONE) txt("1", .5f, .56f, .2f)
            }
            G.SHUFFLE -> {
                pl(.2f, .34f, .4f, .34f, .6f, .66f, .72f, .66f)
                pl(.2f, .66f, .4f, .66f, .6f, .34f, .72f, .34f)
                head(.8f, .66f, 1f, 0f, .09f)
                head(.8f, .34f, 1f, 0f, .09f)
            }
            G.SUN -> {
                circ(.5f, .5f, .14f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(
                        .5f + .24f * cos(a).toFloat(), .5f + .24f * sin(a).toFloat(),
                        .5f + .34f * cos(a).toFloat(), .5f + .34f * sin(a).toFloat()
                    )
                }
            }
            G.PENCIL -> {
                pg(false, .24f, .76f, .28f, .6f, .62f, .26f, .74f, .38f, .4f, .72f)
                line(.56f, .32f, .68f, .44f)
            }
            G.COLORS -> {
                circ(.5f, .36f, .17f)
                circ(.36f, .62f, .17f)
                circ(.64f, .62f, .17f)
            }
            G.EYE_OFF -> {
                path.reset()
                path.moveTo(px(.14f), py(.5f))
                path.quadTo(px(.5f), py(.14f), px(.86f), py(.5f))
                path.quadTo(px(.5f), py(.86f), px(.14f), py(.5f))
                cv.drawPath(path, p)
                circ(.5f, .5f, .1f)
                line(.22f, .8f, .78f, .2f)
            }
            G.INFO -> {
                circ(.5f, .5f, .3f)
                circ(.5f, .35f, .03f, true)
                line(.5f, .47f, .5f, .67f)
            }
        }
        if (slash) line(.2f, .8f, .8f, .2f)
    }
}

class MediaRow(ctx: Context) : LinearLayout(ctx) {
    val more = FrameLayout(ctx)
    var bound = ""
    private val frame = FrameLayout(ctx)
    private val img = ImageView(ctx)
    private val playCircle = FrameLayout(ctx)
    private val badge = TextView(ctx)
    private val noteGlyph = GlyphView(ctx, G.NOTE, Color.WHITE)
    private val title = TextView(ctx)
    private val meta = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(6), ctx.dp(8))
        frame.clipToOutline = true
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        frame.addView(img, FrameLayout.LayoutParams(-1, -1))
        val pc = GradientDrawable()
        pc.shape = GradientDrawable.OVAL
        pc.setColor(Color.parseColor("#66000000"))
        playCircle.background = pc
        playCircle.addView(GlyphView(ctx, G.PLAY, Color.WHITE), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        frame.addView(playCircle, FrameLayout.LayoutParams(ctx.dp(44), ctx.dp(44), Gravity.CENTER))
        badge.textSize = 13f
        badge.setTextColor(Color.WHITE)
        badge.setTypeface(null, Typeface.BOLD)
        val bd = GradientDrawable()
        bd.setColor(Color.parseColor("#CC111111"))
        bd.cornerRadius = ctx.dp(6).toFloat()
        badge.background = bd
        badge.setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
        val bl = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        bl.setMargins(0, 0, ctx.dp(6), ctx.dp(6))
        frame.addView(badge, bl)
        frame.addView(noteGlyph, FrameLayout.LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER))
        addView(frame, LayoutParams(ctx.dp(200), ctx.dp(114)))

        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        col.setPadding(ctx.dp(16), 0, ctx.dp(4), 0)
        title.textSize = 17f
        title.setTextColor(Color.parseColor("#1A1A1A"))
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        meta.textSize = 14f
        meta.setTextColor(Color.parseColor("#7A7A7A"))
        meta.setPadding(0, ctx.dp(4), 0, 0)
        col.addView(title, LayoutParams(-1, -2))
        col.addView(meta, LayoutParams(-1, -2))
        addView(col, LayoutParams(0, -2, 1f))

        more.addView(GlyphView(ctx, G.MORE_V, Color.parseColor("#9A9A9A")), FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.CENTER))
        addView(more, LayoutParams(ctx.dp(44), ctx.dp(44)))
    }

    fun bind(f: MediaFile) {
        bound = f.path
        val c = context
        val st = Library.style(f.file.nameWithoutExtension, f.isAudio)
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(st.c1, st.c2))
        bg.cornerRadius = c.dp(if (f.isAudio) 14 else 12).toFloat()
        frame.background = bg
        val lpf = frame.layoutParams as LayoutParams
        lpf.width = c.dp(if (f.isAudio) 64 else 200)
        lpf.height = c.dp(if (f.isAudio) 64 else 114)
        frame.layoutParams = lpf
        img.setImageBitmap(null)
        playCircle.visibility = if (f.isAudio) GONE else VISIBLE
        noteGlyph.visibility = if (f.isAudio) VISIBLE else GONE
        badge.visibility = GONE
        title.text = f.file.name
        val base = Fmt.size(f.size) + " \u00B7 " + Fmt.age(f.modified)
        meta.text = base
        Meta.loadThumb(f) { bmp, d ->
            if (bound == f.path) {
                if (bmp != null) {
                    img.setImageBitmap(bmp)
                    if (f.isAudio) noteGlyph.visibility = GONE
                }
                if (f.isAudio) {
                    meta.text = Fmt.dur(d) + " \u00B7 " + base
                } else {
                    badge.text = Fmt.dur(d)
                    badge.visibility = VISIBLE
                }
            }
        }
    }
}

class SheetItem(val glyph: Int, val text: String, val action: () -> Unit)

fun showSheet(ctx: Context, title: String, items: List<SheetItem>) {
    val dlg = Dialog(ctx)
    dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = LinearLayout(ctx)
    box.orientation = LinearLayout.VERTICAL
    val bg = GradientDrawable()
    bg.setColor(Color.WHITE)
    val r = ctx.dp(28).toFloat()
    bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    box.background = bg
    box.setPadding(0, 0, 0, ctx.dp(20))
    val t = TextView(ctx)
    t.text = title
    t.textSize = 20f
    t.setTextColor(Color.parseColor("#9AA0A6"))
    t.maxLines = 1
    t.ellipsize = android.text.TextUtils.TruncateAt.END
    t.setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(16))
    box.addView(t, LinearLayout.LayoutParams(-1, -2))
    val dv = View(ctx)
    dv.setBackgroundColor(Color.parseColor("#E6E6E6"))
    box.addView(dv, LinearLayout.LayoutParams(-1, ctx.dp(1)))
    for (it in items) {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(ctx.dp(24), ctx.dp(16), ctx.dp(24), ctx.dp(16))
        row.addView(GlyphView(ctx, it.glyph, Color.parseColor("#5F6368")), LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)))
        val tv = TextView(ctx)
        tv.text = it.text
        tv.textSize = 20f
        tv.setTextColor(Color.parseColor("#202124"))
        tv.setPadding(ctx.dp(22), 0, 0, 0)
        row.addView(tv, LinearLayout.LayoutParams(-1, -2))
        row.setOnClickListener {
            dlg.dismiss()
            it2Action(row)
        }
        row.tag = it.action
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    dlg.setContentView(box)
    val w = dlg.window
    if (w != null) {
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        w.setGravity(Gravity.BOTTOM)
        w.setDimAmount(0.5f)
    }
    dlg.show()
}

@Suppress("UNCHECKED_CAST")
private fun it2Action(row: View) {
    val a = row.tag as? (() -> Unit)
    a?.invoke()
}

fun AlertDialog.tintButtons(color: Int) {
    getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(color)
    getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(color)
    getButton(DialogInterface.BUTTON_NEUTRAL)?.setTextColor(color)
}
