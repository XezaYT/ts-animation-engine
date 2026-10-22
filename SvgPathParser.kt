package com.tsengine.app

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Result of parsing an SVG path "d" string: native Path plus every on-curve anchor point. */
class ParsedPath(val path: Path, val anchors: List<PointF>)

object SvgPathParser {

    private class Scanner(val s: String) {
        var i = 0

        fun skipSep() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++ else break
            }
        }

        fun number(): Float {
            skipSep()
            val st = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') {
                    i++
                } else if (c == '.' && !dot) {
                    dot = true
                    i++
                } else if ((c == 'e' || c == 'E') && i + 1 < s.length &&
                    (s[i + 1] in '0'..'9' || s[i + 1] == '-' || s[i + 1] == '+')
                ) {
                    i += 2
                    while (i < s.length && s[i] in '0'..'9') i++
                    break
                } else break
            }
            return s.substring(st, i).toFloat()
        }

        fun flag(): Boolean {
            skipSep()
            val c = s[i]
            i++
            return c == '1'
        }
    }

    fun parse(d: String): ParsedPath {
        val path = Path()
        val anchors = ArrayList<PointF>()
        val sc = Scanner(d)
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var lcx = 0f
        var lcy = 0f
        var cmd = ' '
        var prev = ' '
        try {
            while (true) {
                sc.skipSep()
                if (sc.i >= d.length) break
                val ch = d[sc.i]
                if (ch.isLetter()) {
                    cmd = ch
                    sc.i++
                    if (cmd == 'z' || cmd == 'Z') {
                        path.close()
                        cx = sx
                        cy = sy
                        prev = 'Z'
                        continue
                    }
                } else if (cmd == ' ' || cmd == 'z' || cmd == 'Z') {
                    break
                }
                val rel = cmd.isLowerCase()
                val ox = if (rel) cx else 0f
                val oy = if (rel) cy else 0f
                when (cmd.uppercaseChar()) {
                    'M' -> {
                        val x = sc.number() + ox
                        val y = sc.number() + oy
                        path.moveTo(x, y)
                        cx = x; cy = y; sx = x; sy = y
                        anchors.add(PointF(x, y))
                        cmd = if (rel) 'l' else 'L'
                        prev = 'M'
                    }
                    'L' -> {
                        val x = sc.number() + ox
                        val y = sc.number() + oy
                        path.lineTo(x, y)
                        cx = x; cy = y
                        anchors.add(PointF(x, y))
                        prev = 'L'
                    }
                    'H' -> {
                        val x = sc.number() + ox
                        path.lineTo(x, cy)
                        cx = x
                        anchors.add(PointF(cx, cy))
                        prev = 'H'
                    }
                    'V' -> {
                        val y = sc.number() + oy
                        path.lineTo(cx, y)
                        cy = y
                        anchors.add(PointF(cx, cy))
                        prev = 'V'
                    }
                    'C' -> {
                        val x1 = sc.number() + ox
                        val y1 = sc.number() + oy
                        val x2 = sc.number() + ox
                        val y2 = sc.number() + oy
                        val x = sc.number() + ox
                        val y = sc.number() + oy
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        lcx = x2; lcy = y2
                        cx = x; cy = y
                        anchors.add(PointF(x, y))
                        prev = 'C'
                    }
                    'S' -> {
                        val x2 = sc.number() + ox
                        val y2 = sc.number() + oy
                        val x = sc.number() + ox
                        val y = sc.number() + oy
                        val x1 = if (prev == 'C' || prev == 'S') 2f * cx - lcx else cx
                        val y1 = if (prev == 'C' || prev == 'S') 2f * cy - lcy else cy
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        lcx = x2; lcy = y2
                        cx = x; cy = y
                        anchors.add(PointF(x, y))
                        prev = 'S'
                    }
                    'Q' -> {
                        val x1 = sc.number() + ox
                        val y1 = sc.number() + oy
                        val x = sc.number() + ox
                        val y = sc.number() + oy
                        path.quadTo(x1, y1, x, y)
                        lcx = x1; lcy = y1
                        cx = x; cy = y
                        anchors.add(PointF(x, y))
                        prev = 'Q'
                    }
                    'T' -> {
                        val x = sc.number() + ox
                        val y = sc.number() + oy
                        val x1 = if (prev == 'Q' || prev == 'T') 2f * cx - lcx else cx
                        val y1 = if (prev == 'Q' || prev == 'T') 2f * cy - lcy else cy
                        path.quadTo(x1, y1, x, y)
                        lcx = x1; lcy = y1
                        cx = x; cy = y
                        anchors.add(PointF(x, y))
                        prev = 'T'
                    }
                    'A' -> {
                        val rx = sc.number()
                        val ry = sc.number()
                        val rot = sc.number()
                        val large = sc.flag()
                        val sweep = sc.flag()
                        val x = sc.number() + ox
                        val y = sc.number() + oy
                        arcTo(path, cx, cy, rx, ry, rot, large, sweep, x, y)
                        cx = x; cy = y
                        anchors.add(PointF(x, y))
                        prev = 'A'
                    }
                    else -> break
                }
            }
        } catch (_: Exception) {
            // Malformed trailing data: keep everything parsed so far.
        }
        return ParsedPath(path, anchors)
    }

    /** SVG elliptical arc (endpoint parameterisation) converted to cubic Beziers. */
    private fun arcTo(
        p: Path, x0: Float, y0: Float, rxIn: Float, ryIn: Float, angle: Float,
        large: Boolean, sweep: Boolean, x: Float, y: Float
    ) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble())
        var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) {
            p.lineTo(x, y)
            return
        }
        val phi = Math.toRadians(angle.toDouble())
        val cosP = cos(phi)
        val sinP = sin(phi)
        val dx2 = (x0 - x) / 2.0
        val dy2 = (y0 - y) / 2.0
        val x1p = cosP * dx2 + sinP * dy2
        val y1p = -sinP * dx2 + cosP * dy2
        val lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lam > 1.0) {
            val s = sqrt(lam)
            rx *= s
            ry *= s
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var co = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
        if (large == sweep) co = -co
        val cxp = co * rx * y1p / ry
        val cyp = -co * ry * x1p / rx
        val cxc = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
        val cyc = sinP * cxp + cosP * cyp + (y0 + y) / 2.0
        val th1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var dth = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - th1
        if (sweep && dth < 0) dth += 2.0 * PI else if (!sweep && dth > 0) dth -= 2.0 * PI
        val segs = max(1, ceil(abs(dth) / (PI / 2.0) - 1e-9).toInt())
        val delta = dth / segs
        val t = 4.0 / 3.0 * tan(delta / 4.0)
        var a = th1
        for (k in 0 until segs) {
            val cosA = cos(a)
            val sinA = sin(a)
            val cosB = cos(a + delta)
            val sinB = sin(a + delta)
            val e1x = cosA - t * sinA
            val e1y = sinA + t * cosA
            val e2x = cosB + t * sinB
            val e2y = sinB - t * cosB
            val c1x = (cosP * rx * e1x - sinP * ry * e1y + cxc).toFloat()
            val c1y = (sinP * rx * e1x + cosP * ry * e1y + cyc).toFloat()
            val c2x = (cosP * rx * e2x - sinP * ry * e2y + cxc).toFloat()
            val c2y = (sinP * rx * e2x + cosP * ry * e2y + cyc).toFloat()
            val ex = if (k == segs - 1) x else (cosP * rx * cosB - sinP * ry * sinB + cxc).toFloat()
            val ey = if (k == segs - 1) y else (sinP * rx * cosB + cosP * ry * sinB + cyc).toFloat()
            p.cubicTo(c1x, c1y, c2x, c2y, ex, ey)
            a += delta
        }
    }

    private val NUMBER_RE = Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")
    private val TRANSFORM_RE = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")

    fun parseNumbers(s: String): FloatArray {
        val out = ArrayList<Float>()
        for (m in NUMBER_RE.findAll(s)) {
            val f = m.value.toFloatOrNull()
            if (f != null) out.add(f)
        }
        return out.toFloatArray()
    }

    fun parseLength(s: String): Float = parseNumbers(s).firstOrNull() ?: 0f

    /** "50%" -> 0.5, "0.25" -> 0.25. */
    fun parseFraction(s: String): Float {
        val t = s.trim()
        return if (t.endsWith("%")) (t.dropLast(1).toFloatOrNull() ?: 0f) / 100f else t.toFloatOrNull() ?: 0f
    }

    fun parseTransform(s: String): Matrix {
        val result = Matrix()
        for (m in TRANSFORM_RE.findAll(s)) {
            val args = parseNumbers(m.groupValues[2])
            val t = Matrix()
            when (m.groupValues[1]) {
                "matrix" -> if (args.size >= 6) {
                    t.setValues(floatArrayOf(args[0], args[2], args[4], args[1], args[3], args[5], 0f, 0f, 1f))
                }
                "translate" -> if (args.isNotEmpty()) t.setTranslate(args[0], args.getOrElse(1) { 0f })
                "scale" -> if (args.isNotEmpty()) t.setScale(args[0], args.getOrElse(1) { args[0] })
                "rotate" -> if (args.isNotEmpty()) {
                    if (args.size >= 3) t.setRotate(args[0], args[1], args[2]) else t.setRotate(args[0])
                }
                "skewX" -> if (args.isNotEmpty()) t.setSkew(tan(Math.toRadians(args[0].toDouble())).toFloat(), 0f)
                "skewY" -> if (args.isNotEmpty()) t.setSkew(0f, tan(Math.toRadians(args[0].toDouble())).toFloat())
            }
            result.preConcat(t)
        }
        return result
    }

    private val NAMED = mapOf(
        "black" to Color.BLACK, "white" to Color.WHITE, "red" to Color.RED,
        "green" to 0xFF008000.toInt(), "lime" to 0xFF00FF00.toInt(), "blue" to Color.BLUE,
        "yellow" to Color.YELLOW, "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
        "orange" to 0xFFFFA500.toInt(), "purple" to 0xFF800080.toInt(), "pink" to 0xFFFFC0CB.toInt(),
        "cyan" to Color.CYAN, "magenta" to Color.MAGENTA, "brown" to 0xFFA52A2A.toInt(),
        "silver" to 0xFFC0C0C0.toInt(), "navy" to 0xFF000080.toInt(), "teal" to 0xFF008080.toInt(),
        "gold" to 0xFFFFD700.toInt()
    )

    private fun hex(h: String): Int? {
        return try {
            when (h.length) {
                3 -> Color.rgb(h.substring(0, 1).toInt(16) * 17, h.substring(1, 2).toInt(16) * 17, h.substring(2, 3).toInt(16) * 17)
                4 -> Color.argb(
                    h.substring(3, 4).toInt(16) * 17, h.substring(0, 1).toInt(16) * 17,
                    h.substring(1, 2).toInt(16) * 17, h.substring(2, 3).toInt(16) * 17
                )
                6 -> Color.rgb(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                8 -> Color.argb(
                    h.substring(6, 8).toInt(16), h.substring(0, 2).toInt(16),
                    h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16)
                )
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Returns ARGB, or null for "none"/unparseable. Resolves var(--x) and token(x) through [tokens]. */
    fun parseColor(value: String, tokens: DesignTokens?): Int? {
        val v = value.trim()
        if (v.isEmpty() || v.equals("none", ignoreCase = true)) return null
        if (v.startsWith("#")) return hex(v.substring(1))
        val lower = v.lowercase()
        if (lower.startsWith("rgb")) {
            val n = parseNumbers(v.substringAfter('(').substringBefore(')'))
            if (n.size < 3) return null
            val pct = v.contains('%')
            val k = if (pct) 2.55f else 1f
            val a = if (n.size >= 4) n[3] else 1f
            return Color.argb(
                (a * 255f).roundToInt().coerceIn(0, 255),
                (n[0] * k).roundToInt().coerceIn(0, 255),
                (n[1] * k).roundToInt().coerceIn(0, 255),
                (n[2] * k).roundToInt().coerceIn(0, 255)
            )
        }
        if (lower.startsWith("var(") || lower.startsWith("token(")) {
            val inner = v.substringAfter('(').substringBeforeLast(')')
            val name = inner.substringBefore(',').trim().removePrefix("--")
            val tokenColor = tokens?.color(name)
            if (tokenColor != null) return tokenColor
            val fallback = inner.substringAfter(',', "").trim()
            return if (fallback.isEmpty()) null else parseColor(fallback, null)
        }
        if (lower == "currentcolor") return Color.BLACK
        if (lower == "transparent") return Color.TRANSPARENT
        return NAMED[lower]
    }
}
