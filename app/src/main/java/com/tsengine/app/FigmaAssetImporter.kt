package com.tsengine.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Design tokens (colors, stroke widths) harvested from Figma variable / token JSON exports.
// ---------------------------------------------------------------------------------------------

class DesignTokens {
    val colors = LinkedHashMap<String, Int>()
    val strokeWidths = LinkedHashMap<String, Float>()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun color(name: String): Int? {
        val n = norm(name)
        if (n.isEmpty()) return null
        colors.entries.firstOrNull { norm(it.key) == n }?.let { return it.value }
        return colors.entries.firstOrNull { norm(it.key).endsWith(n) }?.value
    }

    fun strokeWidth(name: String): Float? {
        val n = norm(name)
        if (n.isEmpty()) return null
        strokeWidths.entries.firstOrNull { norm(it.key) == n }?.let { return it.value }
        return strokeWidths.entries.firstOrNull { norm(it.key).endsWith(n) }?.value
    }

    /** Accepts W3C design-token JSON, Figma variable exports, or plain {"colors":{...},"strokeWidths":{...}}. */
    fun mergeJson(json: String) {
        walk("", JSONTokener(json).nextValue())
    }

    private fun walk(path: String, v: Any?) {
        when (v) {
            is JSONObject -> {
                val rgb = jsonRgba(v)
                if (rgb != null) {
                    colors[path] = rgb
                    return
                }
                val inner: Any? = if (v.has("\$value")) v.opt("\$value") else if (v.has("value") && v.length() <= 6) v.opt("value") else null
                if (inner != null) {
                    walk(path, inner)
                    return
                }
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    walk(if (path.isEmpty()) k else "$path/$k", v.opt(k))
                }
            }
            is JSONArray -> for (i in 0 until v.length()) walk("$path/$i", v.opt(i))
            is String -> {
                val c = SvgPathParser.parseColor(v, null)
                if (c != null) colors[path] = c else v.removeSuffix("px").trim().toFloatOrNull()?.let { putWidth(path, it) }
            }
            is Number -> putWidth(path, v.toFloat())
        }
    }

    private fun putWidth(path: String, w: Float) {
        val p = path.lowercase()
        if (p.contains("stroke") || p.contains("border") || p.contains("width") || p.contains("weight")) strokeWidths[path] = w
    }
}

/** {r,g,b[,a]} with 0..1 floats (Figma REST) or 0..255 ints -> ARGB, or null when not a color object. */
internal fun jsonRgba(o: JSONObject?): Int? {
    if (o == null || !(o.has("r") && o.has("g") && o.has("b"))) return null
    val r = o.optDouble("r", 0.0)
    val g = o.optDouble("g", 0.0)
    val b = o.optDouble("b", 0.0)
    val a = o.optDouble("a", 1.0)
    val k = if (max(r, max(g, b)) <= 1.0) 255.0 else 1.0
    val alpha = if (a <= 1.0) a * 255.0 else a
    return Color.argb(
        alpha.roundToInt().coerceIn(0, 255),
        (r * k).roundToInt().coerceIn(0, 255),
        (g * k).roundToInt().coerceIn(0, 255),
        (b * k).roundToInt().coerceIn(0, 255)
    )
}

// ---------------------------------------------------------------------------------------------
// Vector model: resolution independent Paths + Paints, drawn straight to the Canvas (always crisp).
// ---------------------------------------------------------------------------------------------

class VectorShape(
    val id: String,
    val name: String,
    val order: Int,
    val path: Path,
    val anchors: List<PointF>,
    val fill: Paint?,
    val stroke: Paint?,
    val isMarker: Boolean
) {
    val bounds = RectF().also { path.computeBounds(it, true) }

    fun draw(c: Canvas) {
        if (isMarker) return
        if (fill != null) c.drawPath(path, fill)
        if (stroke != null) c.drawPath(path, stroke)
    }
}

class VectorNode(val id: String, val name: String, val isGroup: Boolean) {
    val children = ArrayList<VectorNode>()
    var shape: VectorShape? = null
    var pivot: PointF? = null
}

class VectorAsset(val viewBox: RectF, val root: VectorNode, val tokens: DesignTokens) {

    val shapes: List<VectorShape> by lazy {
        val out = ArrayList<VectorShape>()
        fun walk(n: VectorNode) {
            n.shape?.let { out.add(it) }
            for (c in n.children) walk(c)
        }
        walk(root)
        out.sortBy { it.order }
        out
    }

    /** Draws the asset scaled to [width] with its bottom-center sitting on (anchorX, anchorY). */
    fun drawAnchored(canvas: Canvas, anchorX: Float, anchorY: Float, width: Float) {
        val s = width / viewBox.width()
        canvas.save()
        canvas.translate(anchorX - viewBox.centerX() * s, anchorY - viewBox.bottom * s)
        canvas.scale(s, s)
        for (sh in shapes) sh.draw(canvas)
        canvas.restore()
    }

    /** Draws the asset aspect-fitted and centered inside [dst]. */
    fun drawFit(canvas: Canvas, dst: RectF) {
        val s = min(dst.width() / viewBox.width(), dst.height() / viewBox.height())
        canvas.save()
        canvas.translate(dst.centerX() - viewBox.centerX() * s, dst.centerY() - viewBox.centerY() * s)
        canvas.scale(s, s)
        for (sh in shapes) sh.draw(canvas)
        canvas.restore()
    }

    fun createPuppet(): Puppet = PuppetBuilder(viewBox).build(root)
}

// ---------------------------------------------------------------------------------------------
// Puppet rig: hierarchical joints (Root > Torso > Head/Arms, Root > Legs) driven by forward kinematics.
// ---------------------------------------------------------------------------------------------

enum class JointRole(val key: String) {
    ROOT("root"), TORSO("torso"), HEAD("head"),
    ARM_L("arm_l"), ARM_R("arm_r"), FOREARM_L("forearm_l"), FOREARM_R("forearm_r"),
    LEG_L("leg_l"), LEG_R("leg_r"), SHIN_L("shin_l"), SHIN_R("shin_r"), OTHER("other");

    val isLimb: Boolean get() = this == ARM_L || this == ARM_R || this == LEG_L || this == LEG_R
    val isMain: Boolean get() = this == TORSO || this == HEAD || isLimb
}

class PuppetJoint(val key: String, val name: String, val role: JointRole) {
    var parent: PuppetJoint? = null
    val children = ArrayList<PuppetJoint>()
    val shapes = ArrayList<VectorShape>()
    var pivotOverride: PointF? = null
    var pivotX = 0f
    var pivotY = 0f
    val bindBounds = RectF()

    var rotation = 0f
    var offsetX = 0f
    var offsetY = 0f
    var scaleX = 1f
    var scaleY = 1f

    val local = Matrix()
    val world = Matrix()

    val zIndex: Int get() = shapes.minOfOrNull { it.order } ?: Int.MAX_VALUE
}

class Puppet(val joints: List<PuppetJoint>, val root: PuppetJoint, val viewBox: RectF) {
    private val byKey = HashMap<String, PuppetJoint>()
    private val drawOrder: List<PuppetJoint>

    init {
        for (j in joints) byKey[j.key] = j
        drawOrder = joints.filter { it.shapes.isNotEmpty() }.sortedBy { it.zIndex }
        updateTransforms()
    }

    fun joint(key: String): PuppetJoint? = byKey[key.lowercase()]

    val jointKeys: List<String> get() = joints.map { it.key }

    /** Path form: "<joint>.rot|x|y|sx|sy", e.g. "arm_l.rot". */
    fun setValue(path: String, v: Float): Boolean {
        val dot = path.lastIndexOf('.')
        if (dot <= 0) return false
        val j = byKey[path.substring(0, dot)] ?: return false
        when (path.substring(dot + 1)) {
            "rot" -> j.rotation = v
            "x" -> j.offsetX = v
            "y" -> j.offsetY = v
            "sx" -> j.scaleX = v
            "sy" -> j.scaleY = v
            else -> return false
        }
        return true
    }

    fun resetPose() {
        for (j in joints) {
            j.rotation = 0f
            j.offsetX = 0f
            j.offsetY = 0f
            j.scaleX = 1f
            j.scaleY = 1f
        }
    }

    /** Forward kinematics. [joints] is parent-first so one linear pass is enough. */
    fun updateTransforms() {
        for (j in joints) {
            val l = j.local
            l.reset()
            if (j.scaleX != 1f || j.scaleY != 1f) l.postScale(j.scaleX, j.scaleY, j.pivotX, j.pivotY)
            if (j.rotation != 0f) l.postRotate(j.rotation, j.pivotX, j.pivotY)
            if (j.offsetX != 0f || j.offsetY != 0f) l.postTranslate(j.offsetX, j.offsetY)
            val p = j.parent
            if (p == null) j.world.set(l) else j.world.setConcat(p.world, l)
        }
    }

    fun draw(canvas: Canvas) {
        for (j in drawOrder) {
            canvas.save()
            canvas.concat(j.world)
            for (s in j.shapes) s.draw(canvas)
            canvas.restore()
        }
    }
}

private val LEFT_RE = Regex("(?i)(^|[^a-z])(l|left)([^a-z]|$)")
private val RIGHT_RE = Regex("(?i)(^|[^a-z])(r|right)([^a-z]|$)")
private val LEFT_CAMEL = Regex("[a-z]L($|[^a-z])")
private val RIGHT_CAMEL = Regex("[a-z]R($|[^a-z])")

internal fun classifyJointName(raw: String): JointRole? {
    val n = raw.lowercase()
    if (n.isBlank()) return null
    val left = LEFT_RE.containsMatchIn(raw) || LEFT_CAMEL.containsMatchIn(raw)
    val right = (RIGHT_RE.containsMatchIn(raw) || RIGHT_CAMEL.containsMatchIn(raw)) && !left
    return when {
        n.contains("forearm") || n.contains("lowerarm") || n.contains("hand") ->
            if (right) JointRole.FOREARM_R else JointRole.FOREARM_L
        n.contains("shin") || n.contains("calf") || n.contains("lowerleg") || n.contains("foot") || n.contains("feet") ->
            if (right) JointRole.SHIN_R else JointRole.SHIN_L
        n.contains("arm") || n.contains("shoulder") -> if (right) JointRole.ARM_R else JointRole.ARM_L
        n.contains("leg") || n.contains("thigh") -> if (right) JointRole.LEG_R else JointRole.LEG_L
        n.contains("head") || n.contains("face") || n.contains("hair") || n.contains("neck") -> JointRole.HEAD
        n.contains("torso") || n.contains("body") || n.contains("chest") || n.contains("spine") ||
            n.contains("pelvis") || n.contains("hip") -> JointRole.TORSO
        else -> null
    }
}

internal class PuppetBuilder(private val viewBox: RectF) {
    private val all = ArrayList<PuppetJoint>()
    private val keys = HashSet<String>()

    fun build(tree: VectorNode): Puppet {
        val root = newJoint(JointRole.ROOT, "Root")
        visit(tree, root)

        val torso = all.firstOrNull { it.role == JointRole.TORSO }
        for (j in all) {
            if (j === root || j.parent != null) continue
            j.parent = when (j.role) {
                JointRole.HEAD, JointRole.ARM_L, JointRole.ARM_R -> torso ?: root
                else -> root
            }
        }

        val ordered = ArrayList<PuppetJoint>()
        order(root, ordered)
        computePivots(ordered, root)
        return Puppet(ordered, root, viewBox)
    }

    private fun order(j: PuppetJoint, out: MutableList<PuppetJoint>) {
        out.add(j)
        j.children.clear()
        for (c in all) if (c.parent === j) j.children.add(c)
        for (c in j.children) order(c, out)
    }

    private fun newJoint(role: JointRole, name: String): PuppetJoint {
        val base = if (role == JointRole.OTHER) {
            name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "part" }
        } else role.key
        var key = base
        var n = 2
        while (!keys.add(key)) {
            key = "${base}_$n"
            n++
        }
        val j = PuppetJoint(key, name, role)
        all.add(j)
        return j
    }

    private fun containsMain(node: VectorNode): Boolean {
        for (c in node.children) {
            if (!c.isGroup) continue
            val r = classifyJointName(c.name.ifEmpty { c.id })
            if (r != null && r.isMain) return true
            if (containsMain(c)) return true
        }
        return false
    }

    private fun addShape(j: PuppetJoint, s: VectorShape) {
        if (s.isMarker) {
            j.pivotOverride = PointF(s.bounds.centerX(), s.bounds.centerY())
        } else {
            j.shapes.add(s)
        }
    }

    private fun visit(node: VectorNode, root: PuppetJoint) {
        for (c in node.children) {
            if (!c.isGroup) {
                c.shape?.let { addShape(root, it) }
                continue
            }
            val label = c.name.ifEmpty { c.id }
            val role = classifyJointName(label)
            if (role != null && role.isMain) {
                val j = newJoint(role, label)
                c.pivot?.let { j.pivotOverride = it }
                collect(c, j)
            } else if (containsMain(c)) {
                visit(c, root)
            } else {
                val j = newJoint(JointRole.OTHER, label.ifEmpty { "part" })
                c.pivot?.let { j.pivotOverride = it }
                collect(c, j)
            }
        }
    }

    private fun isSubJointOf(parent: JointRole, r: JointRole): Boolean {
        val arm = parent == JointRole.ARM_L || parent == JointRole.ARM_R
        val leg = parent == JointRole.LEG_L || parent == JointRole.LEG_R
        val fore = r == JointRole.FOREARM_L || r == JointRole.FOREARM_R
        val shin = r == JointRole.SHIN_L || r == JointRole.SHIN_R
        return (arm && fore) || (leg && shin)
    }

    private fun collect(group: VectorNode, joint: PuppetJoint) {
        for (c in group.children) {
            if (c.isGroup) {
                val label = c.name.ifEmpty { c.id }
                val r = classifyJointName(label)
                if (r != null && isSubJointOf(joint.role, r)) {
                    val sj = newJoint(r, label)
                    sj.parent = joint
                    c.pivot?.let { sj.pivotOverride = it }
                    collect(c, sj)
                } else {
                    collect(c, joint)
                }
            } else {
                c.shape?.let { addShape(joint, it) }
            }
        }
    }

    private fun computePivots(ordered: List<PuppetJoint>, root: PuppetJoint) {
        val own = HashMap<PuppetJoint, RectF>()
        val whole = RectF()
        var hasWhole = false
        for (j in ordered.asReversed()) {
            var r: RectF? = null
            for (s in j.shapes) {
                val cur = r
                if (cur == null) r = RectF(s.bounds) else cur.union(s.bounds)
            }
            if (r == null) {
                for (c in j.children) {
                    val cb = own[c] ?: continue
                    val cur = r
                    if (cur == null) r = RectF(cb) else cur.union(cb)
                }
            }
            val rect = r ?: RectF(viewBox)
            own[j] = rect
            j.bindBounds.set(rect)
            if (j.shapes.isNotEmpty()) {
                if (!hasWhole) {
                    whole.set(rect)
                    hasWhole = true
                } else whole.union(rect)
            }
        }
        if (!hasWhole) whole.set(viewBox)

        for (j in ordered) {
            val ov = j.pivotOverride
            if (ov != null) {
                j.pivotX = ov.x
                j.pivotY = ov.y
                continue
            }
            val b = own[j] ?: whole
            when (j.role) {
                JointRole.ROOT -> { j.pivotX = whole.centerX(); j.pivotY = whole.bottom }
                JointRole.TORSO, JointRole.HEAD -> { j.pivotX = b.centerX(); j.pivotY = b.bottom }
                JointRole.ARM_L, JointRole.ARM_R -> { j.pivotX = b.centerX(); j.pivotY = b.top + b.height() * 0.06f }
                JointRole.LEG_L, JointRole.LEG_R -> { j.pivotX = b.centerX(); j.pivotY = b.top + b.height() * 0.04f }
                JointRole.FOREARM_L, JointRole.FOREARM_R, JointRole.SHIN_L, JointRole.SHIN_R -> {
                    j.pivotX = b.centerX(); j.pivotY = b.top
                }
                JointRole.OTHER -> { j.pivotX = b.centerX(); j.pivotY = b.centerY() }
            }
        }
        root.bindBounds.set(whole)
    }
}

// ---------------------------------------------------------------------------------------------
// Importer: raw Figma SVG export, or Figma REST JSON node trees, plus design tokens.
// ---------------------------------------------------------------------------------------------

internal class El(val tag: String, val attrs: HashMap<String, String>) {
    val children = ArrayList<El>()
}

internal data class Style(
    val fill: String = "#000000",
    val stroke: String = "none",
    val strokeWidth: Float = 1f,
    val fillOpacity: Float = 1f,
    val strokeOpacity: Float = 1f,
    val opacity: Float = 1f,
    val cap: String = "butt",
    val join: String = "miter",
    val miter: Float = 4f,
    val fillRule: String = "nonzero",
    val dash: String = "none"
) {
    fun inherit(a: Map<String, String>): Style = copy(
        fill = a["fill"] ?: fill,
        stroke = a["stroke"] ?: stroke,
        strokeWidth = a["stroke-width"]?.let { SvgPathParser.parseLength(it) } ?: strokeWidth,
        fillOpacity = a["fill-opacity"]?.toFloatOrNull() ?: fillOpacity,
        strokeOpacity = a["stroke-opacity"]?.toFloatOrNull() ?: strokeOpacity,
        opacity = opacity * (a["opacity"]?.toFloatOrNull() ?: 1f),
        cap = a["stroke-linecap"] ?: cap,
        join = a["stroke-linejoin"] ?: join,
        miter = a["stroke-miterlimit"]?.toFloatOrNull() ?: miter,
        fillRule = a["fill-rule"] ?: fillRule,
        dash = a["stroke-dasharray"] ?: dash
    )
}

private val FIGMA_GROUP_TYPES = setOf(
    "DOCUMENT", "CANVAS", "FRAME", "GROUP", "COMPONENT", "COMPONENT_SET", "INSTANCE", "SECTION"
)

class FigmaAssetImporter(val tokens: DesignTokens = DesignTokens()) {

    private var orderCounter = 0

    fun loadTokens(json: String) {
        tokens.mergeJson(json)
    }

    // ------------------------------------------------------------------ SVG

    fun importSvg(svg: String): VectorAsset = importSvg(svg.byteInputStream(Charsets.UTF_8))

    fun importSvg(input: InputStream): VectorAsset {
        val dom = parseDom(input)
        orderCounter = 0

        val defs = HashMap<String, El>()
        fun collectDefs(e: El) {
            if (e.tag == "linearGradient" || e.tag == "radialGradient") {
                e.attrs["id"]?.let { defs[it] = e }
            }
            for (c in e.children) collectDefs(c)
        }
        collectDefs(dom)

        var vb: RectF? = null
        val vbNums = dom.attrs["viewBox"]?.let { SvgPathParser.parseNumbers(it) }
        if (vbNums != null && vbNums.size >= 4 && vbNums[2] > 0f && vbNums[3] > 0f) {
            vb = RectF(vbNums[0], vbNums[1], vbNums[0] + vbNums[2], vbNums[1] + vbNums[3])
        } else {
            val w = dom.attrs["width"]?.let { SvgPathParser.parseLength(it) } ?: 0f
            val h = dom.attrs["height"]?.let { SvgPathParser.parseLength(it) } ?: 0f
            if (w > 0f && h > 0f) vb = RectF(0f, 0f, w, h)
        }

        val builder = SvgBuilder(defs, vb?.width() ?: 100f)
        val rootNode = builder.node(dom, Matrix(), Style()) ?: VectorNode("", "", true)
        val asset0 = VectorAsset(vb ?: RectF(0f, 0f, 1f, 1f), rootNode, tokens)
        if (vb != null) return asset0

        val union = RectF()
        var first = true
        for (s in asset0.shapes) {
            if (first) {
                union.set(s.bounds)
                first = false
            } else union.union(s.bounds)
        }
        if (first) union.set(0f, 0f, 1f, 1f)
        return VectorAsset(union, rootNode, tokens)
    }

    private fun parseDom(input: InputStream): El {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        val stack = ArrayList<El>()
        var rootEl: El? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val attrs = HashMap<String, String>()
                for (i in 0 until p.attributeCount) {
                    attrs[p.getAttributeName(i).substringAfter(':')] = p.getAttributeValue(i)
                }
                val style = attrs["style"]
                if (style != null) {
                    for (decl in style.split(';')) {
                        val k = decl.substringBefore(':', "").trim()
                        val v = decl.substringAfter(':', "").trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) attrs[k] = v
                    }
                }
                val el = El(p.name.substringAfter(':'), attrs)
                if (stack.isNotEmpty()) stack[stack.size - 1].children.add(el)
                if (rootEl == null) rootEl = el
                stack.add(el)
            } else if (ev == XmlPullParser.END_TAG) {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            ev = p.next()
        }
        return rootEl ?: throw IllegalArgumentException("Empty SVG document")
    }

    private fun Matrix.meanScale(): Float {
        val v = FloatArray(9)
        getValues(v)
        val det = v[0] * v[4] - v[1] * v[3]
        val s = sqrt(abs(det))
        return if (s == 0f) 1f else s
    }

    private inner class SvgBuilder(val defs: Map<String, El>, val vbW: Float) {

        fun node(el: El, m: Matrix, parent: Style): VectorNode? {
            val a = el.attrs
            if (a["display"] == "none" || a["visibility"] == "hidden") return null
            val style = parent.inherit(a)
            val m2 = Matrix(m)
            a["transform"]?.let { m2.preConcat(SvgPathParser.parseTransform(it)) }
            val id = a["id"] ?: ""
            val name = a["data-name"] ?: a["name"] ?: id
            return when (el.tag) {
                "svg", "g", "a", "switch" -> {
                    val n = VectorNode(id, name, true)
                    a["data-pivot"]?.let {
                        val v = SvgPathParser.parseNumbers(it)
                        if (v.size >= 2) {
                            val pts = floatArrayOf(v[0], v[1])
                            m2.mapPoints(pts)
                            n.pivot = PointF(pts[0], pts[1])
                        }
                    }
                    for (c in el.children) node(c, m2, style)?.let { n.children.add(it) }
                    n
                }
                "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> shapeNode(el, m2, style, id, name)
                else -> null
            }
        }

        private fun shapeNode(el: El, m: Matrix, st: Style, id: String, name: String): VectorNode? {
            val a = el.attrs
            val raw = Path()
            val anchors = ArrayList<PointF>()
            fun num(k: String, d: Float = 0f): Float = a[k]?.let { SvgPathParser.parseLength(it) } ?: d

            when (el.tag) {
                "path" -> {
                    val d = a["d"] ?: return null
                    val pp = SvgPathParser.parse(d)
                    raw.addPath(pp.path)
                    anchors.addAll(pp.anchors)
                }
                "rect" -> {
                    val x = num("x")
                    val y = num("y")
                    val w = num("width")
                    val h = num("height")
                    if (w <= 0f || h <= 0f) return null
                    var rx = if (a.containsKey("rx")) num("rx") else num("ry")
                    var ry = if (a.containsKey("ry")) num("ry") else rx
                    rx = min(rx, w / 2f)
                    ry = min(ry, h / 2f)
                    raw.addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW)
                    anchors.add(PointF(x, y)); anchors.add(PointF(x + w, y))
                    anchors.add(PointF(x + w, y + h)); anchors.add(PointF(x, y + h))
                }
                "circle" -> {
                    val r = num("r")
                    if (r <= 0f) return null
                    raw.addCircle(num("cx"), num("cy"), r, Path.Direction.CW)
                    anchors.add(PointF(num("cx"), num("cy")))
                }
                "ellipse" -> {
                    val rx = num("rx")
                    val ry = num("ry")
                    if (rx <= 0f || ry <= 0f) return null
                    val cx = num("cx")
                    val cy = num("cy")
                    raw.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
                    anchors.add(PointF(cx, cy))
                }
                "line" -> {
                    raw.moveTo(num("x1"), num("y1"))
                    raw.lineTo(num("x2"), num("y2"))
                    anchors.add(PointF(num("x1"), num("y1"))); anchors.add(PointF(num("x2"), num("y2")))
                }
                "polyline", "polygon" -> {
                    val pts = SvgPathParser.parseNumbers(a["points"] ?: return null)
                    if (pts.size < 4) return null
                    var i = 0
                    while (i + 1 < pts.size) {
                        if (i == 0) raw.moveTo(pts[0], pts[1]) else raw.lineTo(pts[i], pts[i + 1])
                        anchors.add(PointF(pts[i], pts[i + 1]))
                        i += 2
                    }
                    if (el.tag == "polygon") raw.close()
                }
                else -> return null
            }
            if (raw.isEmpty) return null

            val bounds = RectF()
            raw.computeBounds(bounds, true)
            val path = Path()
            raw.transform(m, path)
            path.fillType = if (st.fillRule == "evenodd") Path.FillType.EVEN_ODD else Path.FillType.WINDING

            val ap = FloatArray(anchors.size * 2)
            for ((i, p) in anchors.withIndex()) {
                ap[i * 2] = p.x
                ap[i * 2 + 1] = p.y
            }
            m.mapPoints(ap)
            val worldAnchors = ArrayList<PointF>(anchors.size)
            for (i in anchors.indices) worldAnchors.add(PointF(ap[i * 2], ap[i * 2 + 1]))

            val fill = makePaint(st.fill, st.fillOpacity * st.opacity, bounds, m, true, st)
            val stroke = if (st.stroke.trim() != "none" && st.strokeWidth > 0f) {
                makePaint(st.stroke, st.strokeOpacity * st.opacity, bounds, m, false, st)
            } else null

            val lower = name.lowercase()
            val marker = lower.contains("pivot") || lower.contains("anchor")
            if (fill == null && stroke == null && !marker) return null

            val node = VectorNode(id, name, false)
            node.shape = VectorShape(id, name, orderCounter++, path, worldAnchors, fill, stroke, marker)
            return node
        }

        private fun makePaint(value: String, alpha: Float, bounds: RectF, m: Matrix, isFill: Boolean, st: Style): Paint? {
            val v = value.trim()
            if (v.isEmpty() || v == "none") return null
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = if (isFill) Paint.Style.FILL else Paint.Style.STROKE
            val al = alpha.coerceIn(0f, 1f)
            if (v.startsWith("url(")) {
                val ref = v.substringAfter('#').substringBefore(')').trim().trim('\'', '"')
                val g = defs[ref] ?: return null
                val sh = gradientShader(g, bounds, m) ?: return null
                p.shader = sh
                p.alpha = (255f * al).roundToInt()
            } else {
                val c = SvgPathParser.parseColor(v, tokens) ?: return null
                val ca = Color.alpha(c) / 255f
                p.color = (c and 0x00FFFFFF) or ((255f * ca * al).roundToInt().coerceIn(0, 255) shl 24)
            }
            if (!isFill) {
                val sc = m.meanScale()
                p.strokeWidth = st.strokeWidth * sc
                p.strokeCap = when (st.cap) {
                    "round" -> Paint.Cap.ROUND
                    "square" -> Paint.Cap.SQUARE
                    else -> Paint.Cap.BUTT
                }
                p.strokeJoin = when (st.join) {
                    "round" -> Paint.Join.ROUND
                    "bevel" -> Paint.Join.BEVEL
                    else -> Paint.Join.MITER
                }
                p.strokeMiter = st.miter
                if (st.dash != "none") {
                    val d = SvgPathParser.parseNumbers(st.dash)
                    if (d.isNotEmpty() && d.any { it > 0f }) {
                        val arr = if (d.size % 2 == 1) d + d else d
                        for (i in arr.indices) arr[i] = arr[i] * sc
                        p.pathEffect = DashPathEffect(arr, 0f)
                    }
                }
            }
            return p
        }

        private fun gradientStops(g: El): List<El> {
            var cur: El? = g
            var depth = 0
            while (cur != null && depth < 8) {
                val s = cur.children.filter { it.tag == "stop" }
                if (s.isNotEmpty()) return s
                val href = cur.attrs["href"]?.removePrefix("#")
                cur = if (href != null) defs[href] else null
                depth++
            }
            return emptyList()
        }

        private fun gradientShader(g: El, b: RectF, m: Matrix): Shader? {
            val stops = gradientStops(g)
            if (stops.isEmpty()) return null
            val n = max(2, stops.size)
            val colors = IntArray(n)
            val pos = FloatArray(n)
            var last = 0f
            for (i in stops.indices) {
                val s = stops[i]
                val o = s.attrs["offset"]?.let { SvgPathParser.parseFraction(it) } ?: 0f
                last = max(last, o.coerceIn(0f, 1f))
                pos[i] = last
                val c = SvgPathParser.parseColor(s.attrs["stop-color"] ?: "#000000", tokens) ?: Color.BLACK
                val so = s.attrs["stop-opacity"]?.toFloatOrNull() ?: 1f
                colors[i] = (c and 0x00FFFFFF) or ((Color.alpha(c) * so).roundToInt().coerceIn(0, 255) shl 24)
            }
            if (stops.size == 1) {
                colors[1] = colors[0]
                pos[0] = 0f
                pos[1] = 1f
            }

            val bbox = g.attrs["gradientUnits"] != "userSpaceOnUse"
            val local = Matrix(m)
            if (bbox) {
                if (b.width() <= 0f || b.height() <= 0f) return null
                val bm = Matrix()
                bm.setScale(b.width(), b.height())
                bm.postTranslate(b.left, b.top)
                local.preConcat(bm)
            }
            g.attrs["gradientTransform"]?.let { local.preConcat(SvgPathParser.parseTransform(it)) }

            fun coord(k: String, def: Float): Float = g.attrs[k]?.let { SvgPathParser.parseFraction(it) } ?: def

            val shader: Shader = if (g.tag == "linearGradient") {
                LinearGradient(
                    coord("x1", 0f), coord("y1", 0f),
                    coord("x2", if (bbox) 1f else vbW), coord("y2", 0f),
                    colors, pos, Shader.TileMode.CLAMP
                )
            } else {
                val r = coord("r", if (bbox) 0.5f else vbW / 2f)
                if (r <= 0f) return null
                RadialGradient(
                    coord("cx", if (bbox) 0.5f else vbW / 2f), coord("cy", if (bbox) 0.5f else vbW / 2f),
                    r, colors, pos, Shader.TileMode.CLAMP
                )
            }
            shader.setLocalMatrix(local)
            return shader
        }
    }

    // ------------------------------------------------------------------ Figma REST JSON

    /** Accepts a file response, a /nodes response, or a single node subtree. */
    fun importFigmaJson(json: String): VectorAsset {
        orderCounter = 0
        val rootObj = JSONObject(json)
        val doc: JSONObject = when {
            rootObj.has("nodes") -> {
                val nodes = rootObj.getJSONObject("nodes")
                nodes.getJSONObject(nodes.keys().next()).getJSONObject("document")
            }
            rootObj.has("document") -> rootObj.getJSONObject("document")
            else -> rootObj
        }
        val frame = findFrame(doc)
        val box = frame.optJSONObject("absoluteBoundingBox")
        val ox = box?.optDouble("x", 0.0)?.toFloat() ?: 0f
        val oy = box?.optDouble("y", 0.0)?.toFloat() ?: 0f
        val w = box?.optDouble("width", 0.0)?.toFloat() ?: 0f
        val h = box?.optDouble("height", 0.0)?.toFloat() ?: 0f

        val node = figmaNode(frame, ox, oy) ?: VectorNode(frame.optString("id"), frame.optString("name"), true)
        val asset0 = VectorAsset(RectF(0f, 0f, max(w, 1f), max(h, 1f)), node, tokens)
        if (w > 0f && h > 0f) return asset0

        val union = RectF()
        var first = true
        for (s in asset0.shapes) {
            if (first) {
                union.set(s.bounds)
                first = false
            } else union.union(s.bounds)
        }
        if (first) union.set(0f, 0f, 1f, 1f)
        return VectorAsset(union, node, tokens)
    }

    private fun findFrame(n: JSONObject): JSONObject {
        if (n.has("absoluteBoundingBox")) return n
        val ch = n.optJSONArray("children") ?: return n
        for (i in 0 until ch.length()) {
            val c = ch.optJSONObject(i) ?: continue
            val f = findFrame(c)
            if (f.has("absoluteBoundingBox")) return f
        }
        return n
    }

    private fun figmaNode(n: JSONObject, ox: Float, oy: Float): VectorNode? {
        if (!n.optBoolean("visible", true)) return null
        val type = n.optString("type")
        val name = n.optString("name")
        val id = n.optString("id")

        if (type in FIGMA_GROUP_TYPES) {
            val g = VectorNode(id, name, true)
            val ch = n.optJSONArray("children")
            if (ch != null) {
                for (i in 0 until ch.length()) {
                    val c = ch.optJSONObject(i) ?: continue
                    figmaNode(c, ox, oy)?.let { g.children.add(it) }
                }
            }
            return g
        }

        // Geometry is placed from the absolute bounding box; rotated nodes should be flattened in Figma first.
        val box = n.optJSONObject("absoluteBoundingBox") ?: return null
        val bx = box.optDouble("x", 0.0).toFloat() - ox
        val by = box.optDouble("y", 0.0).toFloat() - oy
        val bw = box.optDouble("width", 0.0).toFloat()
        val bh = box.optDouble("height", 0.0).toFloat()

        val path = Path()
        val anchors = ArrayList<PointF>()
        val geoms = n.optJSONArray("fillGeometry")
        if (geoms != null && geoms.length() > 0) {
            val t = Matrix()
            t.setTranslate(bx, by)
            for (i in 0 until geoms.length()) {
                val g = geoms.optJSONObject(i) ?: continue
                val pp = SvgPathParser.parse(g.optString("path"))
                if (g.optString("windingRule") == "EVENODD") path.fillType = Path.FillType.EVEN_ODD
                pp.path.transform(t)
                path.addPath(pp.path)
                for (a in pp.anchors) anchors.add(PointF(a.x + bx, a.y + by))
            }
        } else {
            when (type) {
                "RECTANGLE" -> {
                    val r = n.optDouble("cornerRadius", 0.0).toFloat()
                    path.addRoundRect(RectF(bx, by, bx + bw, by + bh), r, r, Path.Direction.CW)
                }
                "ELLIPSE" -> path.addOval(RectF(bx, by, bx + bw, by + bh), Path.Direction.CW)
                "LINE" -> {
                    path.moveTo(bx, by)
                    path.lineTo(bx + bw, by + bh)
                }
                else -> return null
            }
            anchors.add(PointF(bx, by))
            anchors.add(PointF(bx + bw, by + bh))
        }
        if (path.isEmpty) return null

        val opacity = n.optDouble("opacity", 1.0).toFloat()
        val fill = figmaPaint(n.optJSONArray("fills"), true, bx, by, bw, bh, 0f, opacity, n)
        val weight = n.optDouble("strokeWeight", 0.0).toFloat()
        val stroke = if (weight > 0f) figmaPaint(n.optJSONArray("strokes"), false, bx, by, bw, bh, weight, opacity, n) else null

        val lower = name.lowercase()
        val marker = lower.contains("pivot") || lower.contains("anchor")
        if (fill == null && stroke == null && !marker) return null

        val node = VectorNode(id, name, false)
        node.shape = VectorShape(id, name, orderCounter++, path, anchors, fill, stroke, marker)
        return node
    }

    private fun figmaPaint(
        arr: JSONArray?, isFill: Boolean, bx: Float, by: Float, bw: Float, bh: Float,
        weight: Float, nodeOpacity: Float, node: JSONObject
    ): Paint? {
        if (arr == null) return null
        for (i in arr.length() - 1 downTo 0) {
            val f = arr.optJSONObject(i) ?: continue
            if (!f.optBoolean("visible", true)) continue
            val op = (f.optDouble("opacity", 1.0).toFloat() * nodeOpacity).coerceIn(0f, 1f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = if (isFill) Paint.Style.FILL else Paint.Style.STROKE
            val type = f.optString("type")
            when (type) {
                "SOLID" -> {
                    val c = jsonRgba(f.optJSONObject("color")) ?: continue
                    p.color = (c and 0x00FFFFFF) or ((Color.alpha(c) * op).roundToInt().coerceIn(0, 255) shl 24)
                }
                "GRADIENT_LINEAR", "GRADIENT_RADIAL" -> {
                    val hs = f.optJSONArray("gradientHandlePositions")
                    val st = f.optJSONArray("gradientStops")
                    if (hs == null || hs.length() < 2 || st == null || st.length() < 2) continue
                    val h0 = hs.getJSONObject(0)
                    val h1 = hs.getJSONObject(1)
                    val x0 = bx + h0.optDouble("x", 0.0).toFloat() * bw
                    val y0 = by + h0.optDouble("y", 0.0).toFloat() * bh
                    val x1 = bx + h1.optDouble("x", 0.0).toFloat() * bw
                    val y1 = by + h1.optDouble("y", 0.0).toFloat() * bh
                    val colors = IntArray(st.length())
                    val pos = FloatArray(st.length())
                    var last = 0f
                    for (k in 0 until st.length()) {
                        val s = st.getJSONObject(k)
                        colors[k] = jsonRgba(s.optJSONObject("color")) ?: Color.BLACK
                        last = max(last, s.optDouble("position", 0.0).toFloat().coerceIn(0f, 1f))
                        pos[k] = last
                    }
                    p.shader = if (type == "GRADIENT_LINEAR") {
                        LinearGradient(x0, y0, x1, y1, colors, pos, Shader.TileMode.CLAMP)
                    } else {
                        RadialGradient(x0, y0, max(hypot(x1 - x0, y1 - y0), 0.01f), colors, pos, Shader.TileMode.CLAMP)
                    }
                    p.alpha = (op * 255f).roundToInt()
                }
                else -> continue
            }
            if (!isFill) {
                p.strokeWidth = weight
                p.strokeCap = if (node.optString("strokeCap") == "ROUND") Paint.Cap.ROUND else Paint.Cap.BUTT
                p.strokeJoin = when (node.optString("strokeJoin")) {
                    "ROUND" -> Paint.Join.ROUND
                    "BEVEL" -> Paint.Join.BEVEL
                    else -> Paint.Join.MITER
                }
            }
            return p
        }
        return null
    }
}
