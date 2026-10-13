package dev.dreamrally.citybuilder.gfx

import dev.dreamrally.citybuilder.model.Biome
import kotlin.math.abs

/** Texturas procedurales propias (64x64, repetibles). Se multiplican por el color base del material. */
object Textures {
    const val S = 64

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun noise(x: Int, y: Int, seed: Int): Int {
        var h = x * 73856093 xor y * 19349663 xor seed * 83492791
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0xFF
    }

    private fun make(f: (Int, Int) -> Int): ByteArray = Png.encode(S, S, IntArray(S * S) { f(it % S, it / S) })

    fun ground(biome: Biome): ByteArray {
        val c1 = biome.groundRgb; val c2 = biome.groundRgb2
        return make { x, y ->
            val n = noise(x / 2, y / 2, biome.ordinal)
            val base = if (n > 128) c1 else c2
            val j = (noise(x, y, 99) - 128) / 12
            rgb(((base shr 16) and 255) + j, ((base shr 8) and 255) + j, (base and 255) + j)
        }
    }

    /** Asfalto con marcas; v crece a lo largo del tramo (64 px = 8 m), u cruza el ancho. */
    fun asphalt(kind: String): ByteArray = make { x, y ->
        val n = (noise(x, y, 5) - 128) / 16
        var base = 0x60 + n
        var r = base; var g = base; var b = base + 4
        val center = abs(x - 32) <= 1
        val edge = x <= 2 || x >= S - 3
        when (kind) {
            "street" -> { if (center && (y % 32) < 16) { r = 235; g = 235; b = 230 }; if (edge) { r = 200; g = 200; b = 195 } }
            "avenue" -> { if (abs(x - 30) <= 1 || abs(x - 33) <= 1) { r = 240; g = 200; b = 40 }; if (edge) { r = 210; g = 210; b = 205 } }
            "highway" -> { if (center && (y % 32) < 20) { r = 240; g = 240; b = 235 }; if (x in 3..4 || x in S - 5..S - 4) { r = 230; g = 230; b = 230 } }
            "dirt" -> { base = 0x9A + n; r = base; g = base - 28; b = base - 62 }
        }
        rgb(r, g, b)
    }

    /** Ventana por celda: una tesela = 1 ventana (4 m x 3.5 m). Color claro multiplicable por el color de pared. */
    fun facade(kind: String): ByteArray = make { x, y ->
        val n = (noise(x, y, 11) - 128) / 40
        val inWin = when (kind) {
            "commercial" -> x in 6..57 && y in 24..58            // escaparate ancho
            "industrial" -> x in 8..55 && y in 10..22            // ventanal alto
            "office" -> x in 2..61 && y in 14..46                // cinta de vidrio
            "civic" -> x in 20..43 && y in 10..52                // ventana alta
            else -> x in 18..46 && y in 14..46                   // residencial
        }
        if (inWin) {
            val glass = if (kind == "office") rgb(70 + n, 110 + n, 150 + n) else rgb(90 + n, 120 + n, 150 + n)
            val frame = x == 18 || x == 46 || y == 14 || y == 46
            if (frame && kind != "office") rgb(235, 235, 235) else glass
        } else rgb(240 + n, 240 + n, 240 + n)
    }

    fun roofTiles(): ByteArray = make { x, y ->
        val row = y / 8; val xo = if (row % 2 == 0) 0 else 8
        val line = (y % 8 == 0) || ((x + xo) % 16 == 0)
        val n = (noise(x, y, 21) - 128) / 30
        if (line) rgb(190, 190, 190) else rgb(245 + n, 245 + n, 245 + n)
    }

    fun concrete(): ByteArray = make { x, y -> val n = noise(x, y, 31) / 10; rgb(228 + n - 10, 228 + n - 10, 228 + n - 10) }

    fun pavers(): ByteArray = make { x, y ->
        val line = x % 16 == 0 || y % 16 == 0
        val n = (noise(x / 16, y / 16, 41) - 128) / 12
        if (line) rgb(170, 170, 165) else rgb(225 + n, 222 + n, 215 + n)
    }

    val ALL: Map<String, () -> ByteArray> = linkedMapOf(
        "facade_residential" to { facade("residential") }, "facade_commercial" to { facade("commercial") },
        "facade_industrial" to { facade("industrial") }, "facade_office" to { facade("office") }, "facade_civic" to { facade("civic") },
        "roof_tiles" to ::roofTiles, "concrete" to ::concrete, "pavers" to ::pavers,
        "road_street" to { asphalt("street") }, "road_avenue" to { asphalt("avenue") },
        "road_highway" to { asphalt("highway") }, "road_dirt" to { asphalt("dirt") }
    )
    fun groundName(b: Biome) = "ground_${b.name.lowercase()}"
}
