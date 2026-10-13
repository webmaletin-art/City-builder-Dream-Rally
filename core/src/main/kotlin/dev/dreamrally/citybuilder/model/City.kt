package dev.dreamrally.citybuilder.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class PlacedBuilding(val id: String, val catalogId: String, val x: Int, val y: Int, val rot: Int = 0) {
    val sector: SectorId get() = Grid.sectorKey(x, y)
}

/** Datos de un sector (unidad de guardado incremental, carga diferida y exportación por lotes). */
@Serializable
data class SectorData(
    val id: SectorId,
    val buildings: List<PlacedBuilding> = emptyList(),
    val edges: List<RoadEdge> = emptyList()
)

@Serializable
data class CityConfig(
    val cityId: String,
    val name: String,
    val biome: Biome = Biome.TEMPERATE,
    val relief: Relief = Relief.FLAT,
    val reliefSeed: Int = 1234,
    val maxSectors: Int = 400,
    val unlocked: Set<SectorId> = setOf(SectorId(-1, -1), SectorId(0, -1), SectorId(-1, 0), SectorId(0, 0)),
    val nextBuildingId: Int = 1,
    val nextRoundaboutId: Int = 1,
    val roundabouts: List<Roundabout> = emptyList()
)

/** Origen de datos de sectores bajo demanda (disco). Null => todo en memoria. */
interface SectorSource {
    fun loadSector(id: SectorId): SectorData?
}

sealed class Op {
    data class AddBuilding(val b: PlacedBuilding) : Op()
    data class RemoveBuilding(val b: PlacedBuilding) : Op()
    data class AddEdge(val e: RoadEdge) : Op()
    data class RemoveEdge(val e: RoadEdge) : Op()
    data class SetConfig(val before: CityConfig, val after: CityConfig) : Op()
}

data class Outcome(val ok: Boolean, val message: String = "", val warnings: List<String> = emptyList(), val id: String? = null)

/**
 * Estado editable de la ciudad. Los sectores se cargan bajo demanda ([ensureLoaded]) y se liberan con [trim].
 * Toda edición pasa por [apply] para alimentar deshacer/rehacer y marcar sectores sucios.
 */
class City(
    var config: CityConfig,
    val catalog: Catalog,
    private val source: SectorSource? = null
) {
    private val sectors = HashMap<SectorId, SectorData>()
    private val buildingsById = HashMap<String, PlacedBuilding>()
    private val occupancy = HashMap<Long, String>()          // celda -> id edificio (índice espacial por celda)
    private val edgesByKey = HashMap<String, RoadEdge>()
    private val adjacency = HashMap<Vtx, MutableSet<String>>() // vértice -> claves de aristas
    val dirty = HashSet<SectorId>()
    var configDirty = false
    private val undoStack = ArrayDeque<List<Op>>()
    private val redoStack = ArrayDeque<List<Op>>()
    var revision = 0L; private set

    val loadedSectors: Set<SectorId> get() = sectors.keys
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // ---------- carga diferida ----------
    fun ensureLoaded(ids: Collection<SectorId>) {
        for (id in ids) {
            if (id in sectors || id !in config.unlocked) continue
            val data = source?.loadSector(id) ?: SectorData(id)
            install(data)
        }
    }

    /** Instala sectores completos (p. ej. al importar). */
    fun install(data: SectorData) {
        sectors[data.id] = data.copy()
        data.buildings.forEach { index(it) }
        data.edges.forEach { indexEdge(it) }
    }

    /** Libera los sectores cargados que no estén en [keep] ni sucios. Devuelve cuántos liberó. */
    fun trim(keep: Set<SectorId>): Int {
        val drop = sectors.keys.filter { it !in keep && it !in dirty }
        drop.forEach { evict(it) }
        return drop.size
    }

    private fun evict(id: SectorId) {
        val s = sectors.remove(id) ?: return
        s.buildings.forEach { deindex(it) }
        s.edges.forEach { deindexEdge(it) }
    }

    fun clearMemory() { sectors.clear(); buildingsById.clear(); occupancy.clear(); edgesByKey.clear(); adjacency.clear() }

    // ---------- consultas ----------
    fun building(id: String) = buildingsById[id]
    fun buildings(): Collection<PlacedBuilding> = buildingsById.values
    fun edges(): Collection<RoadEdge> = edgesByKey.values
    fun edge(a: Vtx, b: Vtx) = edgesByKey[RoadEdge.key(a, b)]
    fun edgesAt(v: Vtx): List<RoadEdge> = adjacency[v]?.mapNotNull { edgesByKey[it] } ?: emptyList()
    fun buildingAt(cx: Int, cy: Int): PlacedBuilding? = occupancy[cell(cx, cy)]?.let { buildingsById[it] }
    fun isUnlockedCell(cx: Int, cy: Int) = Grid.sectorKey(cx, cy) in config.unlocked
    /** Un vértice pertenece al sector de la celda (x, y); así cada arista vive siempre en un sector desbloqueado. */
    fun isUnlockedVertex(v: Vtx) = v.sector in config.unlocked

    /** Edificios cuya huella cae en el rectángulo de celdas (consulta espacial). */
    fun buildingsIn(x0: Int, y0: Int, x1: Int, y1: Int): List<PlacedBuilding> {
        val out = LinkedHashSet<PlacedBuilding>()
        for (cx in x0..x1) for (cy in y0..y1) buildingAt(cx, cy)?.let { out.add(it) }
        return out.toList()
    }

    fun edgesIn(x0: Int, y0: Int, x1: Int, y1: Int): List<RoadEdge> =
        edgesByKey.values.filter { it.a.x in (x0 - 1)..(x1 + 1) && it.a.y in (y0 - 1)..(y1 + 1) }

    /** Vértices viales con al menos [n] aristas dentro del rectángulo (intersecciones a dibujar). */
    fun nodesWithDegreeAtLeast(n: Int, x0: Int, y0: Int, x1: Int, y1: Int): List<Vtx> =
        adjacency.entries.filter { it.value.size >= n && it.key.x in x0..x1 && it.key.y in y0..y1 }.map { it.key }

    fun footprint(b: PlacedBuilding): List<Pair<Int, Int>> {
        val spec = catalog[b.catalogId] ?: return listOf(b.x to b.y)
        return footprintCells(b.x, b.y, spec.footprintW(b.rot), spec.footprintD(b.rot))
    }

    // ---------- edificios ----------
    fun canPlace(spec: BuildingSpec, x: Int, y: Int, rot: Int, ignoreId: String? = null): Outcome {
        val w = spec.footprintW(rot); val d = spec.footprintD(rot)
        ensureLoaded(sectorsTouching(x, y, w, d))
        for ((cx, cy) in footprintCells(x, y, w, d)) {
            if (!isUnlockedCell(cx, cy)) return Outcome(false, "Fuera del terreno desbloqueado")
            val o = occupancy[cell(cx, cy)]
            if (o != null && o != ignoreId) return Outcome(false, "Ocupado por otro edificio")
        }
        if (edgeCrossesFootprint(x, y, w, d)) return Outcome(false, "Hay una carretera en el solar")
        for (rb in config.roundabouts) if (x < rb.cx + 2 && x + w > rb.cx - 2 && y < rb.cy + 2 && y + d > rb.cy - 2) return Outcome(false, "Es parte de una rotonda")
        val warns = ArrayList<String>()
        if (spec.placement.needsRoad && !touchesRoad(x, y, w, d)) warns += "Sin conexión a una carretera"
        return Outcome(true, warnings = warns)
    }

    /**
     * Giro automático "de frente a la calle" (comportamiento observable en constructores urbanos): el frente del
     * modelo es su lado +Z local; con giro r mira a sur/este/norte/oeste. Devuelve el giro que deja el frente
     * pegado a una carretera (prefiere [current]); si ninguno lo logra, devuelve [current].
     */
    fun bestRotation(spec: BuildingSpec, x: Int, y: Int, current: Int): Int {
        ensureLoaded(sectorsTouching(x, y, spec.widthCells, spec.depthCells))
        val order = listOf(current and 3, (current + 1) and 3, (current + 2) and 3, (current + 3) and 3)
        return order.firstOrNull { frontTouchesRoad(x, y, spec.footprintW(it), spec.footprintD(it), it) && canPlace(spec, x, y, it).ok } ?: (current and 3)
    }

    fun frontTouchesRoad(x: Int, y: Int, w: Int, d: Int, rot: Int): Boolean = when (rot and 3) {
        0 -> (x until x + w).any { edge(Vtx(it, y + d), Vtx(it + 1, y + d)) != null }       // sur
        1 -> (y until y + d).any { edge(Vtx(x + w, it), Vtx(x + w, it + 1)) != null }       // este
        2 -> (x until x + w).any { edge(Vtx(it, y), Vtx(it + 1, y)) != null }               // norte
        else -> (y until y + d).any { edge(Vtx(x, it), Vtx(x, it + 1)) != null }            // oeste
    }

    fun placeBuilding(catalogId: String, x: Int, y: Int, rot: Int = 0): Outcome {
        val spec = catalog[catalogId] ?: return Outcome(false, "Edificio desconocido: $catalogId")
        val r = ((rot % 4) + 4) % 4
        val chk = canPlace(spec, x, y, r)
        if (!chk.ok) return chk
        val id = "b${config.nextBuildingId}"
        val b = PlacedBuilding(id, catalogId, x, y, r)
        apply(listOf(Op.SetConfig(config, config.copy(nextBuildingId = config.nextBuildingId + 1)), Op.AddBuilding(b)))
        return Outcome(true, "Colocado", chk.warnings, id)
    }

    fun removeBuilding(id: String): Outcome {
        val b = buildingsById[id] ?: return Outcome(false, "No existe")
        apply(listOf(Op.RemoveBuilding(b))); return Outcome(true)
    }

    fun moveBuilding(id: String, x: Int, y: Int, rot: Int? = null): Outcome {
        val b = buildingsById[id] ?: return Outcome(false, "No existe")
        val spec = catalog[b.catalogId] ?: return Outcome(false, "Edificio desconocido")
        val r = ((rot ?: b.rot) % 4 + 4) % 4
        val chk = canPlace(spec, x, y, r, ignoreId = id)
        if (!chk.ok) return chk
        apply(listOf(Op.RemoveBuilding(b), Op.AddBuilding(b.copy(x = x, y = y, rot = r)))); return Outcome(true, warnings = chk.warnings, id = id)
    }

    fun rotateBuilding(id: String): Outcome {
        val b = buildingsById[id] ?: return Outcome(false, "No existe")
        return moveBuilding(id, b.x, b.y, b.rot + 1)
    }

    /** Reemplaza el edificio por otro del catálogo conservando la posición (misma huella o libre). */
    fun replaceBuilding(id: String, newCatalogId: String): Outcome {
        val b = buildingsById[id] ?: return Outcome(false, "No existe")
        val spec = catalog[newCatalogId] ?: return Outcome(false, "Edificio desconocido")
        val chk = canPlace(spec, b.x, b.y, b.rot, ignoreId = id)
        if (!chk.ok) return chk
        apply(listOf(Op.RemoveBuilding(b), Op.AddBuilding(b.copy(catalogId = newCatalogId)))); return Outcome(true, warnings = chk.warnings, id = id)
    }

    // ---------- carreteras ----------
    /** Valida una arista elemental contra terreno, edificios y cruces diagonales. */
    fun canBuildEdge(p: Vtx, q: Vtx): Outcome {
        if (abs(p.x - q.x) > 1 || abs(p.y - q.y) > 1 || p == q) return Outcome(false, "Tramo no elemental")
        ensureLoaded(listOf(p.sector, q.sector) + p.sector.neighbors())
        if (!isUnlockedVertex(p) || !isUnlockedVertex(q)) return Outcome(false, "Fuera del terreno desbloqueado")
        val e = RoadEdge.of(p, q, RoadType.STREET)
        if (e.isDiagonal) {
            val cx = minOf(p.x, q.x); val cy = minOf(p.y, q.y)
            if (!isUnlockedCell(cx, cy)) return Outcome(false, "Fuera del terreno desbloqueado")
            if (buildingAt(cx, cy) != null) return Outcome(false, "Edificio en el camino")
            // la diagonal contraria de la misma celda produciría un cruce sin intersección
            val other = if (e.a.x < e.b.x && e.a.y < e.b.y) RoadEdge.key(Vtx(cx + 1, cy), Vtx(cx, cy + 1)) else RoadEdge.key(Vtx(cx, cy), Vtx(cx + 1, cy + 1))
            if (edgesByKey.containsKey(other)) return Outcome(false, "Cruce sin intersección (diagonales opuestas)")
        } else if (e.a.y == e.b.y) { // horizontal: separa celdas (x, y-1) y (x, y)
            val bt = buildingAt(e.a.x, e.a.y - 1); val bb = buildingAt(e.a.x, e.a.y)
            if (bt != null && bt.id == bb?.id) return Outcome(false, "Edificio en el camino")
        } else {
            val bl = buildingAt(e.a.x - 1, e.a.y); val br = buildingAt(e.a.x, e.a.y)
            if (bl != null && bl.id == br?.id) return Outcome(false, "Edificio en el camino")
        }
        return Outcome(true)
    }

    /**
     * Dibuja una carretera siguiendo [path] (vértices de rejilla). Los saltos mayores a 1 se rellenan con pasos
     * elementales. Tramos inválidos se omiten y se informan; la red resultante conserva conexiones e intersecciones
     * porque los vértices son nodos compartidos.
     */
    fun drawRoad(path: List<Vtx>, type: RoadType, oneWay: Int = 0): Outcome {
        val steps = expandPath(path)
        if (steps.isEmpty()) return Outcome(false, "Trazado vacío")
        val ops = ArrayList<Op>(); val skipped = ArrayList<String>(); var added = 0
        for ((p, q) in steps) {
            val chk = canBuildEdge(p, q)
            if (!chk.ok) { skipped += "${p.id}->${q.id}: ${chk.message}"; continue }
            val e = RoadEdge.of(p, q, type, oneWay)
            val old = edgesByKey[e.key]
            if (old == e) continue
            if (old != null) ops += Op.RemoveEdge(old)
            ops += Op.AddEdge(e); added++
        }
        if (ops.isEmpty()) return Outcome(false, if (skipped.isNotEmpty()) skipped.first() else "Sin cambios", skipped)
        apply(ops)
        return Outcome(true, "$added tramos", skipped)
    }

    fun removeRoadAlong(path: List<Vtx>): Outcome {
        val ops = expandPath(path).mapNotNull { (p, q) -> edgesByKey[RoadEdge.key(p, q)]?.let { Op.RemoveEdge(it) } }
        if (ops.isEmpty()) return Outcome(false, "No hay carretera ahí")
        apply(ops); return Outcome(true, "${ops.size} tramos eliminados")
    }

    fun removeEdgesNear(v: Vtx): Outcome {
        val ops = edgesAt(v).map { Op.RemoveEdge(it) }
        if (ops.isEmpty()) return Outcome(false, "No hay carretera ahí"); apply(ops); return Outcome(true)
    }

    /** Reconstruye (cambia tipo) la cadena de aristas conectada a [start] hasta la siguiente intersección. */
    fun retypeSegment(start: RoadEdge, type: RoadType): Outcome {
        val chain = chainFrom(start)
        val ops = chain.filter { it.type != type }.flatMap { listOf(Op.RemoveEdge(it), Op.AddEdge(it.copy(type = type))) }
        if (ops.isEmpty()) return Outcome(false, "Sin cambios"); apply(ops); return Outcome(true, "${chain.size} tramos")
    }

    fun removeSegment(start: RoadEdge): Outcome {
        val chain = chainFrom(start); apply(chain.map { Op.RemoveEdge(it) }); return Outcome(true, "${chain.size} tramos eliminados")
    }

    /** Tramos consecutivos hasta nodos con grado != 2 o cambio de tipo/grupo (mismo criterio que la exportación). */
    fun chainFrom(start: RoadEdge): List<RoadEdge> {
        val out = LinkedHashMap<String, RoadEdge>(); out[start.key] = start
        for (dir in 0..1) {
            var cur = start; var node = if (dir == 0) start.b else start.a
            while (true) {
                val es = edgesAt(node)
                if (es.size != 2) break
                val next = es.first { it.key != cur.key }
                if (next.type != start.type || next.group != start.group || out.containsKey(next.key)) break
                out[next.key] = next; node = if (next.a == node) next.b else next.a; cur = next
            }
        }
        return out.values.toList()
    }

    /** Rotonda de 8 lados centrada en el vértice (cx, cy), sentido único antihorario (conducción por la derecha). */
    fun addRoundabout(cx: Int, cy: Int, type: RoadType = RoadType.STREET): Outcome {
        val ring = listOf(
            Vtx(cx - 1, cy - 2), Vtx(cx, cy - 2), Vtx(cx + 1, cy - 2), Vtx(cx + 2, cy - 1), Vtx(cx + 2, cy), Vtx(cx + 2, cy + 1),
            Vtx(cx + 1, cy + 2), Vtx(cx, cy + 2), Vtx(cx - 1, cy + 2), Vtx(cx - 2, cy + 1), Vtx(cx - 2, cy), Vtx(cx - 2, cy - 1)
        )
        // La lista va en sentido horario en pantalla (y hacia abajo); antihorario = recorrer al revés.
        val ccw = ring.reversed()
        val id = "rb${config.nextRoundaboutId}"
        val ops = ArrayList<Op>()
        for (i in ccw.indices) {
            val p = ccw[i]; val q = ccw[(i + 1) % ccw.size]
            val chk = canBuildEdge(p, q)
            if (!chk.ok) return Outcome(false, "No se puede construir la rotonda: ${chk.message}")
            val old = edgesByKey[RoadEdge.key(p, q)]
            if (old != null) ops += Op.RemoveEdge(old)
            ops += Op.AddEdge(RoadEdge.of(p, q, type, oneWay = 1, group = id))
        }
        // El interior debe estar libre de edificios (isla central)
        for (x in cx - 1 until cx + 1) for (y in cy - 1 until cy + 1) if (buildingAt(x, y) != null) return Outcome(false, "Edificio en la isla central")
        val cfg = config.copy(nextRoundaboutId = config.nextRoundaboutId + 1, roundabouts = config.roundabouts + Roundabout(id, cx, cy, type))
        apply(listOf(Op.SetConfig(config, cfg)) + ops)
        return Outcome(true, "Rotonda $id", id = id)
    }

    // ---------- terreno ----------
    fun expandable(): List<SectorId> =
        config.unlocked.flatMap { s -> listOf(SectorId(s.x + 1, s.y), SectorId(s.x - 1, s.y), SectorId(s.x, s.y + 1), SectorId(s.x, s.y - 1)) }
            .filter { it !in config.unlocked }.distinct()

    fun unlockSector(id: SectorId): Outcome {
        if (id in config.unlocked) return Outcome(false, "Ya desbloqueado")
        if (config.unlocked.size >= config.maxSectors) return Outcome(false, "Límite de sectores del proyecto alcanzado (${config.maxSectors})")
        if (id !in expandable()) return Outcome(false, "Debe ser adyacente al terreno actual")
        apply(listOf(Op.SetConfig(config, config.copy(unlocked = config.unlocked + id)))); return Outcome(true)
    }

    fun updateConfig(transform: (CityConfig) -> CityConfig) { apply(listOf(Op.SetConfig(config, transform(config)))) }

    // ---------- deshacer / rehacer ----------
    fun undo(): Boolean {
        val ops = undoStack.removeLastOrNull() ?: return false
        ops.asReversed().forEach { applyOp(inverse(it)) }; redoStack.addLast(ops); revision++; return true
    }

    fun redo(): Boolean {
        val ops = redoStack.removeLastOrNull() ?: return false
        ops.forEach { applyOp(it) }; undoStack.addLast(ops); revision++; return true
    }

    private fun apply(ops: List<Op>) {
        ops.forEach { applyOp(it) }
        undoStack.addLast(ops); if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear(); revision++
    }

    private fun inverse(op: Op): Op = when (op) {
        is Op.AddBuilding -> Op.RemoveBuilding(op.b)
        is Op.RemoveBuilding -> Op.AddBuilding(op.b)
        is Op.AddEdge -> Op.RemoveEdge(op.e)
        is Op.RemoveEdge -> Op.AddEdge(op.e)
        is Op.SetConfig -> Op.SetConfig(op.after, op.before)
    }

    private fun applyOp(op: Op) {
        when (op) {
            is Op.AddBuilding -> { ensureSector(op.b.sector); sectors[op.b.sector] = sectors[op.b.sector]!!.let { it.copy(buildings = it.buildings + op.b) }; index(op.b); dirty += op.b.sector }
            is Op.RemoveBuilding -> { val s = op.b.sector; ensureSector(s); sectors[s] = sectors[s]!!.let { it.copy(buildings = it.buildings.filter { x -> x.id != op.b.id }) }; deindex(op.b); dirty += s }
            is Op.AddEdge -> { val s = op.e.sector; ensureSector(s); sectors[s] = sectors[s]!!.let { it.copy(edges = it.edges + op.e) }; indexEdge(op.e); dirty += s }
            is Op.RemoveEdge -> { val s = op.e.sector; ensureSector(s); sectors[s] = sectors[s]!!.let { it.copy(edges = it.edges.filter { x -> x.key != op.e.key }) }; deindexEdge(op.e); dirty += s }
            is Op.SetConfig -> { config = op.after; configDirty = true }
        }
    }

    private fun ensureSector(id: SectorId) {
        if (id in sectors) return
        val data = source?.loadSector(id) ?: SectorData(id)
        install(data)
    }

    /** Lee un sector sin cargarlo en memoria (usa el cargado si existe, para incluir ediciones sin guardar). */
    fun readSector(id: SectorId): SectorData? = sectors[id] ?: source?.loadSector(id)

    /** Snapshot de un sector cargado (para guardar). */
    fun sectorData(id: SectorId): SectorData? = sectors[id]
    fun markClean(ids: Collection<SectorId>) { dirty.removeAll(ids.toSet()); configDirty = false }

    // ---------- índices ----------
    private fun cell(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

    private fun index(b: PlacedBuilding) {
        buildingsById[b.id] = b
        footprint(b).forEach { (cx, cy) -> occupancy[cell(cx, cy)] = b.id }
    }

    private fun deindex(b: PlacedBuilding) {
        buildingsById.remove(b.id)
        footprint(b).forEach { (cx, cy) -> if (occupancy[cell(cx, cy)] == b.id) occupancy.remove(cell(cx, cy)) }
    }

    private fun indexEdge(e: RoadEdge) {
        edgesByKey[e.key] = e
        adjacency.getOrPut(e.a) { HashSet() }.add(e.key); adjacency.getOrPut(e.b) { HashSet() }.add(e.key)
    }

    private fun deindexEdge(e: RoadEdge) {
        edgesByKey.remove(e.key)
        adjacency[e.a]?.let { it.remove(e.key); if (it.isEmpty()) adjacency.remove(e.a) }
        adjacency[e.b]?.let { it.remove(e.key); if (it.isEmpty()) adjacency.remove(e.b) }
    }

    private fun sectorsTouching(x: Int, y: Int, w: Int, d: Int): Set<SectorId> =
        setOf(Grid.sectorKey(x, y), Grid.sectorKey(x + w - 1, y), Grid.sectorKey(x, y + d - 1), Grid.sectorKey(x + w - 1, y + d - 1))
            .flatMap { it.neighbors() }.toSet()

    private fun edgeCrossesFootprint(x: Int, y: Int, w: Int, d: Int): Boolean {
        val inside = { cx: Int, cy: Int -> cx in x until x + w && cy in y until y + d }
        for (e in edgesIn(x, y, x + w, y + d)) {
            if (e.isDiagonal) {
                if (inside(minOf(e.a.x, e.b.x), minOf(e.a.y, e.b.y))) return true
            } else if (e.a.y == e.b.y) {
                if (inside(e.a.x, e.a.y - 1) && inside(e.a.x, e.a.y)) return true
            } else if (inside(e.a.x - 1, e.a.y) && inside(e.a.x, e.a.y)) return true
        }
        return false
    }

    private fun touchesRoad(x: Int, y: Int, w: Int, d: Int): Boolean {
        for (vx in x..x + w) for (vy in y..y + d) {
            val onBorder = vx == x || vx == x + w || vy == y || vy == y + d
            if (onBorder && adjacency.containsKey(Vtx(vx, vy))) return true
        }
        return false
    }

    companion object {
        fun footprintCells(x: Int, y: Int, w: Int, d: Int): List<Pair<Int, Int>> =
            (0 until w).flatMap { i -> (0 until d).map { j -> (x + i) to (y + j) } }

        /** Expande un trazado de vértices en pasos elementales (8-vecindad), rellenando saltos con una línea de Bresenham. */
        fun expandPath(path: List<Vtx>): List<Pair<Vtx, Vtx>> {
            val out = ArrayList<Pair<Vtx, Vtx>>()
            for (i in 0 until path.size - 1) {
                var cur = path[i]; val end = path[i + 1]
                while (cur != end) {
                    val nx = cur.x + Integer.signum(end.x - cur.x); val ny = cur.y + Integer.signum(end.y - cur.y)
                    val dx = abs(end.x - cur.x); val dy = abs(end.y - cur.y)
                    // prefiere pasos rectos cuando una componente domina (evita diagonales accidentales en trazos casi rectos)
                    val next = when {
                        dx >= 2 * dy -> Vtx(nx, cur.y)
                        dy >= 2 * dx -> Vtx(cur.x, ny)
                        else -> Vtx(nx, ny)
                    }
                    out += cur to next; cur = next
                }
            }
            return out
        }
    }
}
