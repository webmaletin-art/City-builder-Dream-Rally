package dev.dreamrally.citybuilder.gfx

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Codificador PNG RGBA 8 bits mínimo (sin java.awt, válido en Android). pixels = ARGB. */
object Png {
    fun encode(w: Int, h: Int, argb: IntArray): ByteArray {
        require(argb.size == w * h)
        val raw = ByteArrayOutputStream(h * (w * 4 + 1))
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) {
                val p = argb[y * w + x]
                raw.write((p shr 16) and 0xFF); raw.write((p shr 8) and 0xFF); raw.write(p and 0xFF); raw.write((p ushr 24) and 0xFF)
            }
        }
        val deflater = Deflater(9)
        deflater.setInput(raw.toByteArray()); deflater.finish()
        val comp = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) comp.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(w); writeInt(h); writeByte(8); writeByte(6); writeByte(0); writeByte(0); writeByte(0) }
        }.toByteArray()
        chunk(out, "IHDR", ihdr); chunk(out, "IDAT", comp.toByteArray()); chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.write(t); d.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        d.writeInt(crc.value.toInt())
    }

    /** Lee ancho/alto de un PNG (para validar texturas al importar). */
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || bytes[1] != 0x50.toByte() || bytes[2] != 0x4E.toByte()) return null
        fun i(o: Int) = ((bytes[o].toInt() and 255) shl 24) or ((bytes[o + 1].toInt() and 255) shl 16) or ((bytes[o + 2].toInt() and 255) shl 8) or (bytes[o + 3].toInt() and 255)
        return i(16) to i(20)
    }
}
