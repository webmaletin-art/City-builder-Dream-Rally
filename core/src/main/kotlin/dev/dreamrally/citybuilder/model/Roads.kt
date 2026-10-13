package dev.dreamrally.citybuilder.model

import kotlinx.serialization.Serializable

@Serializable
enum class RoadType(val label: String, val width: Double, val lanes: Int, val speedKmh: Int, val roadClass: String, val rgb: Int) {
    DIRT("Camino de tierra", 5.0, 1, 30, "track", 0x9C7B52),
    STREET("Calle", 6.0, 2, 40, "residential", 0x5A5E66),
    AVENUE("Avenida", 8.0, 4, 60, "primary", 0x4B4F57),
    HIGHWAY("Autopista", 8.0, 4, 100, "motorway", 0x3C3F46)
}

/**
 * Arista elemental de la red vial: une dos vértices de la rejilla contiguos (8-vecindad).
 * a < b siempre (orden canónico). oneWay: 0 = doble sentido, 1 = a->b, -1 = b->a.
 * El id es determinista y estable: "e_<ax>_<ay>_<bx>_<by>".
 */
@Serializable
data class RoadEdge(
    val a: Vtx,
    val b: Vtx,
    val type: RoadType = RoadType.STREET,
    val oneWay: Int = 0,
    val group: String? = null // p.ej. id de rotonda
) {
    val id: String get() = "e_${a.x}_${a.y}_${b.x}_${b.y}"
    val isDiagonal: Boolean get() = a.x != b.x && a.y != b.y
    val sector: SectorId get() = a.sector

    companion object {
        fun of(p: Vtx, q: Vtx, type: RoadType, oneWay: Int = 0, group: String? = null): RoadEdge {
            require(kotlin.math.abs(p.x - q.x) <= 1 && kotlin.math.abs(p.y - q.y) <= 1 && p != q) { "arista no elemental" }
            return if (p < q) RoadEdge(p, q, type, oneWay, group) else RoadEdge(q, p, type, -oneWay, group)
        }
        fun key(p: Vtx, q: Vtx): String = if (p < q) "${p.x},${p.y},${q.x},${q.y}" else "${q.x},${q.y},${p.x},${p.y}"
    }
    val key: String get() = key(a, b)
}

@Serializable
data class Roundabout(val id: String, val cx: Int, val cy: Int, val type: RoadType)
