package dev.dreamrally.citybuilder.model

import kotlinx.serialization.Serializable

/** Constantes de la rejilla. Diseño propio (ver docs/research): 1 celda = 8 m, 1 sector = 16x16 celdas. */
object Grid {
    const val CELL_M = 8.0
    const val SECTOR_CELLS = 16
    const val SECTOR_M = CELL_M * SECTOR_CELLS

    fun sectorOf(cell: Int): Int = Math.floorDiv(cell, SECTOR_CELLS)
    fun sectorKey(cx: Int, cy: Int) = SectorId(sectorOf(cx), sectorOf(cy))
}

/** Identificador estable de sector: "sec_<x>_<y>" (con signo: sec_-1_0). */
@Serializable
data class SectorId(val x: Int, val y: Int) {
    val name: String get() = "sec_${x}_$y"
    fun neighbors(): List<SectorId> = (-1..1).flatMap { dx -> (-1..1).map { dy -> SectorId(x + dx, y + dy) } }

    companion object {
        fun parse(s: String): SectorId? {
            val m = Regex("^sec_(-?\\d+)_(-?\\d+)$").matchEntire(s) ?: return null
            return SectorId(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
    }
}

/** Coordenada entera de un vértice de la rejilla (esquina de celda). */
@Serializable
data class Vtx(val x: Int, val y: Int) : Comparable<Vtx> {
    override fun compareTo(other: Vtx): Int = if (x != other.x) x.compareTo(other.x) else y.compareTo(other.y)
    val id: String get() = "n_${x}_$y"
    val sector: SectorId get() = Grid.sectorKey(x, y)
}
