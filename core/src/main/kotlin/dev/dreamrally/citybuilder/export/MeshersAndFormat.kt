package dev.dreamrally.citybuilder.export

import dev.dreamrally.citybuilder.glb.*
import dev.dreamrally.citybuilder.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object Format {
    const val PACKAGE_NAME = "DreamCityPackage"
    const val PACKAGE_VERSION = "1.1.0"
    const val SCHEMA_VERSION = 1
    const val ROAD_LIFT = 0.06
    const val VERTS = Grid.SECTOR_CELLS + 1

    fun sectorOrigin(s: SectorId) = doubleArrayOf(s.x * Grid.SECTOR_M, s.y * Grid.SECTOR_M)
    fun world(v: Vtx) = doubleArrayOf(v.x * Grid.CELL_M, v.y * Grid.CELL_M)
}

class HeightField(val config: CityConfig) {
    fun at(vx: Int, vy: Int) = Heights.at(config.relief, config.reliefSeed, vx, vy)
    fun at(v: Vtx) = at(v.x, v.y)
    /** Altura bilineal en coordenadas del mundo (metros), coherente con los vértices del terreno. */
    fun atWorld(x: Double, z: Double): Double {
        val gx = x / Grid.CELL_M; val gy = z / Grid.CELL_M
        val i = Math.floor(gx).toInt(); val j = Math.floor(gy).toInt(); val tx = gx - i; val ty = gy - j
        val a = at(i, j); val b = at(i + 1, j); val c = at(i, j + 1); val d = at(i + 1, j + 1)
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }
    fun footprintBase(x: Int, y: Int, w: Int, d: Int): Double {
        var m = Double.MAX_VALUE
        for (i in x..x + w) for (j in y..y + d) m = minOf(m, at(i, j))
        return m
    }
}

object TerrainMesher {
    fun groundMat(b: Biome) = Mat("ground_${b.name.lowercase()}", 0xFFFFFF, "ground_${b.name.lowercase()}", roughness = 1.0)

    /** Malla del terreno de un sector: (16+1)^2 vértices, 2 triángulos por celda con diagonal (v00,v11). UV = 1 tesela por celda. */
    fun mesh(sector: SectorId, hf: HeightField): MeshBuilder {
        val mb = MeshBuilder(); val mat = groundMat(hf.config.biome); val cs = Grid.CELL_M
        fun p(i: Int, j: Int): DoubleArray {
            val vx = sector.x * Grid.SECTOR_CELLS + i; val vy = sector.y * Grid.SECTOR_CELLS + j
            return doubleArrayOf(vx * cs, hf.at(vx, vy), vy * cs)
        }
        val up = doubleArrayOf(0.0, 1.0, 0.0)
        for (j in 0 until Grid.SECTOR_CELLS) for (i in 0 until Grid.SECTOR_CELLS) {
            val a = p(i, j); val b = p(i + 1, j); val c = p(i + 1, j + 1); val d = p(i, j + 1)
            fun uv(ii: Int, jj: Int) = doubleArrayOf((sector.x * Grid.SECTOR_CELLS + ii).toDouble(), (sector.y * Grid.SECTOR_CELLS + jj).toDouble())
            mb.tri(mat, arrayOf(a, b, c), arrayOf(uv(i, j), uv(i + 1, j), uv(i + 1, j + 1)), up)
            mb.tri(mat, arrayOf(a, c, d), arrayOf(uv(i, j), uv(i + 1, j + 1), uv(i, j + 1)), up)
        }
        return mb
    }

    fun heightsJson(sector: SectorId, hf: HeightField): List<List<Double>> =
        (0..Grid.SECTOR_CELLS).map { j -> (0..Grid.SECTOR_CELLS).map { i -> hf.at(sector.x * Grid.SECTOR_CELLS + i, sector.y * Grid.SECTOR_CELLS + j) } }
}

object RoadMesher {
    fun roadMat(t: RoadType) = Mat("road_${t.name.lowercase()}", 0xFFFFFF, "road_${if (t == RoadType.DIRT) "dirt" else t.name.lowercase()}", roughness = 0.95)
    private fun patchRgb(t: RoadType) = when (t) { RoadType.DIRT -> 0x9A7248; RoadType.STREET -> 0x606064; RoadType.AVENUE -> 0x55585C; RoadType.HIGHWAY -> 0x4A4D52 }
    fun patchMat(t: RoadType) = Mat("roadpatch_${t.name.lowercase()}", patchRgb(t), null, roughness = 0.95)

    /** Puntos (x,y,z) de la curva del segmento: esquinas de la rejilla redondeadas con arcos y alturas del terreno. */
    fun curve(seg: RoadSegment, hf: HeightField): List<DoubleArray> {
        val xz = Curves.fillet(seg.nodes.map { Format.world(it) })
        return xz.map { doubleArrayOf(it[0], hf.atWorld(it[0], it[1]) + Format.ROAD_LIFT, it[1]) }
    }

    fun segmentMesh(seg: RoadSegment, hf: HeightField): MeshBuilder {
        val mb = MeshBuilder(); val mat = roadMat(seg.type); val half = seg.type.width / 2
        val pts = curve(seg, hf)
        // dirección por vértice = bisectriz de las aristas vecinas, para que la banda siga la curva sin huecos
        fun dir(i: Int): DoubleArray {
            val a = pts[maxOf(0, i - 1)]; val b = pts[minOf(pts.size - 1, i + 1)]
            val dx = b[0] - a[0]; val dz = b[2] - a[2]; val l = hypot(dx, dz).coerceAtLeast(1e-9)
            return doubleArrayOf(dx / l, dz / l)
        }
        var vAcc = 0.0
        for (i in 0 until pts.size - 1) {
            val p = pts[i]; val q = pts[i + 1]; val dp = dir(i); val dq = dir(i + 1)
            val len = hypot(q[0] - p[0], q[2] - p[2]); val v0 = vAcc / 8.0; vAcc += len; val v1 = vAcc / 8.0
            val ax = -dp[1] * half; val az = dp[0] * half; val bx = -dq[1] * half; val bz = dq[0] * half
            mb.quad(mat, arrayOf(doubleArrayOf(p[0] - ax, p[1], p[2] - az), doubleArrayOf(p[0] + ax, p[1], p[2] + az), doubleArrayOf(q[0] + bx, q[1], q[2] + bz), doubleArrayOf(q[0] - bx, q[1], q[2] - bz)),
                arrayOf(doubleArrayOf(0.0, v0), doubleArrayOf(1.0, v0), doubleArrayOf(1.0, v1), doubleArrayOf(0.0, v1)), doubleArrayOf(0.0, 1.0, 0.0))
        }
        return mb
    }

    /** Parche octogonal en nodos de intersección, extremo o giro (cubre huecos de las uniones). */
    fun nodePatch(v: Vtx, radius: Double, type: RoadType, hf: HeightField): MeshBuilder {
        val mb = MeshBuilder(); val mat = patchMat(type); val w = Format.world(v); val y = hf.at(v) + Format.ROAD_LIFT + 0.01
        val n = 10
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n
            mb.tri(mat, arrayOf(doubleArrayOf(w[0], y, w[1]), doubleArrayOf(w[0] + radius * cos(a0), y, w[1] + radius * sin(a0)), doubleArrayOf(w[0] + radius * cos(a1), y, w[1] + radius * sin(a1))),
                arrayOf(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.5, 0.5), doubleArrayOf(0.5, 0.5)), doubleArrayOf(0.0, 1.0, 0.0))
        }
        return mb
    }

    fun island(rb: Roundabout, hf: HeightField): MeshBuilder {
        val mb = MeshBuilder(); val grass = Mat("island_grass", 0x5E9E3F, null, roughness = 1.0); val curb = Mat("island_curb", 0xB8B4AA, null, roughness = 0.9)
        val cx = rb.cx * Grid.CELL_M; val cz = rb.cy * Grid.CELL_M
        val r = 2 * Grid.CELL_M - rb.type.width / 2 - 0.8
        val y0 = hf.at(rb.cx, rb.cy) + Format.ROAD_LIFT; val y1 = y0 + 0.3; val n = 14
        for (i in 0 until n) {
            val a0 = 2 * PI * i / n; val a1 = 2 * PI * (i + 1) / n; val am = (a0 + a1) / 2
            mb.tri(grass, arrayOf(doubleArrayOf(cx, y1, cz), doubleArrayOf(cx + r * cos(a0), y1, cz + r * sin(a0)), doubleArrayOf(cx + r * cos(a1), y1, cz + r * sin(a1))), arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)), doubleArrayOf(0.0, 1.0, 0.0))
            mb.quad(curb, arrayOf(doubleArrayOf(cx + r * cos(a0), y0, cz + r * sin(a0)), doubleArrayOf(cx + r * cos(a1), y0, cz + r * sin(a1)), doubleArrayOf(cx + r * cos(a1), y1, cz + r * sin(a1)), doubleArrayOf(cx + r * cos(a0), y1, cz + r * sin(a0))),
                arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)), doubleArrayOf(cos(am), 0.0, sin(am)))
        }
        return mb
    }
}
