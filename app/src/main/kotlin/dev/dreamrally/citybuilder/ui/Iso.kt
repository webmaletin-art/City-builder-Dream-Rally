package dev.dreamrally.citybuilder.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import dev.dreamrally.citybuilder.model.*

/** Cámara isométrica. rotK = giro en cuartos de vuelta (0..3). zoom = ancho en píxeles de una celda. */
class Camera(var cx: Float = 0f, var cy: Float = 0f, var zoom: Float = 56f, var rotK: Int = 0) {
    var w = 1f; var h = 1f // tamaño del lienzo

    private fun rot(dx: Float, dy: Float): Pair<Float, Float> = when (rotK and 3) {
        0 -> dx to dy; 1 -> -dy to dx; 2 -> -dx to -dy; else -> dy to -dx
    }
    private fun unrot(u: Float, v: Float): Pair<Float, Float> = when (rotK and 3) {
        0 -> u to v; 1 -> v to -u; 2 -> -u to -v; else -> -v to u
    }

    /** Celda (x,y) a pantalla; hCells = altura en unidades de celda. */
    fun project(x: Float, y: Float, hCells: Float = 0f): Offset {
        val (u, v) = rot(x - cx, y - cy)
        return Offset(w / 2 + (u - v) * zoom / 2, h / 2 + (u + v) * zoom / 4 - hCells * zoom * 0.5f)
    }

    fun depth(x: Float, y: Float): Float { val (u, v) = rot(x - cx, y - cy); return u + v }

    /** Pantalla a coordenadas de celda (plano del suelo, altura 0). */
    fun unproject(sx: Float, sy: Float): Pair<Float, Float> {
        val a = 2 * (sx - w / 2) / zoom; val b = 4 * (sy - h / 2) / zoom
        val (dx, dy) = unrot((a + b) / 2, (b - a) / 2)
        return cx + dx to cy + dy
    }

    /** Rectángulo de celdas visible (con margen para edificios altos). */
    fun visibleCells(margin: Int = 6): IntArray {
        val pts = listOf(unproject(0f, 0f), unproject(w, 0f), unproject(0f, h), unproject(w, h))
        return intArrayOf(
            Math.floor(pts.minOf { it.first }.toDouble()).toInt() - margin, Math.floor(pts.minOf { it.second }.toDouble()).toInt() - margin,
            Math.ceil(pts.maxOf { it.first }.toDouble()).toInt() + margin, Math.ceil(pts.maxOf { it.second }.toDouble()).toInt() + margin
        )
    }

    fun panByScreen(dx: Float, dy: Float) {
        val a = 2 * dx / zoom; val b = 4 * dy / zoom
        val (wx, wy) = unrot((a + b) / 2, (b - a) / 2)
        cx -= wx; cy -= wy
    }
}

fun colorOf(rgb: Int, shade: Float = 1f) = Color(
    (((rgb shr 16) and 255) * shade).toInt().coerceIn(0, 255), (((rgb shr 8) and 255) * shade).toInt().coerceIn(0, 255), ((rgb and 255) * shade).toInt().coerceIn(0, 255)
)

private fun poly(a: Offset, b: Offset, c: Offset, d: Offset? = null): Path = Path().apply {
    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); if (d != null) lineTo(d.x, d.y); close()
}

/** Dibuja un edificio del catálogo como volumen isométrico. (x,y,w,d): huella en celdas ya girada. */
fun DrawScope.drawBuilding(cam: Camera, spec: BuildingSpec, x: Int, y: Int, fw: Int, fd: Int, alpha: Float = 1f, tint: Color? = null, baseH: Float = 0f) {
    val m = spec.model
    val hC = (m.heightM / Grid.CELL_M).toFloat()
    val x0 = x + 0.1f; val y0 = y + 0.1f; val x1 = x + fw - 0.1f; val y1 = y + fd - 0.1f
    fun col(rgb: Int, s: Float = 1f) = (tint ?: colorOf(rgb, s)).copy(alpha = alpha)
    fun P(px: Float, py: Float, h: Float) = cam.project(px, py, baseH + h)
    fun prism(ax: Float, ay: Float, bx: Float, by: Float, hBase: Float, hTop: Float, wall: Int, roof: Int, roofFlat: Boolean = true) {
        // 4 paredes ordenadas de atrás hacia delante, luego el techo
        data class W(val a: Pair<Float, Float>, val b: Pair<Float, Float>, val shade: Float)
        val ws = listOf(W(ax to ay, bx to ay, 0.82f), W(bx to ay, bx to by, 0.66f), W(bx to by, ax to by, 0.9f), W(ax to by, ax to ay, 0.74f))
            .sortedBy { cam.depth((it.a.first + it.b.first) / 2, (it.a.second + it.b.second) / 2) }
        for (w in ws) drawPath(poly(P(w.a.first, w.a.second, hBase), P(w.b.first, w.b.second, hBase), P(w.b.first, w.b.second, hTop), P(w.a.first, w.a.second, hTop)), col(wall, w.shade))
        if (roofFlat) drawPath(poly(P(ax, ay, hTop), P(bx, ay, hTop), P(bx, by, hTop), P(ax, by, hTop)), col(roof, 1.0f))
    }
    val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
    when (m.shape) {
        "gable" -> {
            val eave = hC * 0.62f
            prism(x0, y0, x1, y1, 0f, eave, m.wallRgb, m.roofRgb, roofFlat = false)
            val alongX = (x1 - x0) >= (y1 - y0)
            val r0 = if (alongX) P(x0 - 0.05f, cy, hC) else P(cx, y0 - 0.05f, hC); val r1 = if (alongX) P(x1 + 0.05f, cy, hC) else P(cx, y1 + 0.05f, hC)
            val c = listOf(P(x0, y0, eave), P(x1, y0, eave), P(x1, y1, eave), P(x0, y1, eave))
            val sides = if (alongX) listOf(poly(c[0], c[1], r1, r0), poly(c[3], c[2], r1, r0)) else listOf(poly(c[0], c[3], r1, r0), poly(c[1], c[2], r1, r0))
            val order = sides.indices.sortedBy { i -> if (alongX) cam.depth(cx, if (i == 0) y0 else y1) else cam.depth(if (i == 0) x0 else x1, cy) }
            order.forEachIndexed { k, i -> drawPath(sides[i], col(m.roofRgb, if (k == 0) 0.85f else 1.05f)) }
        }
        "stepped" -> {
            val sc = floatArrayOf(1f, 0.8f, 0.6f); val tops = floatArrayOf(0.55f, 0.85f, 1f); var hb = 0f
            for (i in 0..2) {
                val ex = (x1 - x0) / 2 * (1 - sc[i]); val ey = (y1 - y0) / 2 * (1 - sc[i])
                prism(x0 + ex, y0 + ey, x1 - ex, y1 - ey, hb, hC * tops[i], m.wallRgb, m.roofRgb); hb = hC * tops[i]
            }
        }
        "tree", "pine", "palm", "bush" -> {
            val base = P(cx, cy, 0f)
            if (m.shape == "bush") { drawOval(col(m.roofRgb), Offset(base.x - cam.zoom * 0.22f, base.y - cam.zoom * 0.2f), androidx.compose.ui.geometry.Size(cam.zoom * 0.44f, cam.zoom * 0.28f)) }
            else {
                val top = P(cx, cy, hC * 0.5f)
                drawLine(col(m.wallRgb), base, top, strokeWidth = cam.zoom * 0.07f, cap = StrokeCap.Round)
                if (m.shape == "pine") {
                    for (k in 0..2) { val b = P(cx, cy, hC * (0.18f + 0.22f * k)); val t = P(cx, cy, hC * (0.45f + 0.28f * k)); val r = cam.zoom * (0.26f - 0.06f * k)
                        drawPath(poly(Offset(b.x - r, b.y), Offset(b.x + r, b.y), t), col(m.roofRgb, 0.9f + 0.08f * k)) }
                } else drawCircle(col(m.roofRgb), cam.zoom * 0.28f, P(cx, cy, hC * 0.72f))
            }
        }
        "park", "plaza" -> {
            val flat = (m.heightM / Grid.CELL_M).toFloat().coerceAtLeast(0.03f)
            prism(x + 0.05f, y + 0.05f, x + fw - 0.05f, y + fd - 0.05f, 0f, flat, m.wallRgb, m.wallRgb)
            if (m.shape == "park") for (i in 0 until (fw * fd) / 2 + 1) { val tx = x + 0.7f + ((i * 37 + spec.id.length * 11) % 100) / 100f * (fw - 1.4f); val ty = y + 0.7f + ((i * 61 + spec.id.hashCode() % 53 + 53) % 100) / 100f * (fd - 1.4f)
                drawCircle(Color(0xFF3F8F3A).copy(alpha = alpha), cam.zoom * 0.17f, P(tx, ty, 0.35f)) }
        }
        "fountain" -> {
            prism(x + 0.05f, y + 0.05f, x + fw - 0.05f, y + fd - 0.05f, 0f, 0.03f, m.wallRgb, m.wallRgb)
            drawCircle(col(m.roofRgb), cam.zoom * 0.35f, P(cx, cy, 0.08f))
        }
        "lamp" -> drawLine(col(m.wallRgb), P(cx, cy, 0f), P(cx, cy, hC), strokeWidth = cam.zoom * 0.05f).also { drawCircle(col(m.roofRgb), cam.zoom * 0.08f, P(cx, cy, hC)) }
        "silo" -> {
            val r = cam.zoom * 0.28f; val b = P(cx, cy, 0f); val t = P(cx, cy, hC)
            drawRect(col(m.wallRgb, 0.85f), Offset(b.x - r, t.y), androidx.compose.ui.geometry.Size(2 * r, b.y - t.y))
            drawOval(col(m.wallRgb, 1.0f), Offset(t.x - r, t.y - r / 2), androidx.compose.ui.geometry.Size(2 * r, r))
            drawOval(col(m.roofRgb), Offset(b.x - r, b.y - r / 2), androidx.compose.ui.geometry.Size(2 * r, r))
        }
        "chimney" -> {
            prism(x0, y0, x1, y1, 0f, hC, m.wallRgb, m.roofRgb)
            val b = P(x0 + (x1 - x0) * 0.25f, cy, hC); val t = P(x0 + (x1 - x0) * 0.25f, cy, hC * 1.8f)
            drawLine(col(0x8A8A8A), b, t, strokeWidth = cam.zoom * 0.14f)
        }
        else -> prism(x0, y0, x1, y1, 0f, hC, m.wallRgb, m.roofRgb)
    }
}

/** Contorno de una huella sobre el suelo. */
fun DrawScope.drawFootprint(cam: Camera, x: Int, y: Int, fw: Int, fd: Int, fill: Color, line: Color, h: Float = 0f) {
    val p = poly(cam.project(x.toFloat(), y.toFloat(), h), cam.project((x + fw).toFloat(), y.toFloat(), h), cam.project((x + fw).toFloat(), (y + fd).toFloat(), h), cam.project(x.toFloat(), (y + fd).toFloat(), h))
    drawPath(p, fill); drawPath(p, line, style = Stroke(width = 3f))
}

fun DrawScope.drawCell(cam: Camera, x: Int, y: Int, color: Color, hs: FloatArray? = null) {
    fun hh(i: Int) = hs?.get(i) ?: 0f
    drawPath(poly(cam.project(x.toFloat(), y.toFloat(), hh(0)), cam.project(x + 1f, y.toFloat(), hh(1)), cam.project(x + 1f, y + 1f, hh(2)), cam.project(x.toFloat(), y + 1f, hh(3))), color)
}
