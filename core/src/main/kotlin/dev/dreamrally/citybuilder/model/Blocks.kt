package dev.dreamrally.citybuilder.model

/** Parcela: rectángulo de celdas libre de carreteras dentro de una manzana. frontRot: lado de la calle (0 sur, 1 este, 2 norte, 3 oeste) o null si no da a ninguna. */
data class Parcel(val id: String, val blockId: String, val x: Int, val y: Int, val w: Int, val d: Int, val frontRot: Int?, val occupiedBy: String?) {
    val cells get() = City.footprintCells(x, y, w, d)
}

/** Manzana: componente conexa de celdas edificables delimitada por la red vial. [open]=true si no queda cerrada por calles (toca terreno bloqueado/límite de la ventana). */
data class Block(val id: String, val cellCount: Int, val open: Boolean, val bounds: IntArray, val parcels: List<Parcel>)

class BlockMap(val blocks: List<Block>) {
    val parcels: List<Parcel> = blocks.flatMap { it.parcels }
    private val byCell = HashMap<Long, Parcel>().also { m -> parcels.forEach { p -> p.cells.forEach { (cx, cy) -> m[key(cx, cy)] = p } } }
    fun parcelAt(x: Int, y: Int): Parcel? = byCell[key(x, y)]
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
}

/**
 * Deriva manzanas y parcelas SOLO de la geometría vial (no hay polígonos fijos):
 * 1. Celdas edificables = desbloqueadas, sin diagonal vial, fuera de islas de rotonda.
 * 2. Manzana = componente 4-conexa; una arista vial entre dos celdas las separa.
 * 3. Parcelas = lotes de hasta 3×3 pegados a la calle (frente = lado con calle), luego rellenos interiores sin frente.
 */
object BlockBuilder {
    const val LOT = 3
    private val AX = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(0, 1))      // eje a lo largo del frente
    private val DD = arrayOf(intArrayOf(0, -1), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(1, 0))    // profundidad (alejándose de la calle)

    fun build(
        rect: IntArray, // minx, miny, maxx, maxy (celdas, inclusive)
        hasEdge: (Vtx, Vtx) -> Boolean,
        cellOk: (Int, Int) -> Boolean,
        reserved: (Int, Int) -> Boolean,
        buildingAt: (Int, Int) -> String?
    ): BlockMap {
        val (x0, y0, x1, y1) = rect.toList()
        fun inRect(x: Int, y: Int) = x in x0..x1 && y in y0..y1
        fun diag(x: Int, y: Int) = hasEdge(Vtx(x, y), Vtx(x + 1, y + 1)) || hasEdge(Vtx(x + 1, y), Vtx(x, y + 1))
        fun free(x: Int, y: Int) = inRect(x, y) && cellOk(x, y) && !diag(x, y) && !reserved(x, y)
        fun sideRoad(x: Int, y: Int, s: Int): Boolean = when (s) {
            0 -> hasEdge(Vtx(x, y + 1), Vtx(x + 1, y + 1)) || (inRect(x, y + 1) && diag(x, y + 1))
            1 -> hasEdge(Vtx(x + 1, y), Vtx(x + 1, y + 1)) || (inRect(x + 1, y) && diag(x + 1, y))
            2 -> hasEdge(Vtx(x, y), Vtx(x + 1, y)) || (inRect(x, y - 1) && diag(x, y - 1))
            else -> hasEdge(Vtx(x, y), Vtx(x, y + 1)) || (inRect(x - 1, y) && diag(x - 1, y))
        }
        fun blockedAxis(x: Int, y: Int, dx: Int, dy: Int): Boolean = when {      // arista vial entre (x,y) y (x+dx,y+dy)
            dx == 1 -> hasEdge(Vtx(x + 1, y), Vtx(x + 1, y + 1)); dx == -1 -> hasEdge(Vtx(x, y), Vtx(x, y + 1))
            dy == 1 -> hasEdge(Vtx(x, y + 1), Vtx(x + 1, y + 1)); else -> hasEdge(Vtx(x, y), Vtx(x + 1, y))
        }
        val comp = HashMap<Long, Int>()
        fun k(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
        val blocks = ArrayList<Block>()
        var compId = 0
        for (y in y0..y1) for (x in x0..x1) {
            if (!free(x, y) || comp.containsKey(k(x, y))) continue
            val id = compId++; val cells = ArrayList<IntArray>(); var open = false
            val q = ArrayDeque<IntArray>(); q.add(intArrayOf(x, y)); comp[k(x, y)] = id
            while (q.isNotEmpty()) {
                val c = q.removeFirst(); cells += c
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    if (blockedAxis(c[0], c[1], dx, dy)) continue
                    val nx = c[0] + dx; val ny = c[1] + dy
                    if (!inRect(nx, ny) || !cellOk(nx, ny)) { open = true; continue }
                    if (!free(nx, ny)) continue            // diagonal vial / isla: actúa como pared
                    if (comp.putIfAbsent(k(nx, ny), id) == null) q.add(intArrayOf(nx, ny))
                }
            }
            val set = HashSet<Long>().also { s -> cells.forEach { s += k(it[0], it[1]) } }
            val first = cells.minWith(compareBy({ it[1] }, { it[0] }))
            val blockId = "blk_${first[0]}_${first[1]}"
            val assigned = HashSet<Long>(); val parcels = ArrayList<Parcel>()
            fun primary(c: IntArray): Int = (0..3).firstOrNull { sideRoad(c[0], c[1], it) } ?: -1
            fun addParcel(px: Int, py: Int, w: Int, d: Int, front: Int?) {
                for (i in 0 until w) for (j in 0 until d) assigned += k(px + i, py + j)
                val occ = City.footprintCells(px, py, w, d).firstNotNullOfOrNull { (cx, cy) -> buildingAt(cx, cy) }
                parcels += Parcel("p_${px}_$py", blockId, px, py, w, d, front, occ)
            }
            val ordered = cells.sortedWith(compareBy({ it[1] }, { it[0] }))
            for (c in ordered) {                         // lotes con frente
                if (k(c[0], c[1]) in assigned) continue
                val f = primary(c); if (f < 0) continue
                val a = AX[f]; val dd = DD[f]; var len = 1
                while (len < LOT) { val nx = c[0] + a[0] * len; val ny = c[1] + a[1] * len
                    if (k(nx, ny) in set && k(nx, ny) !in assigned && primary(intArrayOf(nx, ny)) == f) len++ else break }
                var depth = 1
                while (depth < LOT) { val ok = (0 until len).all { i -> val nx = c[0] + a[0] * i + dd[0] * depth; val ny = c[1] + a[1] * i + dd[1] * depth; k(nx, ny) in set && k(nx, ny) !in assigned }
                    if (ok) depth++ else break }
                val cxs = (0 until len).flatMap { i -> (0 until depth).map { j -> c[0] + a[0] * i + dd[0] * j } }
                val cys = (0 until len).flatMap { i -> (0 until depth).map { j -> c[1] + a[1] * i + dd[1] * j } }
                addParcel(cxs.min(), cys.min(), cxs.max() - cxs.min() + 1, cys.max() - cys.min() + 1, f)
            }
            for (c in ordered) {                         // interior sin frente
                if (k(c[0], c[1]) in assigned) continue
                var w = 1; while (w < LOT && k(c[0] + w, c[1]) in set && k(c[0] + w, c[1]) !in assigned) w++
                var d = 1; while (d < LOT && (0 until w).all { i -> k(c[0] + i, c[1] + d) in set && k(c[0] + i, c[1] + d) !in assigned }) d++
                addParcel(c[0], c[1], w, d, null)
            }
            blocks += Block(blockId, cells.size, open, intArrayOf(cells.minOf { it[0] }, cells.minOf { it[1] }, cells.maxOf { it[0] }, cells.maxOf { it[1] }), parcels.sortedBy { it.id })
        }
        return BlockMap(blocks)
    }
}
