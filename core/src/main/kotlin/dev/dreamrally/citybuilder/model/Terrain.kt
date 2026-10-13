package dev.dreamrally.citybuilder.model

import kotlinx.serialization.Serializable
import kotlin.math.floor

/** Biomas: definen materiales del suelo y la paleta de decoración. Por defecto TEMPLATE (césped verde). */
@Serializable
enum class Biome(val label: String, val groundRgb: Int, val groundRgb2: Int) {
    TEMPERATE("Templado (césped)", 0x5E9E3F, 0x4F8A35),
    DESERT("Desierto", 0xD8B772, 0xC9A55E),
    SNOW("Nieve", 0xEEF3F7, 0xD6E0EA),
    TROPICAL("Tropical", 0x3FAE4F, 0x2E9441),
    AUTUMN("Otoño", 0x9A8F3F, 0x857B33)
}

@Serializable
enum class Relief(val label: String, val amplitude: Double) {
    FLAT("Llano", 0.0),
    GENTLE("Ondulado suave", 3.0),
    HILLS("Colinas", 8.0)
}

/** Altura determinista del terreno en un vértice de rejilla. Mismo seed => mismo relieve en editor y exportación. */
object Heights {
    private fun hash(ix: Int, iy: Int, seed: Int): Double {
        var h = ix * 374761393 + iy * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535.0
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    private fun noise(x: Double, y: Double, seed: Int): Double {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val tx = smooth(x - x0); val ty = smooth(y - y0)
        val a = hash(x0, y0, seed); val b = hash(x0 + 1, y0, seed)
        val c = hash(x0, y0 + 1, seed); val d = hash(x0 + 1, y0 + 1, seed)
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
    }

    /** Altura en metros en el vértice (vx, vy). Se redondea a 5 cm para que sea reproducible en JSON. */
    fun at(relief: Relief, seed: Int, vx: Int, vy: Int): Double {
        if (relief == Relief.FLAT) return 0.0
        val n = noise(vx / 9.0, vy / 9.0, seed) * 0.75 + noise(vx / 4.0, vy / 4.0, seed + 7) * 0.25
        val h = (n - 0.5) * 2 * relief.amplitude
        return Math.round(h * 20) / 20.0
    }
}
