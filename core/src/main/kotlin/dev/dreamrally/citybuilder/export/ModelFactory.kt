package dev.dreamrally.citybuilder.export

import dev.dreamrally.citybuilder.glb.*
import dev.dreamrally.citybuilder.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Genera mallas procedurales (recursos propios) para cada entrada del catálogo. Origen: centro de la huella, base a y=0. */
object ModelFactory {
    const val FOUNDATION = 3.0
    private const val MARGIN = 0.8

    private fun hex(rgb: Int) = "%06X".format(rgb)
    private fun d(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
    private fun uv(u: Double, v: Double) = doubleArrayOf(u, v)

    fun wallMat(spec: ModelSpec) =
        if (spec.facade == "none") Mat("flat_${hex(spec.wallRgb)}", spec.wallRgb, "concrete")
        else Mat("wall_${spec.facade}_${hex(spec.wallRgb)}", spec.wallRgb, "facade_${spec.facade}")
    fun roofTileMat(spec: ModelSpec) = Mat("roof_${hex(spec.roofRgb)}", spec.roofRgb, "roof_tiles")
    fun flatRoofMat(spec: ModelSpec) = Mat("flatroof_${hex(spec.roofRgb)}", spec.roofRgb, "concrete")
    private fun plain(rgb: Int, prefix: String) = Mat("${prefix}_${hex(rgb)}", rgb, null, roughness = 0.95)

    fun build(spec: BuildingSpec, m: ModelSpec = spec.model): MeshBuilder {
        val mb = MeshBuilder()
        val w = spec.widthCells * Grid.CELL_M; val dp = spec.depthCells * Grid.CELL_M
        val hw = w / 2 - MARGIN; val hd = dp / 2 - MARGIN
        when (m.shape) {
            "gable" -> gable(mb, m, hw, hd)
            "stepped" -> stepped(mb, m, hw, hd)
            "tree" -> tree(mb, m)
            "pine" -> pine(mb, m)
            "palm" -> palm(mb, m)
            "bush" -> sphere(mb, plain(m.roofRgb, "foliage"), 0.0, 0.7, 0.0, 1.2, 0.8, 1.2, 8, 5)
            "park" -> park(mb, spec, m, w / 2, dp / 2)
            "plaza" -> slab(mb, Mat("pavers_${hex(m.wallRgb)}", m.wallRgb, "pavers"), w / 2 - 0.2, dp / 2 - 0.2, m.heightM.coerceAtLeast(0.1), tex = true)
            "fountain" -> fountain(mb, m, w / 2, dp / 2)
            "lamp" -> lamp(mb, m)
            "silo" -> silo(mb, m, minOf(hw, hd))
            "chimney" -> chimneyPlant(mb, m, hw, hd)
            else -> box(mb, m, hw, hd)
        }
        return mb
    }

    // ---- primitivas ----
    /** Cuatro paredes con UV de fachada (1 tesela = 4 m x 3.5 m). */
    fun walls(mb: MeshBuilder, mat: Mat, x0: Double, z0: Double, x1: Double, z1: Double, y0: Double, y1: Double) {
        val cx = (x0 + x1) / 2; val cz = (z0 + z1) / 2
        fun side(ax: Double, az: Double, bx: Double, bz: Double, nx: Double, nz: Double) {
            val len = Math.hypot(bx - ax, bz - az)
            mb.quad(mat, arrayOf(d(ax, y0, az), d(bx, y0, bz), d(bx, y1, bz), d(ax, y1, az)),
                arrayOf(uv(0.0, y0 / 3.5), uv(len / 4.0, y0 / 3.5), uv(len / 4.0, y1 / 3.5), uv(0.0, y1 / 3.5)), d(nx, 0.0, nz))
        }
        side(x0, z0, x1, z0, 0.0, -1.0); side(x1, z0, x1, z1, 1.0, 0.0); side(x1, z1, x0, z1, 0.0, 1.0); side(x0, z1, x0, z0, -1.0, 0.0)
    }

    fun top(mb: MeshBuilder, mat: Mat, x0: Double, z0: Double, x1: Double, z1: Double, y: Double, uvScale: Double = 8.0) {
        mb.quad(mat, arrayOf(d(x0, y, z0), d(x1, y, z0), d(x1, y, z1), d(x0, y, z1)),
            arrayOf(uv(x0 / uvScale, z0 / uvScale), uv(x1 / uvScale, z0 / uvScale), uv(x1 / uvScale, z1 / uvScale), uv(x0 / uvScale, z1 / uvScale)), d(0.0, 1.0, 0.0))
    }

    private fun solidBox(mb: MeshBuilder, wall: Mat, roof: Mat, x0: Double, z0: Double, x1: Double, z1: Double, y0: Double, y1: Double) {
        walls(mb, wall, x0, z0, x1, z1, y0, y1); top(mb, roof, x0, z0, x1, z1, y1)
    }

    private fun box(mb: MeshBuilder, m: ModelSpec, hw: Double, hd: Double) =
        solidBox(mb, wallMat(m), flatRoofMat(m), -hw, -hd, hw, hd, -FOUNDATION, m.heightM)

    private fun gable(mb: MeshBuilder, m: ModelSpec, hw: Double, hd: Double) {
        val eave = m.heightM * 0.62; val wall = wallMat(m); val roof = roofTileMat(m); val o = 0.5
        walls(mb, wall, -hw, -hd, hw, hd, -FOUNDATION, eave)
        val alongX = hw >= hd
        if (alongX) { // cumbrera paralela a X
            val ex = hw + o; val ez = hd + o
            val sl = Math.hypot(ez, m.heightM - eave)
            mb.quad(roof, arrayOf(d(-ex, eave, -ez), d(ex, eave, -ez), d(ex, m.heightM, 0.0), d(-ex, m.heightM, 0.0)), arrayOf(uv(0.0, 0.0), uv(2 * ex / 8, 0.0), uv(2 * ex / 8, sl / 8), uv(0.0, sl / 8)), d(0.0, 1.0, -1.0))
            mb.quad(roof, arrayOf(d(-ex, eave, ez), d(ex, eave, ez), d(ex, m.heightM, 0.0), d(-ex, m.heightM, 0.0)), arrayOf(uv(0.0, 0.0), uv(2 * ex / 8, 0.0), uv(2 * ex / 8, sl / 8), uv(0.0, sl / 8)), d(0.0, 1.0, 1.0))
            mb.tri(wall, arrayOf(d(-hw, eave, -hd), d(-hw, eave, hd), d(-hw, m.heightM, 0.0)), arrayOf(uv(0.0, eave / 3.5), uv(2 * hd / 4, eave / 3.5), uv(hd / 4, m.heightM / 3.5)), d(-1.0, 0.0, 0.0))
            mb.tri(wall, arrayOf(d(hw, eave, -hd), d(hw, eave, hd), d(hw, m.heightM, 0.0)), arrayOf(uv(0.0, eave / 3.5), uv(2 * hd / 4, eave / 3.5), uv(hd / 4, m.heightM / 3.5)), d(1.0, 0.0, 0.0))
        } else {
            val ex = hw + o; val ez = hd + o
            val sl = Math.hypot(ex, m.heightM - eave)
            mb.quad(roof, arrayOf(d(-ex, eave, -ez), d(-ex, eave, ez), d(0.0, m.heightM, ez), d(0.0, m.heightM, -ez)), arrayOf(uv(0.0, 0.0), uv(2 * ez / 8, 0.0), uv(2 * ez / 8, sl / 8), uv(0.0, sl / 8)), d(-1.0, 1.0, 0.0))
            mb.quad(roof, arrayOf(d(ex, eave, -ez), d(ex, eave, ez), d(0.0, m.heightM, ez), d(0.0, m.heightM, -ez)), arrayOf(uv(0.0, 0.0), uv(2 * ez / 8, 0.0), uv(2 * ez / 8, sl / 8), uv(0.0, sl / 8)), d(1.0, 1.0, 0.0))
            mb.tri(wall, arrayOf(d(-hw, eave, -hd), d(hw, eave, -hd), d(0.0, m.heightM, -hd)), arrayOf(uv(0.0, eave / 3.5), uv(2 * hw / 4, eave / 3.5), uv(hw / 4, m.heightM / 3.5)), d(0.0, 0.0, -1.0))
            mb.tri(wall, arrayOf(d(-hw, eave, hd), d(hw, eave, hd), d(0.0, m.heightM, hd)), arrayOf(uv(0.0, eave / 3.5), uv(2 * hw / 4, eave / 3.5), uv(hw / 4, m.heightM / 3.5)), d(0.0, 0.0, 1.0))
        }
    }

    private fun stepped(mb: MeshBuilder, m: ModelSpec, hw: Double, hd: Double) {
        val scales = doubleArrayOf(1.0, 0.8, 0.6); val tops = doubleArrayOf(0.55, 0.85, 1.0)
        var y0 = -FOUNDATION
        for (i in 0..2) {
            val y1 = m.heightM * tops[i]
            solidBox(mb, wallMat(m), flatRoofMat(m), -hw * scales[i], -hd * scales[i], hw * scales[i], hd * scales[i], y0, y1)
            y0 = y1
        }
        if (m.heightM >= 40) cylinder(mb, plain(0x777777, "metal"), 0.0, 0.0, 0.35, m.heightM, m.heightM + m.heightM * 0.15, 6)
    }

    fun cylinder(mb: MeshBuilder, mat: Mat, cx: Double, cz: Double, r: Double, y0: Double, y1: Double, n: Int, cap: Boolean = true, rTop: Double = r) {
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n
            val am = (a0 + a1) / 2
            mb.quad(mat, arrayOf(d(cx + r * cos(a0), y0, cz + r * sin(a0)), d(cx + r * cos(a1), y0, cz + r * sin(a1)), d(cx + rTop * cos(a1), y1, cz + rTop * sin(a1)), d(cx + rTop * cos(a0), y1, cz + rTop * sin(a0))),
                arrayOf(uv(i.toDouble() / n, y0 / 3.5), uv((i + 1.0) / n, y0 / 3.5), uv((i + 1.0) / n, y1 / 3.5), uv(i.toDouble() / n, y1 / 3.5)), d(cos(am), 0.0, sin(am)))
            if (cap && rTop > 0.0) mb.tri(mat, arrayOf(d(cx, y1, cz), d(cx + rTop * cos(a0), y1, cz + rTop * sin(a0)), d(cx + rTop * cos(a1), y1, cz + rTop * sin(a1))),
                arrayOf(uv(0.5, 0.5), uv(0.5, 0.5), uv(0.5, 0.5)), d(0.0, 1.0, 0.0))
        }
    }

    fun cone(mb: MeshBuilder, mat: Mat, cx: Double, cz: Double, r: Double, y0: Double, y1: Double, n: Int) {
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n; val am = (a0 + a1) / 2
            mb.tri(mat, arrayOf(d(cx + r * cos(a0), y0, cz + r * sin(a0)), d(cx + r * cos(a1), y0, cz + r * sin(a1)), d(cx, y1, cz)),
                arrayOf(uv(0.0, 0.0), uv(1.0, 0.0), uv(0.5, 1.0)), d(cos(am), r / (y1 - y0), sin(am)))
        }
    }

    fun sphere(mb: MeshBuilder, mat: Mat, cx: Double, cy: Double, cz: Double, rx: Double, ry: Double, rz: Double, seg: Int, rings: Int) {
        fun p(i: Int, j: Int): DoubleArray {
            val phi = PI * j / rings; val th = 2 * PI * i / seg
            return d(cx + rx * sin(phi) * cos(th), cy + ry * cos(phi), cz + rz * sin(phi) * sin(th))
        }
        for (j in 0 until rings) for (i in 0 until seg) {
            val a = p(i, j); val b = p(i + 1, j); val c = p(i + 1, j + 1); val e = p(i, j + 1)
            val hint = d((a[0] + b[0] + c[0] + e[0]) / 4 - cx, (a[1] + b[1] + c[1] + e[1]) / 4 - cy, (a[2] + b[2] + c[2] + e[2]) / 4 - cz)
            if (j == 0) mb.tri(mat, arrayOf(a, c, e), arrayOf(uv(0.0, 0.0), uv(1.0, 1.0), uv(0.0, 1.0)), hint)
            else if (j == rings - 1) mb.tri(mat, arrayOf(a, b, e), arrayOf(uv(0.0, 0.0), uv(1.0, 0.0), uv(0.0, 1.0)), hint)
            else mb.quad(mat, arrayOf(a, b, c, e), arrayOf(uv(0.0, 0.0), uv(1.0, 0.0), uv(1.0, 1.0), uv(0.0, 1.0)), hint)
        }
    }

    private fun tree(mb: MeshBuilder, m: ModelSpec) {
        cylinder(mb, plain(m.wallRgb, "bark"), 0.0, 0.0, 0.5, -0.5, m.heightM * 0.45, 6)
        sphere(mb, plain(m.roofRgb, "foliage"), 0.0, m.heightM * 0.68, 0.0, 2.6, m.heightM * 0.3, 2.6, 8, 5)
    }

    private fun pine(mb: MeshBuilder, m: ModelSpec) {
        cylinder(mb, plain(m.wallRgb, "bark"), 0.0, 0.0, 0.45, -0.5, m.heightM * 0.3, 6)
        val leaf = plain(m.roofRgb, "foliage")
        cone(mb, leaf, 0.0, 0.0, 2.8, m.heightM * 0.2, m.heightM * 0.55, 8)
        cone(mb, leaf, 0.0, 0.0, 2.2, m.heightM * 0.42, m.heightM * 0.78, 8)
        cone(mb, leaf, 0.0, 0.0, 1.5, m.heightM * 0.65, m.heightM, 8)
    }

    private fun palm(mb: MeshBuilder, m: ModelSpec) {
        cylinder(mb, plain(m.wallRgb, "bark"), 0.0, 0.0, 0.4, -0.5, m.heightM * 0.85, 6, rTop = 0.25)
        val leaf = Mat("foliage_${hex(m.roofRgb)}_ds", m.roofRgb, null, roughness = 0.95, doubleSided = true)
        val top = m.heightM * 0.85
        for (i in 0 until 6) {
            val a = 2 * PI * i / 6; val a2 = a + 0.5
            mb.tri(leaf, arrayOf(d(0.0, top, 0.0), d(3.4 * cos(a), top - 1.6, 3.4 * sin(a)), d(1.0 * cos(a2) + 3.0 * cos(a), top - 1.0, 1.0 * sin(a2) + 3.0 * sin(a))), arrayOf(uv(0.0, 0.0), uv(1.0, 0.0), uv(0.5, 1.0)), d(0.0, 1.0, 0.0))
        }
    }

    fun slab(mb: MeshBuilder, mat: Mat, hw: Double, hd: Double, h: Double, tex: Boolean = false, y0: Double = -0.5) {
        walls(mb, mat, -hw, -hd, hw, hd, y0, h); top(mb, mat, -hw, -hd, hw, hd, h, if (tex) 8.0 else 100.0)
    }

    private fun park(mb: MeshBuilder, spec: BuildingSpec, m: ModelSpec, hw: Double, hd: Double) {
        slab(mb, Mat("grass_park", m.wallRgb, null, roughness = 1.0), hw - 0.2, hd - 0.2, 0.3)
        // camino de adoquín en cruz
        top(mb, Mat("pavers_C9C2B4", 0xC9C2B4, "pavers"), -1.0, -hd + 0.4, 1.0, hd - 0.4, 0.34)
        top(mb, Mat("pavers_C9C2B4", 0xC9C2B4, "pavers"), -hw + 0.4, -1.0, hw - 0.4, 1.0, 0.34)
        var seed = spec.id.hashCode()
        fun rnd(): Double { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535.0 }
        val n = (spec.widthCells * spec.depthCells) / 2 + 1
        val trunk = plain(0x6B4A2B, "bark"); val leaf = plain(0x3F8F3A, "foliage")
        repeat(n) {
            val tx = (rnd() * 2 - 1) * (hw - 3); val tz = (rnd() * 2 - 1) * (hd - 3)
            if (kotlin.math.abs(tx) < 2 || kotlin.math.abs(tz) < 2) return@repeat
            cylinder(mb, trunk, tx, tz, 0.3, 0.3, 3.0, 5)
            sphere(mb, leaf, tx, 4.4, tz, 1.8, 1.6, 1.8, 7, 4)
        }
    }

    private fun fountain(mb: MeshBuilder, m: ModelSpec, hw: Double, hd: Double) {
        slab(mb, Mat("pavers_${hex(m.wallRgb)}", m.wallRgb, "pavers"), hw - 0.2, hd - 0.2, 0.2, tex = true)
        val r = minOf(hw, hd) * 0.5
        cylinder(mb, plain(0xB5B0A5, "stone"), 0.0, 0.0, r, 0.0, 1.0, 12)
        cylinder(mb, Mat("water_${hex(m.roofRgb)}", m.roofRgb, null, roughness = 0.1, metallic = 0.1), 0.0, 0.0, r - 0.3, 0.9, 0.95, 12)
        cylinder(mb, plain(0xB5B0A5, "stone"), 0.0, 0.0, 0.4, 0.9, m.heightM + 0.6, 8, rTop = 0.25)
    }

    private fun lamp(mb: MeshBuilder, m: ModelSpec) {
        cylinder(mb, plain(m.wallRgb, "metal"), 0.0, 0.0, 0.15, 0.0, m.heightM, 6)
        solidBox(mb, plain(m.roofRgb, "lamp"), plain(m.roofRgb, "lamp"), -0.35, -0.35, 0.35, 0.35, m.heightM, m.heightM + 0.5)
    }

    private fun silo(mb: MeshBuilder, m: ModelSpec, r: Double) {
        cylinder(mb, wallMat(m), 0.0, 0.0, r, -FOUNDATION, m.heightM, 12, cap = false)
        cone(mb, plain(m.roofRgb, "siloroof"), 0.0, 0.0, r, m.heightM, m.heightM + r * 0.7, 12)
    }

    private fun chimneyPlant(mb: MeshBuilder, m: ModelSpec, hw: Double, hd: Double) {
        solidBox(mb, wallMat(m), flatRoofMat(m), -hw, -hd, hw * 0.4, hd, -FOUNDATION, m.heightM)
        solidBox(mb, wallMat(m), flatRoofMat(m), hw * 0.45, -hd * 0.5, hw, hd * 0.5, -FOUNDATION, m.heightM * 0.6)
        cylinder(mb, plain(0x8A8A8A, "chimney"), -hw * 0.5, 0.0, 1.3, m.heightM, m.heightM * 1.9, 10, rTop = 1.0)
        cylinder(mb, plain(0x8A8A8A, "chimney"), -hw * 0.1, hd * 0.4, 1.0, m.heightM, m.heightM * 1.6, 10, rTop = 0.8)
    }

    /** Placeholder para modelos GLB externos no disponibles: caja de la huella (nunca se exporta como si fuera el modelo real). */
    fun footprintBox(spec: BuildingSpec): MeshBuilder {
        val mb = MeshBuilder(); val hw = spec.widthCells * Grid.CELL_M / 2 - MARGIN; val hd = spec.depthCells * Grid.CELL_M / 2 - MARGIN
        solidBox(mb, plain(0xAAAAAA, "placeholder"), plain(0x888888, "placeholder"), -hw, -hd, hw, hd, -FOUNDATION, spec.model.heightM)
        return mb
    }
}
