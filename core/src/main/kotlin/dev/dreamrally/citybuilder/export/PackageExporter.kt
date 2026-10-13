package dev.dreamrally.citybuilder.export

import dev.dreamrally.citybuilder.glb.*
import dev.dreamrally.citybuilder.gfx.Textures
import dev.dreamrally.citybuilder.model.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.hypot

class ExportCancelled : RuntimeException("Exportación cancelada")
class ExportFailed(val errors: List<String>) : RuntimeException("La exportación no puede completarse:\n" + errors.joinToString("\n") { "• $it" })

data class Progress(val stage: String, val fraction: Float)

data class ExportResult(val file: File, val report: JsonObject, val warnings: List<String>, val entries: Int, val sizeBytes: Long)

/** Fuente de datos para exportar sin cargar toda la ciudad en memoria. */
class ExportInput(
    val config: CityConfig,
    val catalog: Catalog,
    val readSector: (SectorId) -> SectorData?,
    val readAsset: (String) -> ByteArray? = { null }
)

fun City.exportInput(readAsset: (String) -> ByteArray? = { null }) = ExportInput(config, catalog, { readSector(it) }, readAsset)

object PackageExporter {
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }
    private fun r2(v: Double) = Math.round(v * 100) / 100.0
    private fun r3(v: Double) = Math.round(v * 1000) / 1000.0
    private fun arr(vararg v: Double) = JsonArray(v.map { JsonPrimitive(r3(it)) })
    private fun ints(vararg v: Int) = JsonArray(v.map { JsonPrimitive(it) })

    /** Comprueba todo lo comprobable antes de generar nada. */
    fun validate(input: ExportInput): Pair<List<String>, List<String>> {
        val errors = ArrayList<String>(); val warnings = ArrayList<String>()
        val cfg = input.config
        if (cfg.unlocked.isEmpty()) errors += "El terreno no tiene sectores desbloqueados"
        val allEdges = ArrayList<RoadEdge>(); var nb = 0
        for (s in cfg.unlocked) {
            val d = input.readSector(s) ?: continue
            for (b in d.buildings) {
                nb++
                val spec = input.catalog[b.catalogId]
                if (spec == null) { errors += "Edificio ${b.id}: '${b.catalogId}' no existe en el catálogo"; continue }
                if (spec.model.kind == "glb") {
                    val bytes = spec.model.glbPath?.let { input.readAsset(it) }
                    if (bytes == null) errors += "Modelo GLB ausente para '${spec.id}': ${spec.model.glbPath}"
                    else try { val js = GlbWriter.readJson(bytes); js["images"]?.jsonArray?.forEach { im -> if (im.jsonObject["uri"]?.jsonPrimitive?.content?.startsWith("data:") == false) errors += "'${spec.id}': el GLB usa texturas externas; debe embeberlas" } }
                    catch (e: Exception) { errors += "GLB inválido para '${spec.id}': ${e.message}" }
                }
            }
            allEdges += d.edges
            if (d.id !in cfg.unlocked) errors += "Sector ${d.id.name} fuera del terreno desbloqueado"
        }
        if (nb == 0 && allEdges.isEmpty()) warnings += "La ciudad está vacía (sin edificios ni carreteras)"
        val comps = RoadGraph(allEdges).components
        if (comps > 1) warnings += "La red vial tiene $comps redes desconectadas entre sí"
        return errors to warnings
    }

    fun export(
        input: ExportInput, out: File, progress: (Progress) -> Unit = {}, cancelled: () -> Boolean = { false }
    ): ExportResult {
        val t0 = System.currentTimeMillis() // solo informativo; el informe del ZIP es determinista
        fun check() { if (cancelled()) throw ExportCancelled() }
        progress(Progress("Validando proyecto", 0.02f))
        val (errors, warnings0) = validate(input)
        if (errors.isNotEmpty()) throw ExportFailed(errors)
        val warnings = ArrayList(warnings0)
        val cfg = input.config; val hf = HeightField(cfg)
        val stage = java.nio.file.Files.createTempDirectory("dcp").toFile()
        val fileHash = LinkedHashMap<String, Pair<String, Long>>()
        fun emit(path: String, bytes: ByteArray) {
            require(!path.contains("..") && !path.startsWith("/")) { "ruta insegura $path" }
            val f = File(stage, path); f.parentFile.mkdirs(); f.writeBytes(bytes)
            fileHash[path] = sha256(bytes) to bytes.size.toLong()
        }
        fun emit(path: String, text: String) = emit(path, text.toByteArray(Charsets.UTF_8))
        try {
            // ---- pasada 1: datos ligeros de todos los sectores ----
            progress(Progress("Leyendo sectores", 0.05f))
            val sectorIds = cfg.unlocked.sortedWith(compareBy({ it.x }, { it.y }))
            val sectorData = LinkedHashMap<SectorId, SectorData>()
            for (s in sectorIds) { check(); sectorData[s] = input.readSector(s) ?: SectorData(s) }
            val allEdges = sectorData.values.flatMap { it.edges }
            val graph = RoadGraph(allEdges)
            val materials = LinkedHashMap<String, Mat>()

            // ---- modelos de catálogo (una vez por tipo, instanciados desde buildings.json) ----
            val usedIds = sectorData.values.flatMap { it.buildings }.map { it.catalogId }.distinct().sorted()
            val modelMaterials = HashMap<String, List<String>>()
            usedIds.forEachIndexed { i, id ->
                check(); progress(Progress("Generando modelos 3D (${i + 1}/${usedIds.size})", 0.1f + 0.3f * i / usedIds.size.coerceAtLeast(1)))
                val spec = input.catalog[id]!!
                if (spec.model.kind == "glb") {
                    val bytes = input.readAsset(spec.model.glbPath!!)!!
                    emit("geometry/models/$id.glb", bytes)
                    val js = GlbWriter.readJson(bytes)
                    modelMaterials[id] = js["materials"]?.jsonArray?.map { it.jsonObject["name"]?.jsonPrimitive?.content ?: "unnamed" } ?: emptyList()
                } else {
                    val mesh = ModelFactory.build(spec)
                    mesh.prims.values.forEach { materials[it.mat.name] = it.mat }
                    modelMaterials[id] = mesh.prims.values.filter { it.idx.n > 0 }.map { it.mat.name }
                    emit("geometry/models/$id.glb", GlbWriter.write(listOf(GlbNode(id, mesh)), "../../textures/"))
                }
            }

            // ---- sectores: terreno + carreteras (por lotes, liberando mallas) ----
            val segBySector = graph.segments.groupBy { it.start.sector }
            val nodeBySector = graph.nodes.values.groupBy { it.v.sector }
            val roundaboutBySector = cfg.roundabouts.groupBy { Vtx(it.cx, it.cy).sector }
            val sectorsJson = ArrayList<JsonElement>()
            sectorIds.forEachIndexed { i, s ->
                check(); progress(Progress("Exportando sector ${s.name} (${i + 1}/${sectorIds.size})", 0.4f + 0.4f * i / sectorIds.size))
                val tMesh = TerrainMesher.mesh(s, hf); tMesh.prims.values.forEach { materials[it.mat.name] = it.mat }
                emit("geometry/sectors/${s.name}_terrain.glb", GlbWriter.write(listOf(GlbNode("terrain_${s.name}", tMesh)), "../../textures/"))
                emit("terrain/${s.name}.json", prettyJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("schemaVersion", Format.SCHEMA_VERSION); put("sector", s.name); put("vertices", Format.VERTS); put("spacingMeters", Grid.CELL_M)
                    putJsonArray("originMeters") { Format.sectorOrigin(s).forEach { add(it) } }
                    put("biome", cfg.biome.name.lowercase()); put("layout", "rows indexed by z (cell y), columns by x; meters above sea level 0")
                    put("heights", JsonArray(TerrainMesher.heightsJson(s, hf).map { row -> JsonArray(row.map { JsonPrimitive(it) }) }))
                }))
                val roadNodes = ArrayList<GlbNode>()
                for (seg in segBySector[s] ?: emptyList()) {
                    val m = RoadMesher.segmentMesh(seg, hf); m.prims.values.forEach { materials[it.mat.name] = it.mat }
                    roadNodes += GlbNode(seg.id, m, extras = buildJsonObject { put("kind", "road_segment"); put("segmentId", seg.id) })
                }
                for (n in nodeBySector[s] ?: emptyList()) {
                    val inc = graph.edgesAt(n.v)
                    val needsPatch = n.kind != NodeKind.THROUGH || !straight(n.v, inc)
                    if (!needsPatch) continue
                    val t = inc.maxByOrNull { it.type.width }!!.type
                    val m = RoadMesher.nodePatch(n.v, inc.maxOf { it.type.width } / 2, t, hf); m.prims.values.forEach { materials[it.mat.name] = it.mat }
                    roadNodes += GlbNode(n.v.id, m, extras = buildJsonObject { put("kind", "road_node"); put("nodeId", n.v.id) })
                }
                for (rb in roundaboutBySector[s] ?: emptyList()) {
                    val m = RoadMesher.island(rb, hf); m.prims.values.forEach { materials[it.mat.name] = it.mat }
                    roadNodes += GlbNode("island_${rb.id}", m, extras = buildJsonObject { put("kind", "roundabout_island"); put("roundaboutId", rb.id) })
                }
                val roadsFile = if (roadNodes.isNotEmpty()) "geometry/sectors/${s.name}_roads.glb".also { emit(it, GlbWriter.write(roadNodes, "../../textures/")) } else null
                val d = sectorData[s]!!
                sectorsJson += buildJsonObject {
                    put("id", s.name); put("sx", s.x); put("sy", s.y)
                    putJsonArray("originMeters") { Format.sectorOrigin(s).forEach { add(it) } }
                    put("sizeMeters", Grid.SECTOR_M); put("sizeCells", Grid.SECTOR_CELLS)
                    put("terrainHeights", "terrain/${s.name}.json"); put("terrainGeometry", "geometry/sectors/${s.name}_terrain.glb")
                    if (roadsFile != null) put("roadsGeometry", roadsFile) else put("roadsGeometry", JsonNull)
                    put("buildingCount", d.buildings.size); put("roadSegmentCount", segBySector[s]?.size ?: 0)
                }
            }

            // ---- texturas y materiales (solo las usadas) ----
            check(); progress(Progress("Texturas y materiales", 0.82f))
            val texNames = materials.values.mapNotNull { it.texture }.toSortedSet()
            for (t in texNames) {
                val gen = if (t.startsWith("ground_")) { val b = Biome.valueOf(t.removePrefix("ground_").uppercase()); { dev.dreamrally.citybuilder.gfx.Textures.ground(b) } } else Textures.ALL[t]
                    ?: throw ExportFailed(listOf("Textura desconocida: $t"))
                emit("textures/$t.png", gen())
            }
            emit("materials/materials.json", prettyJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("schemaVersion", Format.SCHEMA_VERSION)
                put("note", "Materiales PBR metallic-roughness. baseColorFactor se multiplica por la textura. Los GLB ya los referencian; este archivo permite reconstruirlos sin parsear glTF.")
                put("materials", JsonArray(materials.values.sortedBy { it.name }.map { m ->
                    buildJsonObject {
                        put("name", m.name)
                        putJsonArray("baseColorFactor") { add(r3(((m.rgb shr 16) and 255) / 255.0)); add(r3(((m.rgb shr 8) and 255) / 255.0)); add(r3((m.rgb and 255) / 255.0)); add(1.0) }
                        if (m.texture != null) put("baseColorTexture", "textures/${m.texture}.png") else put("baseColorTexture", JsonNull)
                        put("roughness", m.roughness); put("metallic", m.metallic); put("doubleSided", m.doubleSided); put("textureWrap", "repeat")
                    }
                }))
            }))

            // ---- buildings.json ----
            check(); progress(Progress("buildings.json", 0.86f))
            var withoutRoad = 0
            val buildingsJson = ArrayList<JsonElement>()
            val adjacentVertices = HashSet<Vtx>().also { s -> allEdges.forEach { s += it.a; s += it.b } }
            for (s in sectorIds) for (b in sectorData[s]!!.buildings.sortedBy { it.id.drop(1).toIntOrNull() ?: 0 }) {
                val spec = input.catalog[b.catalogId]!!
                val fw = spec.footprintW(b.rot); val fd = spec.footprintD(b.rot)
                val baseY = hf.footprintBase(b.x, b.y, fw, fd)
                val cx = (b.x + fw / 2.0) * Grid.CELL_M; val cz = (b.y + fd / 2.0) * Grid.CELL_M
                var connected = false
                for (vx in b.x..b.x + fw) for (vy in b.y..b.y + fd) if ((vx == b.x || vx == b.x + fw || vy == b.y || vy == b.y + fd) && Vtx(vx, vy) in adjacentVertices) connected = true
                if (spec.placement.needsRoad && !connected) { withoutRoad++; warnings += "${b.id} (${spec.name}) no toca ninguna carretera" }
                buildingsJson += buildJsonObject {
                    put("id", b.id); put("catalogId", b.catalogId); put("name", spec.name); put("buildingType", spec.buildingType); put("category", spec.category.name.lowercase())
                    put("sector", s.name); put("cell", ints(b.x, b.y)); put("rot", b.rot)
                    put("position", arr(cx, baseY, cz)); put("rotationYDegrees", b.rot * 90); put("scale", arr(1.0, 1.0, 1.0))
                    putJsonObject("dimensions") { put("width", spec.widthCells * Grid.CELL_M); put("depth", spec.depthCells * Grid.CELL_M); put("height", spec.model.heightM); put("unit", "meters") }
                    putJsonObject("footprint") {
                        put("widthCells", fw); put("depthCells", fd)
                        putJsonArray("cells") { for (c in City.footprintCells(b.x, b.y, fw, fd)) add(ints(c.first, c.second)) }
                        putJsonArray("polygon") { for ((px, pz) in listOf(b.x to b.y, b.x + fw to b.y, b.x + fw to b.y + fd, b.x to b.y + fd)) add(arr(px * Grid.CELL_M, pz * Grid.CELL_M)) }
                    }
                    put("model", "geometry/models/${spec.id}.glb")
                    put("materials", JsonArray((modelMaterials[spec.id] ?: emptyList()).map { JsonPrimitive(it) }))
                    put("connectedToRoad", connected)
                    put("meta", JsonObject(spec.meta.mapValues { JsonPrimitive(it.value) }))
                }
            }
            emit("buildings.json", prettyJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("schemaVersion", Format.SCHEMA_VERSION); put("units", "meters")
                put("rotationConvention", "rotationYDegrees: giro alrededor de +Y (regla de la mano derecha, sentido antihorario visto desde arriba), aplicado al modelo centrado en su huella; position es el centro de la huella a la altura base del terreno")
                put("buildings", JsonArray(buildingsJson))
            }))

            // ---- roads.json ----
            check(); progress(Progress("roads.json", 0.9f))
            emit("roads.json", prettyJson.encodeToString(JsonObject.serializer(), roadsJson(graph, cfg, hf)))

            // ---- city.json ----
                    val minX = sectorIds.minOf { it.x }; val maxX = sectorIds.maxOf { it.x }; val minY = sectorIds.minOf { it.y }; val maxY = sectorIds.maxOf { it.y }
            emit("city.json", prettyJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("schemaVersion", Format.SCHEMA_VERSION); put("cityId", cfg.cityId); put("name", cfg.name)
                put("units", "meters"); put("cellSizeMeters", Grid.CELL_M); put("sectorSizeCells", Grid.SECTOR_CELLS); put("sectorSizeMeters", Grid.SECTOR_M)
                putJsonObject("bounds") {
                    putJsonArray("minMeters") { add(minX * Grid.SECTOR_M); add(minY * Grid.SECTOR_M) }
                    putJsonArray("maxMeters") { add((maxX + 1) * Grid.SECTOR_M); add((maxY + 1) * Grid.SECTOR_M) }
                    put("note", "[x, z] en metros; sectores no desbloqueados dentro de este rectángulo no existen")
                }
                putJsonObject("globalTransform") { put("translation", arr(0.0, 0.0, 0.0)); put("rotationYDegrees", 0.0); put("scale", arr(1.0, 1.0, 1.0)) }
                putJsonObject("terrain") {
                    put("biome", cfg.biome.name.lowercase()); put("relief", cfg.relief.name.lowercase()); put("reliefSeed", cfg.reliefSeed)
                    put("reliefAmplitudeMeters", cfg.relief.amplitude); put("heightSampling", "lattice vertices every ${Grid.CELL_M} m, see terrain/<sector>.json")
                }
                putJsonObject("mapConfig") { put("drivingSide", "right"); put("maxSectorsConfigured", cfg.maxSectors); put("buildingCount", buildingsJson.size); put("roadSegmentCount", graph.segments.size) }
                put("sectors", JsonArray(sectorsJson))
            }))

            // ---- metadata ----
            val specsUsed = usedIds.map { input.catalog[it]!! }
            emit("metadata/catalog.json", Catalog(specsUsed).toJson().toByteArray())
            emit("metadata/README.txt", ("DreamCity Package ${Format.PACKAGE_VERSION}\nLeé manifest.json primero. Especificación: docs/export/dream-city-package-spec.md del repositorio City-builder-Dream-Rally.\n" +
                "Las rutas son relativas a la raíz del ZIP. Unidades en metros, Y arriba, sistema diestro (igual que glTF y Godot).\n").toByteArray())

            // ---- informe (se incluye en el ZIP; sin hash propio) ----
            val report = buildJsonObject {
                put("schemaVersion", Format.SCHEMA_VERSION); put("result", "ok")
                put("sectors", sectorIds.size); put("buildings", buildingsJson.size); put("catalogModels", usedIds.size)
                put("roadSegments", graph.segments.size); put("roadNodes", graph.nodes.size); put("intersections", graph.intersections.size)
                put("roadComponents", graph.components); put("buildingsWithoutRoad", withoutRoad)
                put("textures", texNames.size); put("materials", materials.size)
                put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
            }
            emit("metadata/export-report.json", prettyJson.encodeToString(JsonObject.serializer(), report))

            // ---- manifest (último, lista todo con hash) ----
            check(); progress(Progress("manifest.json", 0.94f))
            val manifest = buildJsonObject {
                put("format", Format.PACKAGE_NAME); put("packageVersion", Format.PACKAGE_VERSION); put("schemaVersion", Format.SCHEMA_VERSION)
                put("cityId", cfg.cityId); put("name", cfg.name)
                putJsonObject("generator") { put("app", "City Builder Dream Rally"); put("appVersion", "1.0.0") }
                putJsonObject("dimensions") { put("sectors", sectorIds.size); put("widthMeters", (maxX - minX + 1) * Grid.SECTOR_M); put("depthMeters", (maxY - minY + 1) * Grid.SECTOR_M) }
                putJsonObject("units") { put("length", "meters"); put("angle", "degrees"); put("scale", 1.0) }
                putJsonObject("coordinateSystem") { put("handedness", "right"); put("up", "+Y"); put("east", "+X"); put("south", "+Z"); put("origin", "sector sec_0_0 north-west corner (cell 0,0)"); put("gltfCompatible", true) }
                putJsonObject("entryPoints") { put("city", "city.json"); put("roads", "roads.json"); put("buildings", "buildings.json"); put("materials", "materials/materials.json") }
                putJsonObject("compatibility") {
                    put("gltf", "2.0 (GLB, texturas PNG externas por URI relativa)"); put("godotMinimum", "4.2"); put("minReaderSchemaVersion", 1)
                    put("importerNote", "Requiere un importador propio en Dream Rally; el ZIP no se importa automáticamente.")
                }
                put("dependencies", JsonArray(listOf(JsonPrimitive("textures/*.png referenciadas desde geometry/**/*.glb y materials/materials.json"))))
                put("files", JsonArray(fileHash.entries.sortedBy { it.key }.map { (p, h) -> buildJsonObject { put("path", p); put("sha256", h.first); put("size", h.second) } }))
                put("selfExcluded", "manifest.json")
            }
            emit("manifest.json", prettyJson.encodeToString(JsonObject.serializer(), manifest))

            // ---- ZIP (manifest primero) ----
            check(); progress(Progress("Creando ZIP", 0.96f))
            out.parentFile?.mkdirs()
            val tmpOut = File(out.parentFile, out.name + ".part")
            ZipOutputStream(FileOutputStream(tmpOut).buffered()).use { zip ->
                zip.setLevel(6)
                val ordered = listOf("manifest.json") + fileHash.keys.filter { it != "manifest.json" }.sorted()
                for (p in ordered) {
                    val e = ZipEntry(p); e.time = 0L // reproducible
                    zip.putNextEntry(e); File(stage, p).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            if (out.exists()) out.delete()
            if (!tmpOut.renameTo(out)) throw ExportFailed(listOf("No se pudo escribir ${out.path}"))
            progress(Progress("Verificando paquete", 0.98f))
            val verify = PackageVerifier.verify(out)
            if (!verify.ok) { out.delete(); throw ExportFailed(verify.errors.map { "Verificación: $it" }) }
            progress(Progress("Listo", 1f))
            return ExportResult(out, report, warnings, fileHash.size, out.length())
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun straight(v: Vtx, inc: List<RoadEdge>): Boolean {
        if (inc.size != 2) return false
        val d = inc.map { e -> val o = if (e.a == v) e.b else e.a; (o.x - v.x) to (o.y - v.y) }
        return d[0].first == -d[1].first && d[0].second == -d[1].second
    }

    private fun roadsJson(graph: RoadGraph, cfg: CityConfig, hf: HeightField): JsonObject = buildJsonObject {
        put("schemaVersion", Format.SCHEMA_VERSION); put("units", "meters"); put("drivingSide", "right")
        put("note", "Red vial lógica. segments[].polyline sigue el sentido de circulación si oneWay. La malla visible está en geometry/sectors/<sector>_roads.glb (nodo glTF con nombre = segment id).")
        put("nodes", JsonArray(graph.nodes.values.sortedBy { it.v.id }.map { n ->
            val w = Format.world(n.v)
            buildJsonObject {
                put("id", n.v.id); put("cell", ints(n.v.x, n.v.y)); put("position", arr(w[0], hf.at(n.v) + Format.ROAD_LIFT, w[1]))
                put("sector", n.v.sector.name); put("degree", n.degree); put("kind", n.kind.name.lowercase()); put("segments", JsonArray(n.segments.map { JsonPrimitive(it) }))
            }
        }))
        put("segments", JsonArray(graph.segments.map { s ->
            var len = 0.0
            val pts = s.nodes.map { v -> val w = Format.world(v); doubleArrayOf(w[0], hf.at(v) + Format.ROAD_LIFT, w[1]) }
            for (i in 0 until pts.size - 1) len += hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][2] - pts[i][2])
            val connStart = graph.nodes[s.start]!!.segments.filter { it != s.id }
            val connEnd = graph.nodes[s.end]!!.segments.filter { it != s.id }
            buildJsonObject {
                put("id", s.id); put("type", s.type.name.lowercase()); put("sector", s.start.sector.name)
                put("startNode", s.start.id); put("endNode", s.end.id)
                put("nodeIds", JsonArray(s.nodes.map { JsonPrimitive(it.id) })); put("edgeIds", JsonArray(s.edgeIds.map { JsonPrimitive(it) }))
                put("polyline", JsonArray(pts.map { arr(it[0], it[1], it[2]) }))
                put("lengthMeters", r2(len)); put("widthMeters", s.type.width); put("lanes", s.type.lanes)
                put("oneWay", s.oneWay); put("loop", s.isLoop)
                putJsonObject("elevation") { put("mode", "terrain_following"); put("bridge", false); put("tunnel", false); put("liftMeters", Format.ROAD_LIFT) }
                putJsonObject("connections") { put("atStart", JsonArray(connStart.map { JsonPrimitive(it) })); put("atEnd", JsonArray(connEnd.map { JsonPrimitive(it) })) }
                put("intersections", JsonArray(listOf(s.start, s.end).distinct().mapNotNull { v -> graph.intersections.firstOrNull { it.node == v }?.id }.map { JsonPrimitive(it) }))
                put("roundabout", s.group?.let { JsonPrimitive(it) } ?: JsonNull)
                put("geometry", "geometry/sectors/${s.start.sector.name}_roads.glb#${s.id}")
                putJsonObject("gps") { put("roadClass", s.type.roadClass); put("speedLimitKmh", s.type.speedKmh); put("surface", if (s.type == RoadType.DIRT) "unpaved" else "asphalt") }
                putJsonObject("traffic") { put("lanesForward", if (s.oneWay) s.type.lanes else maxOf(1, s.type.lanes / 2)); put("lanesBackward", if (s.oneWay) 0 else maxOf(1, s.type.lanes / 2)); put("laneWidthMeters", r2(s.type.width / s.type.lanes.coerceAtLeast(1))) }
            }
        }))
        put("intersections", JsonArray(graph.intersections.map { i ->
            val w = Format.world(i.node)
            buildJsonObject {
                put("id", i.id); put("node", i.node.id); put("kind", i.kind); put("position", arr(w[0], hf.at(i.node) + Format.ROAD_LIFT, w[1]))
                put("segments", JsonArray(i.segments.map { JsonPrimitive(it) })); put("roundabout", i.roundabout?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }))
        put("roundabouts", JsonArray(cfg.roundabouts.map { r ->
            buildJsonObject {
                put("id", r.id); put("centerCell", ints(r.cx, r.cy)); put("centerPosition", arr(r.cx * Grid.CELL_M, hf.at(r.cx, r.cy), r.cy * Grid.CELL_M))
                put("type", r.type.name.lowercase()); put("direction", "counterclockwise_from_above"); put("islandGeometry", "geometry/sectors/${Vtx(r.cx, r.cy).sector.name}_roads.glb#island_${r.id}")
            }
        }))
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
