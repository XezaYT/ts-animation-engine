package com.tsengine.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.WindowInsets
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Constants & projection (2:1 dimetric). RenderOrder = (X + Y) * TS + Z.
// ---------------------------------------------------------------------------------------------

private const val TS = 64f
private const val TW = TS
private const val TH = TS * 0.5f
private const val GRID_N = 12
private const val WALL_H = 150f
private const val ECG_N = 160
private const val ECG_RATE = 100f

private fun wxOf(gx: Float, gy: Float): Float = (gx - gy) * TW * 0.5f
private fun wyOf(gx: Float, gy: Float, z: Float = 0f): Float = (gx + gy) * TH * 0.5f - z
private fun withAlpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

private fun shade(color: Int, f: Float): Int {
    val r = (((color shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
    val g = (((color shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
    val b = ((color and 0xFF) * f).toInt().coerceIn(0, 255)
    return (color and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
}

// ---------------------------------------------------------------------------------------------
// Timeline animator: keyframes, easing, frame-accurate seeking.
// ---------------------------------------------------------------------------------------------

enum class Easing(val fn: (Float) -> Float) {
    LINEAR({ t -> t }),
    EASE_IN({ t -> t * t }),
    EASE_OUT({ t -> 1f - (1f - t) * (1f - t) }),
    EASE_IN_OUT({ t -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).pow(2) / 2f }),
    CUBIC_IN_OUT({ t -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f }),
    BACK_OUT({ t ->
        val c1 = 1.70158f
        val c3 = c1 + 1f
        1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }),
    ELASTIC_OUT({ t ->
        if (t <= 0f) 0f else if (t >= 1f) 1f else {
            val c4 = (2.0 * PI / 3.0).toFloat()
            2f.pow(-10f * t) * sin((t * 10f - 0.75f) * c4) + 1f
        }
    }),
    BOUNCE_OUT({ t ->
        val n1 = 7.5625f
        val d1 = 2.75f
        if (t < 1f / d1) n1 * t * t
        else if (t < 2f / d1) { val u = t - 1.5f / d1; n1 * u * u + 0.75f }
        else if (t < 2.5f / d1) { val u = t - 2.25f / d1; n1 * u * u + 0.9375f }
        else { val u = t - 2.625f / d1; n1 * u * u + 0.984375f }
    }),
    HOLD({ t -> if (t < 1f) 0f else 1f })
}

class Keyframe(val timeMs: Float, val value: Float, val easing: Easing = Easing.EASE_IN_OUT)

class Track(val target: String, keyframes: List<Keyframe>) {
    private val keys: List<Keyframe> = keyframes.sortedBy { it.timeMs }

    fun sample(t: Float): Float {
        val k = keys
        if (k.isEmpty()) return 0f
        if (t <= k[0].timeMs) return k[0].value
        val last = k[k.size - 1]
        if (t >= last.timeMs) return last.value
        var lo = 0
        var hi = k.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (k[mid].timeMs <= t) lo = mid else hi = mid
        }
        val a = k[lo]
        val b = k[hi]
        if (b.timeMs <= a.timeMs) return b.value
        val u = (t - a.timeMs) / (b.timeMs - a.timeMs)
        return a.value + (b.value - a.value) * a.easing.fn(u)
    }
}

class Timeline(val durationMs: Float, val fps: Float = 60f, val loop: Boolean = true) {
    private val tracks = ArrayList<Track>()

    fun track(target: String, keyframes: List<Keyframe>): Timeline {
        tracks.add(Track(target, keyframes))
        return this
    }

    fun track(target: String, vararg keyframes: Keyframe): Timeline = track(target, keyframes.toList())

    fun apply(timeMs: Float, sink: (String, Float) -> Unit) {
        for (i in 0 until tracks.size) {
            val t = tracks[i]
            sink(t.target, t.sample(timeMs))
        }
    }
}

class TimelinePlayer {
    @Volatile var timeline: Timeline? = null
    @Volatile var timeMs = 0f
    @Volatile var playing = true
    @Volatile var speed = 1f

    val currentFrame: Int get() = ((timeMs / 1000f) * (timeline?.fps ?: 60f)).roundToInt()

    fun advance(dtMs: Float) {
        val tl = timeline ?: return
        if (!playing) return
        var t = timeMs + dtMs * speed
        if (t >= tl.durationMs) {
            if (tl.loop) t %= tl.durationMs else {
                t = tl.durationMs
                playing = false
            }
        } else if (t < 0f) {
            if (tl.loop) t = tl.durationMs + (t % tl.durationMs) else {
                t = 0f
                playing = false
            }
        }
        timeMs = t
    }

    fun seekFrame(frame: Int) {
        val tl = timeline ?: return
        timeMs = (frame * 1000f / tl.fps).coerceIn(0f, tl.durationMs)
    }

    fun stepFrames(n: Int) {
        playing = false
        seekFrame(currentFrame + n)
    }
}

// ---------------------------------------------------------------------------------------------
// Captions
// ---------------------------------------------------------------------------------------------

class CaptionWord(val text: String, val startMs: Float, val endMs: Float)

class CaptionLine(val words: List<CaptionWord>) {
    val startMs: Float = words.firstOrNull()?.startMs ?: 0f
    val endMs: Float = words.lastOrNull()?.endMs ?: 0f

    companion object {
        fun fromText(text: String, startMs: Float, endMs: Float): CaptionLine {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return CaptionLine(emptyList())
            var total = 0f
            for (t in tokens) total += t.length + 1f
            var cursor = startMs
            val out = ArrayList<CaptionWord>(tokens.size)
            for (t in tokens) {
                val d = (endMs - startMs) * ((t.length + 1f) / total)
                out.add(CaptionWord(t, cursor, cursor + d))
                cursor += d
            }
            return CaptionLine(out)
        }
    }
}

enum class Mood(val label: String, val bpm: Float, val color: Int, val jitter: Float) {
    CALM("CALM", 72f, 0xFF3DDC84.toInt(), 0.015f),
    TENSE("TENSE", 108f, 0xFFFFB300.toInt(), 0.05f),
    PANIC("PANIC", 152f, 0xFFFF3B30.toInt(), 0.12f);

    companion object {
        fun fromIndex(i: Int): Mood = values()[i.coerceIn(0, values().size - 1)]
    }
}

// ---------------------------------------------------------------------------------------------
// Built-in rig authored as Figma-style SVG; parsed through the same importer user assets use.
// ---------------------------------------------------------------------------------------------

private object DefaultAssets {
    const val PUPPET_SVG = """
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 166">
  <defs>
    <linearGradient id="shirt" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#E05A52"/>
      <stop offset="1" stop-color="#A63232"/>
    </linearGradient>
  </defs>
  <g id="Arm_R">
    <path d="M66 58C76 60 80 74 79 94L71 94C71 80 69 72 64 68Z" fill="#D49A68" stroke="#3A2416" stroke-width="1.5" stroke-linejoin="round"/>
    <circle id="Pivot_R" cx="66" cy="61" r="2" fill="#000000"/>
  </g>
  <g id="Leg_R">
    <path d="M52 98H66V150Q66 157 76 159H56Q52 159 52 150Z" fill="#1B2640" stroke="#0B1020" stroke-width="2" stroke-linejoin="round"/>
  </g>
  <g id="Leg_L">
    <path d="M34 98H48V150Q48 159 44 159H22Q34 156 34 150Z" fill="#222F4F" stroke="#0B1020" stroke-width="2" stroke-linejoin="round"/>
  </g>
  <g id="Torso">
    <path d="M33 56Q50 48 67 56L69 102H31Z" fill="url(#shirt)" stroke="#3A1414" stroke-width="2" stroke-linejoin="round"/>
    <path d="M44 52Q50 58 56 52" fill="none" stroke="#3A1414" stroke-width="1.5" stroke-linecap="round"/>
  </g>
  <g id="Head">
    <circle cx="50" cy="34" r="16" fill="#E8B88A" stroke="#3A2416" stroke-width="2"/>
    <path d="M34 34A16 16 0 0 1 66 34Q50 24 34 34Z" fill="#2A1B12"/>
    <circle cx="44" cy="37" r="1.8" fill="#1A1A1A"/>
    <circle cx="56" cy="37" r="1.8" fill="#1A1A1A"/>
  </g>
  <g id="Arm_L">
    <path d="M34 58C24 60 20 74 21 94L29 94C29 80 31 72 36 68Z" fill="#E0A878" stroke="#3A2416" stroke-width="1.5" stroke-linejoin="round"/>
    <circle id="Pivot_L" cx="34" cy="61" r="2" fill="#000000"/>
  </g>
</svg>
"""

    fun createPuppet(): Puppet = FigmaAssetImporter().importSvg(PUPPET_SVG).createPuppet()
}

// ---------------------------------------------------------------------------------------------
// The engine view.
// ---------------------------------------------------------------------------------------------

class IsometricEngineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val dp = resources.displayMetrics.density
    private val sp = dp * resources.configuration.fontScale

    // ---- threading
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var choreographer: Choreographer? = null
    @Volatile private var running = false
    @Volatile private var hostPaused = false
    private var framePending = false
    private var lastFrameNanos = 0L
    private val queue = ConcurrentLinkedQueue<() -> Unit>()

    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    @Volatile private var hudTopInset = 0
    @Volatile private var hudBottomInset = 0

    // ---- input accumulators (main thread -> render thread)
    private val inputLock = Any()
    private var pendDx = 0f
    private var pendDy = 0f
    private var pendZoom = 1f
    private var pendFocusX = 0f
    private var pendFocusY = 0f
    private var pendHasZoom = false
    private var pendFling = false
    private var pendFlingVx = 0f
    private var pendFlingVy = 0f
    private var pendTap = false
    private var pendTapX = 0f
    private var pendTapY = 0f

    // ---- camera (render thread)
    private var camInit = false
    private var camX = 0f
    private var camY = 0f
    private var camZoom = 1f
    private var tgtX = 0f
    private var tgtY = 0f
    private var tgtZoom = 1f
    private var velX = 0f
    private var velY = 0f
    private var followTimer = 0f
    private var trauma = 0f
    private var shakeX = 0f
    private var shakeY = 0f
    private var shakeRoll = 0f
    private var sceneTime = 0f

    // ---- simulation state
    private val player = TimelinePlayer()
    private var captions: List<CaptionLine> = emptyList()
    private var mood = Mood.CALM
    private var bpmTarget = Mood.CALM.bpm
    private var bpmShown = Mood.CALM.bpm
    private var clockMinutes = 23f * 60f + 58f
    private var dayNumber = 3
    private var startDate: LocalDate = LocalDate.now()
    private var dayText = "DAY 3"
    private var dateText = ""
    private var clockText = ""
    private var lastClockMinute = -1
    private var bpmText = "72"
    private var lastBpmInt = -1
    private var lightScale = 1f
    private var lightDirX = 1f
    private var lightDirY = 0.5f
    private var lightAimTimer = 0f
    private var lightAimX = 0f
    private var lightAimY = 0f
    private var prevPuppetGx = 0f
    private var prevPuppetGy = 0f
    private var fpsEma = 60f

    // ---- ECG
    private val ecg = FloatArray(ECG_N)
    private var ecgHead = 0
    private var ecgPhase = 0f
    private var ecgAcc = 0f
    private var heartPulse = 0f

    // ---- scene
    @Volatile private var floorAsset: VectorAsset? = null
    private val entities = ArrayList<IsoEntity>()
    private val entityComparator = Comparator<IsoEntity> { a, b ->
        val c = a.sortKey.compareTo(b.sortKey)
        if (c != 0) c else a.layer.compareTo(b.layer)
    }
    private val windowGlass = ArrayList<Path>()
    private val moonPatches = ArrayList<Path>()

    // ---- paints / reusable objects
    private val ep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Path()
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val coneShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xFF000000.toInt(), 0xD0000000.toInt(), 0x00000000),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
    )
    private val coneMatrix = Matrix()
    private val conePath = Path()
    private val coneHalf = floatArrayOf(0.52f, 0.37f, 0.21f)
    private val coneAlpha = floatArrayOf(0.40f, 0.40f, 0.60f)

    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x990B0F1A.toInt() }
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val pDay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * sp
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private val pDate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = 11f * sp
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.06f
    }
    private val pClock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f * sp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val pBpm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f * sp
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val pBpmLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = 10f * sp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val pBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * sp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val pBadgeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBadgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
    }
    private val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val heartPath = SvgPathParser.parse(
        "M12 21C4 14 2 10 2 7.5C2 4.5 4.5 3 7 3C9 3 11 4 12 6C13 4 15 3 17 3C19.5 3 22 4.5 22 7.5C22 10 20 14 12 21Z"
    ).path
    private val ecgGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ecgLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ecgPath = Path()
    private val hudRect = RectF()
    private val pFps = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = 10f * sp
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.MONOSPACE
    }
    private var fpsText = "60 FPS"
    private var fpsTextAcc = 0f

    private val capFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f * sp
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private val capStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f * sp
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        style = Paint.Style.STROKE
        strokeWidth = 6f * dp
        strokeJoin = Paint.Join.ROUND
    }
    private var capLayoutLine: CaptionLine? = null
    private var capLayoutW = 0
    private var capWordX = FloatArray(0)
    private var capWordLine = IntArray(0)
    private var capLines = 1

    // ---- gesture state (main thread)
    private var velocityTracker: VelocityTracker? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var pinchDist = 0f
    private var lastCx = 0f
    private var lastCy = 0f

    private val puppetEntity = PuppetEntity()

    // -------------------------------------------------------------------------------------
    // Entities
    // -------------------------------------------------------------------------------------

    private abstract inner class IsoEntity {
        var gx = 0f
        var gy = 0f
        var z = 0f
        var layer = 0
        var sortKey = 0f
        abstract fun draw(c: Canvas)
    }

    private inner class TileEntity(val i: Int, val j: Int) : IsoEntity() {
        init {
            gx = i + 0.5f
            gy = j + 0.5f
        }

        override fun draw(c: Canvas) {
            val asset = floorAsset
            if (asset != null) {
                asset.drawAnchored(c, wxOf(gx, gy), wyOf(gx, gy) + TH * 0.5f, TW)
                return
            }
            val fi = i.toFloat()
            val fj = j.toFloat()
            tp.reset()
            tp.moveTo(wxOf(fi, fj), wyOf(fi, fj))
            tp.lineTo(wxOf(fi + 1f, fj), wyOf(fi + 1f, fj))
            tp.lineTo(wxOf(fi + 1f, fj + 1f), wyOf(fi + 1f, fj + 1f))
            tp.lineTo(wxOf(fi, fj + 1f), wyOf(fi, fj + 1f))
            tp.close()
            ep.style = Paint.Style.FILL
            ep.color = if ((i + j) % 2 == 0) 0xFF3A2F2A.toInt() else 0xFF2F2629.toInt()
            c.drawPath(tp, ep)
            ep.style = Paint.Style.STROKE
            ep.strokeWidth = 1f
            ep.color = 0x26FFFFFF
            c.drawPath(tp, ep)
        }
    }

    private inner class BoxEntity(
        x0: Float, y0: Float, val sx: Float, val sy: Float, elevation: Float,
        val h: Float, val colTop: Int, val colLeft: Int, val colRight: Int
    ) : IsoEntity() {
        val x0 = x0
        val y0 = y0

        init {
            gx = x0 + sx * 0.5f
            gy = y0 + sy * 0.5f
            z = elevation
            layer = 1
        }

        override fun draw(c: Canvas) {
            val x1 = x0 + sx
            val y1 = y0 + sy
            val bz = z
            val tz = z + h
            // visible faces: front-left (south-west), front-right (south-east) and top
            ep.style = Paint.Style.FILL
            ep.color = colLeft
            tp.reset()
            tp.moveTo(wxOf(x0, y1), wyOf(x0, y1, bz))
            tp.lineTo(wxOf(x1, y1), wyOf(x1, y1, bz))
            tp.lineTo(wxOf(x1, y1), wyOf(x1, y1, tz))
            tp.lineTo(wxOf(x0, y1), wyOf(x0, y1, tz))
            tp.close()
            c.drawPath(tp, ep)

            ep.color = colRight
            tp.reset()
            tp.moveTo(wxOf(x1, y1), wyOf(x1, y1, bz))
            tp.lineTo(wxOf(x1, y0), wyOf(x1, y0, bz))
            tp.lineTo(wxOf(x1, y0), wyOf(x1, y0, tz))
            tp.lineTo(wxOf(x1, y1), wyOf(x1, y1, tz))
            tp.close()
            c.drawPath(tp, ep)

            ep.color = colTop
            tp.reset()
            tp.moveTo(wxOf(x0, y0), wyOf(x0, y0, tz))
            tp.lineTo(wxOf(x1, y0), wyOf(x1, y0, tz))
            tp.lineTo(wxOf(x1, y1), wyOf(x1, y1, tz))
            tp.lineTo(wxOf(x0, y1), wyOf(x0, y1, tz))
            tp.close()
            c.drawPath(tp, ep)

            ep.style = Paint.Style.STROKE
            ep.strokeWidth = 1f
            ep.color = 0x33000000
            c.drawPath(tp, ep)
        }
    }

    /** axis 0: wall along +X at gy = 0, axis 1: wall along +Y at gx = 0. */
    private inner class WallEntity(val axis: Int, val idx: Int, val window: Boolean) : IsoEntity() {
        init {
            if (axis == 0) {
                gx = idx + 0.5f
                gy = 0f
            } else {
                gx = 0f
                gy = idx + 0.5f
            }
        }

        private fun px(u: Float): Float = if (axis == 0) wxOf(u, 0f) else wxOf(0f, u)
        private fun py(u: Float, zz: Float): Float = if (axis == 0) wyOf(u, 0f, zz) else wyOf(0f, u, zz)

        private fun quad(u0: Float, u1: Float, z0: Float, z1: Float) {
            tp.reset()
            tp.moveTo(px(u0), py(u0, z0))
            tp.lineTo(px(u1), py(u1, z0))
            tp.lineTo(px(u1), py(u1, z1))
            tp.lineTo(px(u0), py(u0, z1))
            tp.close()
        }

        override fun draw(c: Canvas) {
            val u0 = idx.toFloat()
            val u1 = u0 + 1f
            val base = if (axis == 0) 0xFF2D3250.toInt() else 0xFF23273F.toInt()
            ep.style = Paint.Style.FILL
            ep.color = base
            quad(u0, u1, 0f, WALL_H)
            c.drawPath(tp, ep)
            ep.color = shade(base, 0.72f)
            quad(u0, u1, 0f, 12f)
            c.drawPath(tp, ep)
            ep.color = shade(base, 1.25f)
            quad(u0, u1, WALL_H - 6f, WALL_H)
            c.drawPath(tp, ep)
            if (window) {
                ep.color = 0xFF9DB4FF.toInt()
                quad(u0 + 0.15f, u1 - 0.15f, 64f, 136f)
                c.drawPath(tp, ep)
                ep.style = Paint.Style.STROKE
                ep.strokeWidth = 3f
                ep.color = 0xFF10131F.toInt()
                c.drawPath(tp, ep)
                val mid = (u0 + u1) * 0.5f
                c.drawLine(px(mid), py(mid, 64f), px(mid), py(mid, 136f), ep)
                val zm = 100f
                c.drawLine(px(u0 + 0.15f), py(u0 + 0.15f, zm), px(u1 - 0.15f), py(u1 - 0.15f, zm), ep)
            }
            ep.style = Paint.Style.STROKE
            ep.strokeWidth = 1f
            ep.color = 0x22000000
            quad(u0, u1, 0f, WALL_H)
            c.drawPath(tp, ep)
        }
    }

    private inner class AssetPropEntity(
        val asset: VectorAsset, cx: Float, cy: Float, elevation: Float, val widthPx: Float
    ) : IsoEntity() {
        init {
            gx = cx
            gy = cy
            z = elevation
            layer = 1
        }

        override fun draw(c: Canvas) {
            asset.drawAnchored(c, wxOf(gx, gy), wyOf(gx, gy, z), widthPx)
        }
    }

    private inner class PuppetEntity : IsoEntity() {
        var puppet: Puppet = DefaultAssets.createPuppet()
        var heightPx = 112f
        var flip = 1f

        init {
            layer = 2
            gx = 4f
            gy = 2.5f
        }

        override fun draw(c: Canvas) {
            val x = wxOf(gx, gy)
            val groundY = wyOf(gx, gy, 0f)
            val sw = 22f * (1f - (z / 120f).coerceIn(0f, 0.6f))
            ep.style = Paint.Style.FILL
            ep.color = 0x66000000
            c.drawOval(x - sw, groundY - sw * 0.38f, x + sw, groundY + sw * 0.38f, ep)

            val vb = puppet.viewBox
            val s = heightPx / vb.height()
            c.save()
            c.translate(x, wyOf(gx, gy, z))
            c.scale(s * flip, s)
            c.translate(-vb.centerX(), -vb.bottom)
            puppet.draw(c)
            c.restore()
        }
    }

    // -------------------------------------------------------------------------------------
    // Public API (thread-safe: mutations are marshalled onto the render thread)
    // -------------------------------------------------------------------------------------

    fun enqueue(action: () -> Unit) {
        queue.add(action)
    }

    fun setFloorTileAsset(asset: VectorAsset?) {
        floorAsset = asset
    }

    fun setPuppetAsset(asset: VectorAsset) {
        val pup = asset.createPuppet()
        enqueue { puppetEntity.puppet = pup }
    }

    fun addAssetProp(asset: VectorAsset, gridX: Float, gridY: Float, widthPx: Float, elevation: Float = 0f) {
        enqueue { entities.add(AssetPropEntity(asset, gridX, gridY, elevation, widthPx)) }
    }

    fun setTimeline(timeline: Timeline?) {
        player.timeline = timeline
        player.timeMs = 0f
    }

    fun setCaptions(lines: List<CaptionLine>) {
        enqueue { captions = lines }
    }

    fun setMood(m: Mood) {
        enqueue { setMoodInternal(m) }
    }

    fun setClock(day: Int, minutesOfDay: Float) {
        enqueue {
            dayNumber = day
            clockMinutes = minutesOfDay
            lastClockMinute = -1
            refreshDayText()
        }
    }

    fun shake(intensity: Float) {
        enqueue { trauma = max(trauma, intensity.coerceIn(0f, 1f)) }
    }

    fun play() { player.playing = true }
    fun pause() { player.playing = false }
    fun togglePlay() { player.playing = !player.playing }
    fun seekFrame(frame: Int) { player.seekFrame(frame) }
    fun stepFrames(n: Int) { player.stepFrames(n) }
    fun setPlaybackSpeed(speed: Float) { player.speed = speed }
    val isPlaying: Boolean get() = player.playing

    fun captureFrame(callback: (Bitmap?) -> Unit) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || !holder.surface.isValid) {
            callback(null)
            return
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(this, bmp, { result ->
                callback(if (result == PixelCopy.SUCCESS) bmp else null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            callback(null)
        }
    }

    fun onHostPause() {
        hostPaused = true
    }

    fun onHostResume() {
        hostPaused = false
        scheduleFrame()
    }

    // -------------------------------------------------------------------------------------
    // Surface lifecycle & render thread
    // -------------------------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRenderThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        viewW = width
        viewH = height
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                holder.surface.setFrameRate(144f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (_: Throwable) {
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRenderThread()
    }

    override fun onDetachedFromWindow() {
        stopRenderThread()
        super.onDetachedFromWindow()
    }

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var t = 0
        var b = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            t = i.top
            b = i.bottom
        } else {
            t = insets.systemWindowInsetTop
            b = insets.systemWindowInsetBottom
        }
        hudTopInset = t
        hudBottomInset = b
        return super.onApplyWindowInsets(insets)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            framePending = false
            if (!running) return
            if (hostPaused) {
                lastFrameNanos = 0L
                return
            }
            renderFrame(frameTimeNanos)
            framePending = true
            choreographer?.postFrameCallback(this)
        }
    }

    private fun scheduleFrame() {
        renderHandler?.post {
            if (running && !hostPaused && !framePending) {
                framePending = true
                choreographer?.postFrameCallback(frameCallback)
            }
        }
    }

    private fun startRenderThread() {
        if (running) return
        val t = HandlerThread("IsoRender", android.os.Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        renderThread = t
        val h = Handler(t.looper)
        renderHandler = h
        running = true
        lastFrameNanos = 0L
        h.post {
            val ch = Choreographer.getInstance()
            choreographer = ch
            framePending = true
            ch.postFrameCallback(frameCallback)
        }
    }

    private fun stopRenderThread() {
        running = false
        val t = renderThread ?: return
        renderHandler?.post { choreographer?.removeFrameCallback(frameCallback) }
        t.quitSafely()
        try {
            t.join(500)
        } catch (_: InterruptedException) {
        }
        renderThread = null
        renderHandler = null
        choreographer = null
    }

    private fun renderFrame(frameTimeNanos: Long) {
        if (!holder.surface.isValid) return
        val dt = if (lastFrameNanos == 0L) 1f / 60f
        else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.0005f, 0.05f)
        lastFrameNanos = frameTimeNanos

        update(dt)

        val canvas: Canvas? = try {
            holder.lockHardwareCanvas()
        } catch (e: Exception) {
            null
        }
        if (canvas == null) return
        try {
            drawScene(canvas)
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Input: low-latency multi-touch (unbuffered dispatch), pan / pinch / fling / tap
    // -------------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(e) }
                lastTouchX = e.x
                lastTouchY = e.y
                downX = e.x
                downY = e.y
                downTime = e.eventTime
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    pinchDist = dist(e)
                    lastCx = (e.getX(0) + e.getX(1)) * 0.5f
                    lastCy = (e.getY(0) + e.getY(1)) * 0.5f
                    moved = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(e)
                if (e.pointerCount >= 2) {
                    val cx = (e.getX(0) + e.getX(1)) * 0.5f
                    val cy = (e.getY(0) + e.getY(1)) * 0.5f
                    val d = dist(e)
                    synchronized(inputLock) {
                        pendDx += cx - lastCx
                        pendDy += cy - lastCy
                        if (pinchDist > 8f && d > 8f) {
                            pendZoom *= d / pinchDist
                            pendFocusX = cx
                            pendFocusY = cy
                            pendHasZoom = true
                        }
                    }
                    pinchDist = d
                    lastCx = cx
                    lastCy = cy
                } else {
                    val dx = e.x - lastTouchX
                    val dy = e.y - lastTouchY
                    if (!moved && (abs(e.x - downX) > 10f * dp || abs(e.y - downY) > 10f * dp)) moved = true
                    if (moved) {
                        synchronized(inputLock) {
                            pendDx += dx
                            pendDy += dy
                        }
                    }
                    lastTouchX = e.x
                    lastTouchY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount == 2) {
                    val remaining = if (e.actionIndex == 0) 1 else 0
                    lastTouchX = e.getX(remaining)
                    lastTouchY = e.getY(remaining)
                    moved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val vt = velocityTracker
                if (vt != null) {
                    vt.addMovement(e)
                    vt.computeCurrentVelocity(1000, 6000f)
                    if (moved) {
                        synchronized(inputLock) {
                            pendFling = true
                            pendFlingVx = vt.xVelocity
                            pendFlingVy = vt.yVelocity
                        }
                    }
                    vt.recycle()
                    velocityTracker = null
                }
                if (!moved && e.eventTime - downTime < 260L) {
                    synchronized(inputLock) {
                        pendTap = true
                        pendTapX = e.x
                        pendTapY = e.y
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    private fun dist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    // -------------------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------------------

    private val trackSink: (String, Float) -> Unit = { name, v -> applyTrack(name, v) }

    private fun applyTrack(name: String, v: Float) {
        when (name) {
            "puppet.gx" -> puppetEntity.gx = v
            "puppet.gy" -> puppetEntity.gy = v
            "puppet.z" -> puppetEntity.z = v
            "puppet.flip" -> puppetEntity.flip = if (v < 0f) -1f else 1f
            "cam.shake" -> if (v > trauma) trauma = min(v, 1f)
            "light.radius" -> lightScale = v
            "mood" -> setMoodInternal(Mood.fromIndex(v.roundToInt()))
            else -> if (name.startsWith("joint.")) puppetEntity.puppet.setValue(name.substring(6), v)
        }
    }

    private fun setMoodInternal(m: Mood) {
        if (m != mood) {
            mood = m
            bpmTarget = m.bpm
        }
    }

    private fun refreshDayText() {
        dayText = "DAY $dayNumber"
        val d = startDate.plusDays((dayNumber - 3).toLong())
        val dow = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).uppercase(Locale.ENGLISH)
        val mon = d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).uppercase(Locale.ENGLISH)
        dateText = "$dow $mon ${d.dayOfMonth}"
    }

    private fun update(dt: Float) {
        while (true) {
            val a = queue.poll() ?: break
            a()
        }
        sceneTime += dt

        if (!camInit && viewW > 0) {
            val z = viewW / (7f * TW)
            camZoom = z
            tgtZoom = z
            camX = wxOf(puppetEntity.gx, puppetEntity.gy)
            camY = wyOf(puppetEntity.gx, puppetEntity.gy, 40f)
            tgtX = camX
            tgtY = camY
            camInit = true
            refreshDayText()
        }

        // timeline
        player.advance(dt * 1000f)
        player.timeline?.apply(player.timeMs, trackSink)
        puppetEntity.puppet.updateTransforms()

        // facing / light direction from motion
        val dgx = puppetEntity.gx - prevPuppetGx
        val dgy = puppetEntity.gy - prevPuppetGy
        prevPuppetGx = puppetEntity.gx
        prevPuppetGy = puppetEntity.gy
        val sdx = (dgx - dgy) * TW * 0.5f
        val sdy = (dgx + dgy) * TH * 0.5f
        val originX = wxOf(puppetEntity.gx, puppetEntity.gy)
        val originY = wyOf(puppetEntity.gx, puppetEntity.gy, 62f)
        var tdx = lightDirX
        var tdy = lightDirY
        if (lightAimTimer > 0f) {
            lightAimTimer -= dt
            tdx = lightAimX - originX
            tdy = lightAimY - originY
            val l = sqrt(tdx * tdx + tdy * tdy)
            if (l > 1e-3f) {
                tdx /= l
                tdy /= l
            }
        } else if (sdx * sdx + sdy * sdy > 1e-6f) {
            val l = sqrt(sdx * sdx + sdy * sdy)
            tdx = sdx / l
            tdy = sdy / l
        }
        val la = 1f - exp(-dt * 9f)
        lightDirX += (tdx - lightDirX) * la
        lightDirY += (tdy - lightDirY) * la
        val ll = sqrt(lightDirX * lightDirX + lightDirY * lightDirY)
        if (ll > 1e-3f) {
            lightDirX /= ll
            lightDirY /= ll
        }

        consumeInput(dt)
        updateCamera(dt)
        updateClockAndVitals(dt)

        // dynamic Z-sort: RenderOrder = (X + Y) * tileSize + Z
        for (i in 0 until entities.size) {
            val e = entities[i]
            e.sortKey = (e.gx + e.gy) * TS + e.z
        }
        java.util.Collections.sort(entities, entityComparator)
    }

    private fun consumeInput(dt: Float) {
        var dx = 0f
        var dy = 0f
        var zoomF = 1f
        var hasZoom = false
        var fx = 0f
        var fy = 0f
        var fling = false
        var fvx = 0f
        var fvy = 0f
        var tap = false
        var tx = 0f
        var ty = 0f
        synchronized(inputLock) {
            dx = pendDx; dy = pendDy
            zoomF = pendZoom; hasZoom = pendHasZoom; fx = pendFocusX; fy = pendFocusY
            fling = pendFling; fvx = pendFlingVx; fvy = pendFlingVy
            tap = pendTap; tx = pendTapX; ty = pendTapY
            pendDx = 0f; pendDy = 0f
            pendZoom = 1f; pendHasZoom = false
            pendFling = false
            pendTap = false
        }

        if (dx != 0f || dy != 0f) {
            tgtX -= dx / tgtZoom
            tgtY -= dy / tgtZoom
            followTimer = 4f
            velX = 0f
            velY = 0f
        }
        if (hasZoom) {
            val ox = fx - viewW * 0.5f
            val oy = fy - viewH * 0.5f
            val wfx = tgtX + ox / tgtZoom
            val wfy = tgtY + oy / tgtZoom
            tgtZoom = (tgtZoom * zoomF).coerceIn(0.55f, 5f)
            tgtX = wfx - ox / tgtZoom
            tgtY = wfy - oy / tgtZoom
            followTimer = 4f
        }
        if (fling) {
            velX = fvx
            velY = fvy
        }
        if (abs(velX) > 2f || abs(velY) > 2f) {
            tgtX -= velX / tgtZoom * dt
            tgtY -= velY / tgtZoom * dt
            val decay = exp(-dt * 4.5f)
            velX *= decay
            velY *= decay
        }
        if (tap && camInit) {
            val wxT = camX + (tx - viewW * 0.5f) / camZoom
            val wyT = camY + (ty - viewH * 0.5f) / camZoom
            lightAimX = wxT
            lightAimY = wyT
            lightAimTimer = 2.5f
            trauma = max(trauma, 0.22f)
        }
    }

    private fun updateCamera(dt: Float) {
        if (!camInit) return
        if (followTimer > 0f) {
            followTimer -= dt
        } else {
            tgtX = wxOf(puppetEntity.gx, puppetEntity.gy)
            tgtY = wyOf(puppetEntity.gx, puppetEntity.gy, 40f)
        }
        val half = GRID_N * TW * 0.5f
        tgtX = tgtX.coerceIn(-half - 80f, half + 80f)
        tgtY = tgtY.coerceIn(-WALL_H - 80f, GRID_N * TH + 80f)

        val pa = 1f - exp(-dt * 12f)
        val za = 1f - exp(-dt * 10f)
        camX += (tgtX - camX) * pa
        camY += (tgtY - camY) * pa
        camZoom += (tgtZoom - camZoom) * za

        // screen shake: trauma^2 amplitude, per-axis amplitude proportional to the 20:9 viewport
        trauma = max(0f, trauma - dt * 1.6f)
        val amp = trauma * trauma
        val t = sceneTime
        shakeX = amp * viewW * 0.020f * noise(t * 38f, 0.0f)
        shakeY = amp * viewH * 0.009f * noise(t * 41f, 11.3f)
        shakeRoll = amp * 2.4f * noise(t * 33f, 27.1f)
    }

    private fun noise(t: Float, seed: Float): Float =
        (sin(t + seed) + 0.5f * sin(t * 2.3f + seed * 1.7f) + 0.25f * sin(t * 4.1f + seed * 2.9f)) / 1.75f

    private fun updateClockAndVitals(dt: Float) {
        clockMinutes += dt * 0.5f
        if (clockMinutes >= 1440f) {
            clockMinutes -= 1440f
            dayNumber++
            refreshDayText()
        }
        val mi = clockMinutes.toInt()
        if (mi != lastClockMinute) {
            lastClockMinute = mi
            val h24 = mi / 60
            val m = mi % 60
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            clockText = "$h12:${if (m < 10) "0$m" else "$m"} ${if (h24 < 12) "AM" else "PM"}"
        }

        val ba = 1f - exp(-dt * 1.6f)
        bpmShown += (bpmTarget - bpmShown) * ba
        val shownInt = (bpmShown + 1.6f * sin(sceneTime * 1.7f)).roundToInt()
        if (shownInt != lastBpmInt) {
            lastBpmInt = shownInt
            bpmText = shownInt.toString()
        }

        heartPulse = max(0f, heartPulse - dt * 5.5f)
        ecgAcc += dt
        val step = 1f / ECG_RATE
        while (ecgAcc >= step) {
            ecgAcc -= step
            val prev = ecgPhase
            ecgPhase += (bpmShown / 60f) / ECG_RATE
            if (ecgPhase >= 1f) ecgPhase -= 1f
            if (prev < 0.33f && ecgPhase >= 0.33f) heartPulse = 1f
            else if (prev > ecgPhase && ecgPhase >= 0.33f) heartPulse = 1f
            val jitter = (kotlin.random.Random.nextFloat() - 0.5f) * 2f * mood.jitter
            ecg[ecgHead] = ecgWave(ecgPhase) + jitter
            ecgHead = (ecgHead + 1) % ECG_N
        }

        fpsEma += ((1f / dt) - fpsEma) * 0.04f
        fpsTextAcc += dt
        if (fpsTextAcc > 0.5f) {
            fpsTextAcc = 0f
            fpsText = "${fpsEma.roundToInt()} FPS"
        }
    }

    private fun gauss(x: Float, mu: Float, sigma: Float): Float {
        val d = x - mu
        return exp(-(d * d) / (2f * sigma * sigma))
    }

    private fun ecgWave(p: Float): Float =
        0.14f * gauss(p, 0.18f, 0.030f) -
            0.12f * gauss(p, 0.30f, 0.012f) +
            1.00f * gauss(p, 0.33f, 0.010f) -
            0.24f * gauss(p, 0.365f, 0.012f) +
            0.28f * gauss(p, 0.60f, 0.050f)

    // -------------------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------------------

    private fun applyCamera(c: Canvas) {
        c.translate(viewW * 0.5f + shakeX, viewH * 0.5f + shakeY)
        c.rotate(shakeRoll)
        c.scale(camZoom, camZoom)
        c.translate(-camX, -camY)
    }

    private fun drawScene(c: Canvas) {
        c.drawColor(0xFF05070D.toInt())
        if (!camInit) return
        c.save()
        applyCamera(c)
        drawWorld(c)
        c.restore()
        drawLighting(c)
        drawHud(c)
        drawCaptions(c)
    }

    private fun drawWorld(c: Canvas) {
        val hw = viewW * 0.5f / camZoom + 240f
        val hh = viewH * 0.5f / camZoom + 260f
        for (i in 0 until entities.size) {
            val e = entities[i]
            val ex = wxOf(e.gx, e.gy)
            val ey = wyOf(e.gx, e.gy, e.z)
            if (abs(ex - camX) > hw || abs(ey - camY) > hh) continue
            e.draw(c)
        }
    }

    private fun darkness(): Float {
        val h = clockMinutes / 60f
        return 0.5f - 0.5f * cos(((h - 13f) / 24f) * 2f * PI.toFloat())
    }

    private fun drawLighting(c: Canvas) {
        val dark = darkness()
        val ambient = (0.10f + 0.82f * dark).coerceIn(0f, 0.95f)
        val ambientColor = Color.argb((ambient * 255f).toInt(), 4, 6, 18)

        val layer = c.saveLayer(0f, 0f, viewW.toFloat(), viewH.toFloat(), null)
        c.drawColor(ambientColor)

        c.save()
        applyCamera(c)

        // window cutouts: glass on the walls + moonlight patches on the floor
        cutPaint.shader = null
        cutPaint.color = withAlpha(Color.BLACK, 0.85f)
        for (p in windowGlass) c.drawPath(p, cutPaint)
        cutPaint.color = withAlpha(Color.BLACK, 0.18f + 0.32f * dark)
        for (p in moonPatches) c.drawPath(p, cutPaint)

        // flashlight + personal glow
        val ox = wxOf(puppetEntity.gx, puppetEntity.gy)
        val oy = wyOf(puppetEntity.gx, puppetEntity.gy, 62f)
        val strength = 0.45f + 0.55f * dark
        val ang = atan2(lightDirY, lightDirX)
        drawCone(c, ox + lightDirX * 14f, oy + lightDirY * 14f, ang, 330f * lightScale, strength)
        drawGlow(c, ox, oy + 10f, 85f, 0.55f * strength)

        c.restore()
        c.restoreToCount(layer)
    }

    private fun drawCone(c: Canvas, ox: Float, oy: Float, ang: Float, len: Float, strength: Float) {
        coneMatrix.setScale(len, len)
        coneMatrix.postTranslate(ox, oy)
        coneShader.setLocalMatrix(coneMatrix)
        cutPaint.shader = coneShader
        for (k in 0 until 3) {
            cutPaint.alpha = (255f * coneAlpha[k] * strength).toInt().coerceIn(0, 255)
            val a1 = ang - coneHalf[k]
            val a2 = ang + coneHalf[k]
            conePath.reset()
            conePath.moveTo(ox, oy)
            conePath.lineTo(ox + cos(a1) * len * 1.15f, oy + sin(a1) * len * 1.15f)
            conePath.lineTo(ox + cos(a2) * len * 1.15f, oy + sin(a2) * len * 1.15f)
            conePath.close()
            c.drawPath(conePath, cutPaint)
        }
        cutPaint.shader = null
    }

    private fun drawGlow(c: Canvas, ox: Float, oy: Float, radius: Float, alpha: Float) {
        coneMatrix.setScale(radius, radius)
        coneMatrix.postTranslate(ox, oy)
        coneShader.setLocalMatrix(coneMatrix)
        cutPaint.shader = coneShader
        cutPaint.alpha = (255f * alpha).toInt().coerceIn(0, 255)
        c.drawCircle(ox, oy, radius, cutPaint)
        cutPaint.shader = null
    }

    private fun drawHud(c: Canvas) {
        val left = 14f * dp
        val top = hudTopInset + 12f * dp
        val w = 244f * dp
        val h = 136f * dp
        hudRect.set(left, top, left + w, top + h)
        c.drawRoundRect(hudRect, 14f * dp, 14f * dp, panelFill)
        c.drawRoundRect(hudRect, 14f * dp, 14f * dp, panelStroke)

        val ix = left + 14f * dp
        val ex = left + w - 14f * dp
        c.drawText(dayText, ix, top + 28f * dp, pDay)
        c.drawText(dateText, ex, top + 27f * dp, pDate)
        c.drawText(clockText, ix, top + 66f * dp, pClock)

        // heart + BPM
        val hcx = ix + 11f * dp
        val hcy = top + 88f * dp
        val hs = (22f * dp / 24f) * (1f + 0.24f * heartPulse)
        heartPaint.color = mood.color
        c.save()
        c.translate(hcx, hcy)
        c.scale(hs, hs)
        c.translate(-12f, -12f)
        c.drawPath(heartPath, heartPaint)
        c.restore()
        pBpm.color = Color.WHITE
        val bx = ix + 30f * dp
        c.drawText(bpmText, bx, top + 96f * dp, pBpm)
        c.drawText("BPM", bx + pBpm.measureText(bpmText) + 4f * dp, top + 96f * dp, pBpmLabel)

        // status badge
        val label = mood.label
        val bw = pBadge.measureText(label) + 22f * dp
        val bh = 22f * dp
        val by = top + 78f * dp
        hudRect.set(ex - bw, by, ex, by + bh)
        val pulse = if (mood == Mood.PANIC) 0.5f + 0.5f * sin(sceneTime * 11f) else 0f
        pBadgeFill.color = withAlpha(mood.color, 0.20f + 0.22f * pulse)
        pBadgeStroke.color = mood.color
        pBadge.color = mood.color
        c.drawRoundRect(hudRect, bh * 0.5f, bh * 0.5f, pBadgeFill)
        c.drawRoundRect(hudRect, bh * 0.5f, bh * 0.5f, pBadgeStroke)
        c.drawText(label, hudRect.centerX(), hudRect.centerY() + pBadge.textSize * 0.35f, pBadge)

        // scrolling ECG
        val x0 = ix
        val x1 = ex
        val ecgTop = top + 104f * dp
        val ecgH = 26f * dp
        val baseY = ecgTop + ecgH * 0.74f
        val amp = ecgH * 0.70f
        val stepX = (x1 - x0) / (ECG_N - 1)
        ecgPath.reset()
        for (k in 0 until ECG_N) {
            val v = ecg[(ecgHead + k) % ECG_N]
            val px = x0 + k * stepX
            val py = baseY - v * amp
            if (k == 0) ecgPath.moveTo(px, py) else ecgPath.lineTo(px, py)
        }
        ecgGlow.color = withAlpha(mood.color, 0.20f)
        ecgLine.color = mood.color
        c.drawPath(ecgPath, ecgGlow)
        c.drawPath(ecgPath, ecgLine)
        val newest = ecg[(ecgHead + ECG_N - 1) % ECG_N]
        heartPaint.color = Color.WHITE
        c.drawCircle(x1, baseY - newest * amp, 2.4f * dp, heartPaint)

        c.drawText(fpsText, viewW - 14f * dp, hudTopInset + 22f * dp, pFps)
    }

    private fun layoutCaption(line: CaptionLine) {
        val words = line.words
        val n = words.size
        if (capWordX.size < n) {
            capWordX = FloatArray(n)
            capWordLine = IntArray(n)
        }
        val maxW = viewW * 0.88f
        val space = capFill.measureText(" ")
        val lineW = FloatArray(n + 1)
        var lineIdx = 0
        var cur = 0f
        for (i in 0 until n) {
            val ww = capFill.measureText(words[i].text)
            if (cur > 0f && cur + space + ww > maxW) {
                lineW[lineIdx] = cur
                lineIdx++
                cur = ww
                capWordX[i] = 0f
            } else {
                capWordX[i] = if (cur == 0f) 0f else cur + space
                cur = if (cur == 0f) ww else cur + space + ww
            }
            capWordLine[i] = lineIdx
        }
        lineW[lineIdx] = cur
        capLines = lineIdx + 1
        for (i in 0 until n) capWordX[i] += (viewW - lineW[capWordLine[i]]) * 0.5f
        capLayoutLine = line
        capLayoutW = viewW
    }

    private fun drawCaptions(c: Canvas) {
        val t = player.timeMs
        var line: CaptionLine? = null
        for (l in captions) {
            if (t >= l.startMs - 120f && t <= l.endMs + 450f) {
                line = l
                break
            }
        }
        if (line == null || line.words.isEmpty()) return
        if (capLayoutLine !== line || capLayoutW != viewW) layoutCaption(line)

        val fadeIn = ((t - (line.startMs - 120f)) / 120f).coerceIn(0f, 1f)
        val fadeOut = ((line.endMs + 450f - t) / 200f).coerceIn(0f, 1f)
        val fade = min(fadeIn, fadeOut)

        val lineH = capFill.textSize * 1.28f
        val baseY = viewH - hudBottomInset - 110f * dp
        capStroke.color = withAlpha(Color.BLACK, fade * 0.95f)
        val words = line.words
        for (i in words.indices) {
            val w = words[i]
            val x = capWordX[i]
            val y = baseY - (capLines - 1 - capWordLine[i]) * lineH
            val active = t >= w.startMs && t < w.endMs
            val fillColor = when {
                active -> 0xFFFFD21F.toInt()
                t >= w.endMs -> Color.WHITE
                else -> 0xD0FFFFFF.toInt()
            }
            capFill.color = withAlpha(fillColor, fade)
            if (active) {
                val pop = 1f + 0.04f + 0.10f * (1f - ((t - w.startMs) / 180f).coerceIn(0f, 1f))
                val wordW = capFill.measureText(w.text)
                c.save()
                c.scale(pop, pop, x + wordW * 0.5f, y - capFill.textSize * 0.35f)
                c.drawText(w.text, x, y, capStroke)
                c.drawText(w.text, x, y, capFill)
                c.restore()
            } else {
                c.drawText(w.text, x, y, capStroke)
                c.drawText(w.text, x, y, capFill)
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Demo scene, timeline and captions
    // -------------------------------------------------------------------------------------

    private fun buildScene() {
        for (j in 0 until GRID_N) for (i in 0 until GRID_N) entities.add(TileEntity(i, j))
        for (i in 0 until GRID_N) entities.add(WallEntity(0, i, i == 3 || i == 7))
        for (j in 1 until GRID_N) entities.add(WallEntity(1, j, j == 6))

        entities.add(BoxEntity(2f, 4f, 1f, 1f, 0f, 36f, 0xFFB08A5A.toInt(), 0xFF8A6A42.toInt(), 0xFF6E5233.toInt()))
        entities.add(BoxEntity(2f, 4f, 1f, 1f, 36f, 30f, 0xFFC39A66.toInt(), 0xFF9A7747.toInt(), 0xFF7B5C38.toInt()))
        entities.add(BoxEntity(2f, 5f, 1f, 1f, 0f, 36f, 0xFFB08A5A.toInt(), 0xFF8A6A42.toInt(), 0xFF6E5233.toInt()))
        entities.add(BoxEntity(7f, 7f, 2f, 1f, 0f, 34f, 0xFF6B4A33.toInt(), 0xFF4F3625.toInt(), 0xFF3C281B.toInt()))
        entities.add(BoxEntity(1f, 8f, 2f, 3f, 0f, 22f, 0xFF8E3B46.toInt(), 0xFF6B2C35.toInt(), 0xFF50212A.toInt()))
        entities.add(BoxEntity(5f, 0f, 1f, 1f, 0f, 84f, 0xFF4A4036.toInt(), 0xFF352E27.toInt(), 0xFF2A2420.toInt()))
        entities.add(puppetEntity)

        // moonlit window geometry (world space), cut out of the darkness overlay
        for (i in intArrayOf(3, 7)) {
            val u0 = i + 0.15f
            val u1 = i + 0.85f
            val glass = Path()
            glass.moveTo(wxOf(u0, 0f), wyOf(u0, 0f, 64f))
            glass.lineTo(wxOf(u1, 0f), wyOf(u1, 0f, 64f))
            glass.lineTo(wxOf(u1, 0f), wyOf(u1, 0f, 136f))
            glass.lineTo(wxOf(u0, 0f), wyOf(u0, 0f, 136f))
            glass.close()
            windowGlass.add(glass)

            val patch = Path()
            patch.moveTo(wxOf(u0, 0f), wyOf(u0, 0f))
            patch.lineTo(wxOf(u1, 0f), wyOf(u1, 0f))
            patch.lineTo(wxOf(u1 + 1.5f, 3.2f), wyOf(u1 + 1.5f, 3.2f))
            patch.lineTo(wxOf(u0 + 1.5f, 3.2f), wyOf(u0 + 1.5f, 3.2f))
            patch.close()
            moonPatches.add(patch)
        }
        run {
            val v0 = 6.15f
            val v1 = 6.85f
            val glass = Path()
            glass.moveTo(wxOf(0f, v0), wyOf(0f, v0, 64f))
            glass.lineTo(wxOf(0f, v1), wyOf(0f, v1, 64f))
            glass.lineTo(wxOf(0f, v1), wyOf(0f, v1, 136f))
            glass.lineTo(wxOf(0f, v0), wyOf(0f, v0, 136f))
            glass.close()
            windowGlass.add(glass)

            val patch = Path()
            patch.moveTo(wxOf(0f, v0), wyOf(0f, v0))
            patch.lineTo(wxOf(0f, v1), wyOf(0f, v1))
            patch.lineTo(wxOf(3.2f, v1 + 1.5f), wyOf(3.2f, v1 + 1.5f))
            patch.lineTo(wxOf(3.2f, v0 + 1.5f), wyOf(3.2f, v0 + 1.5f))
            patch.close()
            moonPatches.add(patch)
        }
    }

    private fun buildDemoTimeline(): Timeline {
        val dur = 16000f
        val tl = Timeline(dur, 60f, true)

        val wp = arrayOf(
            floatArrayOf(4f, 2.5f), floatArrayOf(10f, 2.5f), floatArrayOf(10f, 10f),
            floatArrayOf(4f, 10f), floatArrayOf(4f, 2.5f)
        )
        val gxK = ArrayList<Keyframe>()
        val gyK = ArrayList<Keyframe>()
        for (i in wp.indices) {
            gxK.add(Keyframe(i * 4000f, wp[i][0], Easing.LINEAR))
            gyK.add(Keyframe(i * 4000f, wp[i][1], Easing.LINEAR))
        }
        tl.track("puppet.gx", gxK)
        tl.track("puppet.gy", gyK)

        val flips = floatArrayOf(1f, -1f, -1f, 1f)
        val flipK = ArrayList<Keyframe>()
        for (i in 0 until 4) flipK.add(Keyframe(i * 4000f, flips[i], Easing.HOLD))
        flipK.add(Keyframe(dur, flips[3], Easing.HOLD))
        tl.track("puppet.flip", flipK)

        val armL = ArrayList<Keyframe>()
        val armR = ArrayList<Keyframe>()
        val legL = ArrayList<Keyframe>()
        val legR = ArrayList<Keyframe>()
        val torso = ArrayList<Keyframe>()
        val head = ArrayList<Keyframe>()
        val bob = ArrayList<Keyframe>()
        val half = 250f
        val steps = (dur / half).toInt()
        for (k in 0..steps) {
            val t = k * half
            val s = if (k % 2 == 0) 1f else -1f
            armL.add(Keyframe(t, s * 32f, Easing.EASE_IN_OUT))
            armR.add(Keyframe(t, -s * 32f, Easing.EASE_IN_OUT))
            legL.add(Keyframe(t, -s * 26f, Easing.EASE_IN_OUT))
            legR.add(Keyframe(t, s * 26f, Easing.EASE_IN_OUT))
            torso.add(Keyframe(t, s * 2f, Easing.EASE_IN_OUT))
            head.add(Keyframe(t, -s * 1.5f, Easing.EASE_IN_OUT))
        }
        for (k in 0..steps * 2) {
            bob.add(Keyframe(k * half * 0.5f, if (k % 2 == 0) 0f else -3f, Easing.EASE_OUT))
        }
        tl.track("joint.arm_l.rot", armL)
        tl.track("joint.arm_r.rot", armR)
        tl.track("joint.leg_l.rot", legL)
        tl.track("joint.leg_r.rot", legR)
        tl.track("joint.torso.rot", torso)
        tl.track("joint.head.rot", head)
        tl.track("joint.root.y", bob)

        tl.track(
            "mood",
            Keyframe(0f, 0f, Easing.HOLD), Keyframe(5000f, 1f, Easing.HOLD),
            Keyframe(10000f, 2f, Easing.HOLD), Keyframe(14000f, 0f, Easing.HOLD),
            Keyframe(dur, 0f, Easing.HOLD)
        )
        tl.track(
            "cam.shake",
            Keyframe(0f, 0f, Easing.HOLD), Keyframe(5000f, 0.35f, Easing.HOLD),
            Keyframe(5120f, 0f, Easing.HOLD), Keyframe(10000f, 0.9f, Easing.HOLD),
            Keyframe(10120f, 0f, Easing.HOLD), Keyframe(dur, 0f, Easing.HOLD)
        )
        tl.track(
            "light.radius",
            Keyframe(0f, 1f, Easing.EASE_IN_OUT), Keyframe(8000f, 1.15f, Easing.EASE_IN_OUT),
            Keyframe(dur, 1f, Easing.EASE_IN_OUT)
        )
        return tl
    }

    private fun buildDemoCaptions(): List<CaptionLine> = listOf(
        CaptionLine.fromText("Midnight. The house is too quiet.", 300f, 3600f),
        CaptionLine.fromText("Something moved upstairs...", 4200f, 7600f),
        CaptionLine.fromText("RUN. Don't look back!", 8200f, 11600f),
        CaptionLine.fromText("Dawn is hours away. Stay calm.", 12200f, 15600f)
    )

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBA_8888)
        isFocusable = true
        keepScreenOn = true
        buildScene()
        player.timeline = buildDemoTimeline()
        captions = buildDemoCaptions()
        startDate = LocalDate.now()
    }
}
