package dev.dreamrally.citybuilder.model

import kotlin.math.hypot

/** Curvas viales: la topología sigue la rejilla, la geometría exportada redondea las esquinas con arcos (Bézier cuadrática). */
object Curves {
    /** Puntos (x,z). Quita colineales y redondea cada esquina con radio [radius] (limitado a la mitad de cada tramo). */
    fun fillet(pts: List<DoubleArray>, radius: Double = Grid.CELL_M, samples: Int = 6): List<DoubleArray> {
        val p = ArrayList<DoubleArray>()
        for (q in pts) { if (p.isEmpty() || hypot(q[0] - p.last()[0], q[1] - p.last()[1]) > 1e-9) p += q }
        // quitar colineales
        val c = ArrayList<DoubleArray>(); c += p.first()
        for (i in 1 until p.size - 1) {
            val a = p[i - 1]; val b = p[i]; val d = p[i + 1]
            val cross = (b[0] - a[0]) * (d[1] - b[1]) - (b[1] - a[1]) * (d[0] - b[0])
            if (kotlin.math.abs(cross) > 1e-9) c += b
        }
        if (p.size > 1) c += p.last()
        if (c.size < 3) return c
        val out = ArrayList<DoubleArray>(); out += c.first()
        for (i in 1 until c.size - 1) {
            val a = c[i - 1]; val b = c[i]; val d = c[i + 1]
            val l1 = hypot(b[0] - a[0], b[1] - a[1]); val l2 = hypot(d[0] - b[0], d[1] - b[1])
            val r1 = minOf(radius, l1 / 2); val r2 = minOf(radius, l2 / 2); val r = minOf(r1, r2)
            val s = doubleArrayOf(b[0] + (a[0] - b[0]) / l1 * r, b[1] + (a[1] - b[1]) / l1 * r)
            val e = doubleArrayOf(b[0] + (d[0] - b[0]) / l2 * r, b[1] + (d[1] - b[1]) / l2 * r)
            for (k in 0..samples) {
                val t = k.toDouble() / samples; val u = 1 - t
                out += doubleArrayOf(u * u * s[0] + 2 * u * t * b[0] + t * t * e[0], u * u * s[1] + 2 * u * t * b[1] + t * t * e[1])
            }
        }
        out += c.last()
        return out
    }
}
