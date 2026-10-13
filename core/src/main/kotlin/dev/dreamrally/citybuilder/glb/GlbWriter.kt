package dev.dreamrally.citybuilder.glb

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private class JA { val l = ArrayList<JsonElement>(); fun add(e: JsonElement) { l.add(e) }; fun build() = JsonArray(l) }

/** Nodo glTF con una malla. El nombre es el identificador lógico (p. ej. id de segmento vial). */
class GlbNode(val name: String, val mesh: MeshBuilder, val translation: DoubleArray? = null, val extras: JsonObject? = null)

/**
 * Escritor GLB (glTF 2.0 binario). Las texturas se referencian por URI relativa externa (compartidas entre GLB,
 * sin duplicarlas), con [textureUriPrefix] relativo a la ubicación del GLB dentro del paquete.
 */
object GlbWriter {
    fun write(nodes: List<GlbNode>, textureUriPrefix: String, generator: String = "CityBuilderDreamRally"): ByteArray {
        val bin = ByteArrayOutputStream()
        val bufferViews = JA(); val accessors = JA()
        val materials = LinkedHashMap<String, Int>(); val matJson = JA()
        val textures = LinkedHashMap<String, Int>(); val images = JA(); val texJson = JA()
        val meshes = JA(); val nodesJson = JA(); var accCount = 0; var bvCount = 0

        fun pad() { while (bin.size() % 4 != 0) bin.write(0) }
        fun addView(bytes: ByteArray, target: Int): Int {
            pad(); val off = bin.size(); bin.write(bytes)
            bufferViews.add(buildJsonObject { put("buffer", 0); put("byteOffset", off); put("byteLength", bytes.size); put("target", target) })
            return bvCount++
        }
        fun floats(a: FloatArray, n: Int): ByteArray { val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) bb.putFloat(a[i]); return bb.array() }
        fun ints(a: IntArray, n: Int): ByteArray { val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) bb.putInt(a[i]); return bb.array() }
        fun accessor(view: Int, comp: Int, count: Int, type: String, min: List<Double>? = null, max: List<Double>? = null): Int {
            accessors.add(buildJsonObject {
                put("bufferView", view); put("componentType", comp); put("count", count); put("type", type)
                if (min != null) put("min", JsonArray(min.map { JsonPrimitive(it) }))
                if (max != null) put("max", JsonArray(max.map { JsonPrimitive(it) }))
            }); return accCount++
        }
        fun material(m: Mat): Int = materials.getOrPut(m.name) {
            val idx = materials.size
            var texIdx: Int? = null
            if (m.texture != null) texIdx = textures.getOrPut(m.texture) {
                images.add(buildJsonObject { put("uri", "$textureUriPrefix${m.texture}.png"); put("name", m.texture) })
                texJson.add(buildJsonObject { put("source", textures.size); put("sampler", 0) })
                textures.size
            }
            matJson.add(buildJsonObject {
                put("name", m.name)
                putJsonObject("pbrMetallicRoughness") {
                    putJsonArray("baseColorFactor") { add(((m.rgb shr 16) and 255) / 255.0); add(((m.rgb shr 8) and 255) / 255.0); add((m.rgb and 255) / 255.0); add(1.0) }
                    if (texIdx != null) putJsonObject("baseColorTexture") { put("index", texIdx) }
                    put("metallicFactor", m.metallic); put("roughnessFactor", m.roughness)
                }
                if (m.doubleSided) put("doubleSided", true)
            })
            idx
        }

        for (node in nodes) {
            val prims = JA()
            for (p in node.mesh.prims.values) {
                if (p.idx.n == 0) continue
                val n = p.vertexCount
                var mn = doubleArrayOf(1e30, 1e30, 1e30); var mx = doubleArrayOf(-1e30, -1e30, -1e30)
                for (i in 0 until n) for (k in 0..2) { val v = p.pos.a[i * 3 + k].toDouble(); if (v < mn[k]) mn[k] = v; if (v > mx[k]) mx[k] = v }
                val vp = addView(floats(p.pos.a, p.pos.n), 34962); val vn = addView(floats(p.nrm.a, p.nrm.n), 34962)
                val vu = addView(floats(p.uv.a, p.uv.n), 34962); val vi = addView(ints(p.idx.a, p.idx.n), 34963)
                val aP = accessor(vp, 5126, n, "VEC3", mn.toList(), mx.toList()); val aN = accessor(vn, 5126, n, "VEC3")
                val aU = accessor(vu, 5126, n, "VEC2"); val aI = accessor(vi, 5125, p.idx.n, "SCALAR")
                val mi = material(p.mat)
                prims.add(buildJsonObject {
                    putJsonObject("attributes") { put("POSITION", aP); put("NORMAL", aN); put("TEXCOORD_0", aU) }
                    put("indices", aI); put("material", mi); put("mode", 4)
                })
            }
            val meshIdx = meshes.l.size
            meshes.add(buildJsonObject { put("name", node.name); put("primitives", prims.build()) })
            nodesJson.add(buildJsonObject {
                put("name", node.name); put("mesh", meshIdx)
                if (node.translation != null) putJsonArray("translation") { node.translation.forEach { add(it) } }
                if (node.extras != null) put("extras", node.extras)
            })
        }
        pad()
        val nodeCount = nodes.size
        val root = buildJsonObject {
            putJsonObject("asset") { put("version", "2.0"); put("generator", generator) }
            put("scene", 0)
            putJsonArray("scenes") { addJsonObject { putJsonArray("nodes") { for (i in 0 until nodeCount) add(i) } } }
            put("nodes", nodesJson.build()); put("meshes", meshes.build())
            val mj = matJson.build(); if (mj.isNotEmpty()) put("materials", mj)
            val tj = texJson.build(); if (tj.isNotEmpty()) {
                put("textures", tj); put("images", images.build())
                putJsonArray("samplers") { addJsonObject { put("magFilter", 9729); put("minFilter", 9987); put("wrapS", 10497); put("wrapT", 10497) } }
            }
            val aj = accessors.build(); if (aj.isNotEmpty()) { put("accessors", aj); put("bufferViews", bufferViews.build()) }
            putJsonArray("buffers") { addJsonObject { put("byteLength", bin.size()) } }
        }
        var jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
        val jpad = (4 - jsonBytes.size % 4) % 4
        jsonBytes += ByteArray(jpad) { 0x20 }
        val binBytes = bin.toByteArray()
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67); bb.putInt(2); bb.putInt(total)
        bb.putInt(jsonBytes.size); bb.putInt(0x4E4F534A); bb.put(jsonBytes)
        bb.putInt(binBytes.size); bb.putInt(0x004E4942); bb.put(binBytes)
        return bb.array()
    }

    /** Lee el chunk JSON de un GLB (para verificar texturas/materiales referenciados). Lanza si es inválido. */
    fun readJson(glb: ByteArray): JsonObject {
        require(glb.size >= 20) { "GLB demasiado corto" }
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x46546C67) { "Cabecera GLB inválida" }
        require(bb.getInt(4) == 2) { "Versión GLB no soportada" }
        require(bb.getInt(8) == glb.size) { "Longitud GLB inconsistente" }
        val len = bb.getInt(12); require(bb.getInt(16) == 0x4E4F534A) { "Primer chunk no es JSON" }
        require(20 + len <= glb.size) { "Chunk JSON truncado" }
        return Json.parseToJsonElement(String(glb, 20, len, Charsets.UTF_8)).jsonObject
    }
}
