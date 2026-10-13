package dev.dreamrally.citybuilder.export

import dev.dreamrally.citybuilder.glb.GlbWriter
import dev.dreamrally.citybuilder.gfx.Png
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class VerifyReport(val errors: List<String>, val warnings: List<String>, val entries: Int) {
    val ok get() = errors.isEmpty()
}

/** Contenido ya leído de un paquete (todo en memoria; los paquetes se verifican por entradas). */
class PackageContents(val files: Map<String, ByteArray>)

/**
 * Verifica integridad (CRC del ZIP, SHA-256 del manifiesto), rutas seguras, referencias cruzadas entre JSON,
 * GLB y texturas, y dependencias ausentes. Nunca lanza por datos malformados: los reporta como errores.
 */
object PackageVerifier {
    private const val MAX_ENTRY = 256L * 1024 * 1024

    fun readZip(bytes: ByteArray): PackageContents = readStream(ByteArrayInputStream(bytes))

    fun readStream(ins: java.io.InputStream): PackageContents {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ins).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val bos = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var total = 0L
                while (true) { val n = z.read(buf); if (n < 0) break; total += n; if (total > MAX_ENTRY) throw ZipException("Entrada demasiado grande: ${e.name}"); bos.write(buf, 0, n) }
                if (files.put(e.name, bos.toByteArray()) != null) throw ZipException("Entrada duplicada: ${e.name}")
            }
        }
        return PackageContents(files)
    }

    fun verify(file: File): VerifyReport = try {
        file.inputStream().buffered().use { verify(readStream(it)) }
    } catch (e: Exception) { VerifyReport(listOf("ZIP ilegible o corrupto: ${e.message}"), emptyList(), 0) }

    fun verify(bytes: ByteArray): VerifyReport = try { verify(readZip(bytes)) } catch (e: Exception) { VerifyReport(listOf("ZIP ilegible o corrupto: ${e.message}"), emptyList(), 0) }

    fun safePath(p: String): Boolean =
        p.isNotEmpty() && !p.startsWith("/") && !p.contains("\\") && !p.contains(":") && p.split("/").none { it == ".." || it == "." || it.isEmpty() }

    fun resolve(baseFile: String, uri: String): String? {
        val parts = ArrayList<String>(baseFile.substringBeforeLast('/', "").split("/").filter { it.isNotEmpty() })
        for (seg in uri.split("/")) when (seg) { "", "." -> {}; ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1); else -> parts.add(seg) }
        return parts.joinToString("/")
    }

    fun verify(pkg: PackageContents): VerifyReport {
        val errors = ArrayList<String>(); val warnings = ArrayList<String>()
        val files = pkg.files
        fun err(m: String) { errors += m }
        files.keys.filterNot(::safePath).forEach { err("Ruta insegura en el ZIP: '$it'") }

        fun json(path: String): JsonObject? {
            val b = files[path] ?: run { err("Falta $path"); return null }
            return try { Json.parseToJsonElement(String(b, Charsets.UTF_8)).jsonObject } catch (e: Exception) { err("$path no es JSON válido: ${e.message}"); null }
        }

        val manifest = json("manifest.json") ?: return VerifyReport(errors, warnings, files.size)
        try {
            if (manifest["format"]?.jsonPrimitive?.content != Format.PACKAGE_NAME) err("manifest.format debe ser ${Format.PACKAGE_NAME}")
            val schema = manifest["schemaVersion"]?.jsonPrimitive?.intOrNull
            if (schema == null) err("manifest.schemaVersion ausente") else if (schema > Format.SCHEMA_VERSION) err("Esquema $schema más nuevo que el soportado (${Format.SCHEMA_VERSION})")
            for (k in listOf("packageVersion", "cityId", "name", "dimensions", "units", "coordinateSystem", "files", "dependencies", "compatibility")) if (k !in manifest) err("manifest.$k ausente")
        } catch (e: Exception) { err("manifest malformado: ${e.message}") }

        // archivos del manifiesto vs ZIP
        val listed = HashMap<String, JsonObject>()
        try {
            for (f in manifest["files"]?.jsonArray ?: JsonArray(emptyList())) {
                val o = f.jsonObject; val p = o["path"]!!.jsonPrimitive.content
                if (!safePath(p)) err("manifest: ruta insegura '$p'")
                listed[p] = o
            }
        } catch (e: Exception) { err("manifest.files malformado: ${e.message}") }
        for ((p, o) in listed) {
            val b = files[p]
            if (b == null) { err("Archivo declarado pero ausente: $p"); continue }
            if (o["size"]?.jsonPrimitive?.longOrNull != b.size.toLong()) err("Tamaño incorrecto: $p")
            if (o["sha256"]?.jsonPrimitive?.content != PackageExporter.sha256(b)) err("SHA-256 incorrecto: $p")
        }
        for (p in files.keys) if (p != "manifest.json" && p !in listed) err("Archivo no declarado en el manifiesto: $p")

        val city = json("city.json"); val roads = json("roads.json"); val buildings = json("buildings.json"); val mats = json("materials/materials.json")
        val sectorIds = HashSet<String>()
        try {
            city?.get("sectors")?.jsonArray?.forEach { s ->
                val o = s.jsonObject; sectorIds += o["id"]!!.jsonPrimitive.content
                for (k in listOf("terrainHeights", "terrainGeometry")) { val p = o[k]!!.jsonPrimitive.content; if (p !in files) err("Sector ${o["id"]}: falta $p") }
                val r = o["roadsGeometry"]; if (r != null && r !is JsonNull && r.jsonPrimitive.content !in files) err("Sector ${o["id"]}: falta ${r.jsonPrimitive.content}")
            }
            if (city != null && sectorIds.isEmpty()) err("city.json sin sectores")
        } catch (e: Exception) { err("city.json malformado: ${e.message}") }

        val matNames = HashSet<String>()
        try {
            mats?.get("materials")?.jsonArray?.forEach { m ->
                val o = m.jsonObject; matNames += o["name"]!!.jsonPrimitive.content
                val t = o["baseColorTexture"]; if (t != null && t !is JsonNull) {
                    val tp = t.jsonPrimitive.content
                    val png = files[tp]
                    if (png == null) err("Material ${o["name"]}: textura ausente $tp") else if (Png.size(png) == null) err("Textura no es PNG válido: $tp")
                }
            }
        } catch (e: Exception) { err("materials.json malformado: ${e.message}") }

        // GLB: cabecera, texturas externas referenciadas
        for ((p, b) in files) if (p.endsWith(".glb")) {
            try {
                val js = GlbWriter.readJson(b)
                js["images"]?.jsonArray?.forEach { im ->
                    val uri = im.jsonObject["uri"]?.jsonPrimitive?.content
                    if (uri != null && !uri.startsWith("data:")) {
                        val target = resolve(p, uri)
                        if (target == null || !safePath(target)) err("$p: URI de textura insegura '$uri'") else if (target !in files) err("$p: textura ausente '$target'")
                    }
                }
                js["materials"]?.jsonArray?.forEach { m ->
                    val n = m.jsonObject["name"]?.jsonPrimitive?.content
                    if (n != null && matNames.isNotEmpty() && n !in matNames && p.startsWith("geometry/sectors")) warnings += "$p: material '$n' no está en materials.json"
                }
            } catch (e: Exception) { err("GLB inválido $p: ${e.message}") }
        }

        // edificios
        val buildingIds = HashSet<String>()
        try {
            buildings?.get("buildings")?.jsonArray?.forEach { b ->
                val o = b.jsonObject; val id = o["id"]!!.jsonPrimitive.content
                if (!buildingIds.add(id)) err("Edificio duplicado: $id")
                val model = o["model"]!!.jsonPrimitive.content
                if (model !in files) err("Edificio $id: modelo ausente $model")
                if (o["sector"]!!.jsonPrimitive.content !in sectorIds) err("Edificio $id: sector inexistente ${o["sector"]}")
                o["materials"]?.jsonArray?.forEach { m -> if (matNames.isNotEmpty() && m.jsonPrimitive.content !in matNames && model.startsWith("geometry/models/")) {
                    // materiales de GLB externos no figuran en materials.json: solo advertencia
                    warnings += "Edificio $id: material '${m.jsonPrimitive.content}' no figura en materials.json" } }
            }
        } catch (e: Exception) { err("buildings.json malformado: ${e.message}") }

        // red vial
        try {
            if (roads != null) {
                val nodeIds = roads["nodes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
                val segIds = roads["segments"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
                if (segIds.toSet().size != segIds.size) err("Ids de segmento duplicados")
                val segSet = segIds.toSet()
                val glbNodes = HashMap<String, Set<String>>()
                fun nodesOf(glb: String): Set<String> = glbNodes.getOrPut(glb) { files[glb]?.let { b -> try { GlbWriter.readJson(b)["nodes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet() } catch (e: Exception) { emptySet() } } ?: emptySet() }
                for (s in roads["segments"]!!.jsonArray) {
                    val o = s.jsonObject; val id = o["id"]!!.jsonPrimitive.content
                    val ns = o["nodeIds"]!!.jsonArray.map { it.jsonPrimitive.content }
                    if (ns.size < 2) err("Segmento $id con menos de 2 nodos")
                    ns.filter { it !in nodeIds }.forEach { err("Segmento $id: nodo inexistente $it") }
                    if (o["startNode"]!!.jsonPrimitive.content != ns.first() || o["endNode"]!!.jsonPrimitive.content != ns.last()) err("Segmento $id: startNode/endNode no coinciden con nodeIds")
                    for (c in listOf("atStart", "atEnd")) o["connections"]!!.jsonObject[c]!!.jsonArray.forEach { x -> if (x.jsonPrimitive.content !in segSet) err("Segmento $id: conexión a segmento inexistente ${x.jsonPrimitive.content}") }
                    val g = o["geometry"]!!.jsonPrimitive.content; val glb = g.substringBefore('#'); val node = g.substringAfter('#', "")
                    if (glb !in files) err("Segmento $id: geometría ausente $glb") else if (node !in nodesOf(glb)) err("Segmento $id: el GLB $glb no contiene el nodo '$node'")
                }
                roads["nodes"]!!.jsonArray.forEach { n -> n.jsonObject["segments"]!!.jsonArray.forEach { x -> if (x.jsonPrimitive.content !in segSet) err("Nodo ${n.jsonObject["id"]}: segmento inexistente ${x.jsonPrimitive.content}") } }
                roads["intersections"]!!.jsonArray.forEach { i ->
                    val o = i.jsonObject
                    if (o["node"]!!.jsonPrimitive.content !in nodeIds) err("Intersección ${o["id"]}: nodo inexistente")
                    o["segments"]!!.jsonArray.forEach { x -> if (x.jsonPrimitive.content !in segSet) err("Intersección ${o["id"]}: segmento inexistente ${x.jsonPrimitive.content}") }
                }
            }
        } catch (e: Exception) { err("roads.json malformado: ${e.message}") }

        // manzanas y parcelas (opcional en paquetes 1.0.x)
        json("blocks.json".takeIf { it in files } ?: "city.json")?.takeIf { "blocks.json" in files }?.let { bl ->
            try {
                val blockIds = bl["blocks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
                val parcelIds = HashSet<String>()
                for (p in bl["parcels"]!!.jsonArray) {
                    val o = p.jsonObject; val id = o["id"]!!.jsonPrimitive.content
                    if (!parcelIds.add(id)) err("Parcela duplicada: $id")
                    if (o["block"]!!.jsonPrimitive.content !in blockIds) err("Parcela $id: manzana inexistente")
                    val occ = o["occupiedBy"]; if (occ != null && occ !is JsonNull && occ.jsonPrimitive.content !in buildingIds) err("Parcela $id: edificio inexistente ${occ.jsonPrimitive.content}")
                }
                for (b in bl["blocks"]!!.jsonArray) b.jsonObject["parcels"]!!.jsonArray.forEach { if (it.jsonPrimitive.content !in parcelIds) err("Manzana ${b.jsonObject["id"]}: parcela inexistente ${it.jsonPrimitive.content}") }
            } catch (e: Exception) { err("blocks.json malformado: ${e.message}") }
        }

        return VerifyReport(errors, warnings, files.size)
    }
}
