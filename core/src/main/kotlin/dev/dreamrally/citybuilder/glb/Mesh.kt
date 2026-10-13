package dev.dreamrally.citybuilder.glb

import kotlin.math.sqrt

/** Material PBR sencillo. [texture] es el nombre (sin extensión) de textures/<nombre>.png, o null. */
data class Mat(val name: String, val rgb: Int, val texture: String? = null, val roughness: Double = 0.9, val metallic: Double = 0.0, val doubleSided: Boolean = false)

class FloatList { var a = FloatArray(256); var n = 0
    fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v } }
class IntList { var a = IntArray(256); var n = 0
    fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v } }

class Prim(val mat: Mat) {
    val pos = FloatList(); val nrm = FloatList(); val uv = FloatList(); val idx = IntList()
    val vertexCount get() = pos.n / 3
    fun vert(x: Double, y: Double, z: Double, nx: Double, ny: Double, nz: Double, u: Double, v: Double): Int {
        pos.add(x.toFloat()); pos.add(y.toFloat()); pos.add(z.toFloat())
        nrm.add(nx.toFloat()); nrm.add(ny.toFloat()); nrm.add(nz.toFloat()); uv.add(u.toFloat()); uv.add(v.toFloat())
        return vertexCount - 1
    }
}

/** Constructor de mallas con sombreado plano. Las caras se orientan automáticamente según una normal de referencia. */
class MeshBuilder {
    val prims = LinkedHashMap<String, Prim>()
    fun prim(m: Mat) = prims.getOrPut(m.name) { Prim(m) }
    val isEmpty get() = prims.values.all { it.idx.n == 0 }

    /** Cuadrilátero p0..p3 (en orden de contorno). [hint] indica el lado exterior; se reordena para que el frente CCW mire hacia allí. */
    fun quad(m: Mat, p: Array<DoubleArray>, uvs: Array<DoubleArray>, hint: DoubleArray) {
        val pr = prim(m)
        var order = intArrayOf(0, 1, 2, 3)
        val n = normal(p[0], p[1], p[2])
        if (dot(n, hint) < 0) order = intArrayOf(0, 3, 2, 1)
        val nn = if (dot(n, hint) < 0) neg(n) else n
        val ids = order.map { pr.vert(p[it][0], p[it][1], p[it][2], nn[0], nn[1], nn[2], uvs[it][0], uvs[it][1]) }
        pr.idx.add(ids[0]); pr.idx.add(ids[1]); pr.idx.add(ids[2])
        pr.idx.add(ids[0]); pr.idx.add(ids[2]); pr.idx.add(ids[3])
    }

    fun tri(m: Mat, p: Array<DoubleArray>, uvs: Array<DoubleArray>, hint: DoubleArray) {
        val pr = prim(m)
        var order = intArrayOf(0, 1, 2)
        val n = normal(p[0], p[1], p[2])
        if (dot(n, hint) < 0) order = intArrayOf(0, 2, 1)
        val nn = if (dot(n, hint) < 0) neg(n) else n
        val ids = order.map { pr.vert(p[it][0], p[it][1], p[it][2], nn[0], nn[1], nn[2], uvs[it][0], uvs[it][1]) }
        ids.forEach { pr.idx.add(it) }
    }

    companion object {
        fun normal(a: DoubleArray, b: DoubleArray, c: DoubleArray): DoubleArray {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            val x = uy * vz - uz * vy; val y = uz * vx - ux * vz; val z = ux * vy - uy * vx
            val l = sqrt(x * x + y * y + z * z).let { if (it < 1e-12) 1.0 else it }
            return doubleArrayOf(x / l, y / l, z / l)
        }
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun neg(a: DoubleArray) = doubleArrayOf(-a[0], -a[1], -a[2])
    }
}
