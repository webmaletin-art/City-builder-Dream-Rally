package dev.dreamrally.citybuilder

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dreamrally.citybuilder.demo.DemoCity
import dev.dreamrally.citybuilder.export.*
import dev.dreamrally.citybuilder.model.*
import dev.dreamrally.citybuilder.project.ProjectStore
import dev.dreamrally.citybuilder.ui.Camera
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

enum class Tool(val label: String, val icon: String) {
    HAND("Mover vista", "✋"), BUILD("Construir", "🏠"), ROAD("Carretera", "🛣"), SELECT("Seleccionar", "👆"), ERASE("Borrar", "🗑"), EXPAND("Terreno", "🗺")
}

sealed interface ExportUi {
    data class Confirm(val warnings: List<String>, val errors: List<String>) : ExportUi
    data class Running(val stage: String, val fraction: Float) : ExportUi
    data class Done(val file: File, val summary: String, val warnings: List<String>) : ExportUi
    data class Failed(val message: String) : ExportUi
}

class AppModel(app: Application) : AndroidViewModel(app) {
    val projectsDir = File(app.filesDir, "projects").also { it.mkdirs() }
    val exportDir = File(app.cacheDir, "exports").also { it.mkdirs() }
    val extCatalogDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "catalog_ext")

    /** Catálogo base + extensiones opcionales (catalog_ext/catalog.json con GLB propios/autorizados). */
    val catalog: Catalog by lazy {
        val f = File(extCatalogDir, "catalog.json")
        try { if (f.exists()) DefaultCatalog.catalog.plus(Catalog.fromJson(f.readText()).all.filter { DefaultCatalog.catalog[it.id] == null }) else DefaultCatalog.catalog }
        catch (e: Exception) { catalogError = "catalog_ext/catalog.json inválido: ${e.message}"; DefaultCatalog.catalog }
    }
    var catalogError by mutableStateOf<String?>(null)

    // ---------- proyectos ----------
    var projects by mutableStateOf(listOf<Pair<File, CityConfig>>())
    fun refreshProjects() { projects = ProjectStore.listProjects(projectsDir) }
    init { refreshProjects() }

    var store: ProjectStore? = null
    var city by mutableStateOf<City?>(null)
    var revision by mutableIntStateOf(0)
    val cam = Camera()
    var camTick by mutableIntStateOf(0)

    fun createProject(name: String, biome: Biome, relief: Relief, demo: Boolean) {
        val dir = File(projectsDir, UUID.randomUUID().toString().take(8)); val st = ProjectStore(dir)
        val cfg = CityConfig(UUID.randomUUID().toString(), name.ifBlank { "Mi ciudad" }, biome, relief, reliefSeed = (Math.random() * 100000).toInt())
        if (demo) {
            val d = DemoCity.build(catalog, biome, relief)
            val c = st.create(d.config.copy(cityId = cfg.cityId, name = cfg.name), catalog)
            d.config.unlocked.forEach { id -> d.sectorData(id)?.let { c.install(it) } }
            st.save(c, all = true); c.clearMemory()
        } else st.create(cfg, catalog).clearMemory()
        refreshProjects(); openProject(dir)
    }

    fun openProject(dir: File) {
        closeProject()
        val st = ProjectStore(dir)
        try { val c = st.open(catalog); store = st; city = c; resetEditor(); recenter(); loadVisible() }
        catch (e: Exception) { toast = "No se pudo abrir: ${e.message}" }
    }

    fun deleteProject(dir: File) { dir.deleteRecursively(); refreshProjects() }

    fun closeProject() {
        saveNow(); city?.clearMemory(); city = null; store = null; exportUi = null; refreshProjects(); System.gc()
    }

    fun saveNow(): Boolean = try { val c = city; val s = store; if (c != null && s != null) { s.save(c); true } else false } catch (e: Exception) { toast = "Error al guardar: ${e.message}"; false }

    // ---------- herramientas / estado de edición ----------
    var tool by mutableStateOf(Tool.HAND)
    var toast by mutableStateOf<String?>(null)
    var selectedCatalogId by mutableStateOf<String?>(null)
    var category by mutableStateOf(Category.RESIDENTIAL)
    var rot by mutableIntStateOf(0)
    var ghost by mutableStateOf<Pair<Int, Int>?>(null)
    var ghostMessage by mutableStateOf<String?>(null)
    var ghostValid by mutableStateOf(false)
    var roadType by mutableStateOf(RoadType.STREET)
    var oneWay by mutableStateOf(false)
    var roundaboutMode by mutableStateOf(false)
    var roadPreview by mutableStateOf<List<Vtx>>(emptyList())
    var selectedBuilding by mutableStateOf<String?>(null)
    var selectedEdge by mutableStateOf<String?>(null)
    var moving by mutableStateOf(false)
    var replacing by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var info by mutableStateOf("")
    var autoRotate by mutableStateOf(true)          // girar solo para que el frente mire a la calle
    val popAnim = HashMap<String, Long>()            // id edificio -> inicio de la animación (ms)
    var animTick by mutableIntStateOf(0)
    fun startPop(id: String?) { if (id != null) { popAnim[id] = System.currentTimeMillis(); animTick++ } }

    fun resetEditor() { tool = Tool.HAND; ghost = null; roadPreview = emptyList(); selectedBuilding = null; selectedEdge = null; moving = false; replacing = false; toast = null }
    fun recenter() { cam.cx = 0f; cam.cy = 0f; camTick++ }
    fun bump() { revision++ }

    fun setToolTo(t: Tool) { tool = t; ghost = null; roadPreview = emptyList(); moving = false; replacing = false; if (t != Tool.SELECT) { selectedBuilding = null; selectedEdge = null } }

    // ---------- carga diferida de sectores ----------
    fun loadVisible() {
        val c = city ?: return
        val r = cam.visibleCells(2)
        val sx0 = Grid.sectorOf(r[0]); val sy0 = Grid.sectorOf(r[1]); val sx1 = Grid.sectorOf(r[2]); val sy1 = Grid.sectorOf(r[3])
        val want = HashSet<SectorId>()
        for (x in sx0 - 1..sx1 + 1) for (y in sy0 - 1..sy1 + 1) want += SectorId(x, y)
        c.ensureLoaded(want.filter { it in c.config.unlocked })
        if (c.loadedSectors.size > 30) { val freed = c.trim(want); if (freed > 0) System.gc() } // liberar lejanos (no sucios)
    }

    // ---------- acciones ----------
    fun cellAt(sx: Float, sy: Float): Pair<Int, Int> { val (x, y) = cam.unproject(sx, sy); return Math.floor(x.toDouble()).toInt() to Math.floor(y.toDouble()).toInt() }
    fun vertexAt(sx: Float, sy: Float): Vtx { val (x, y) = cam.unproject(sx, sy); return Vtx(Math.round(x), Math.round(y)) }

    fun updateGhost(cell: Pair<Int, Int>) {
        val c = city ?: return
        ghost = cell
        val id = if (moving) c.building(selectedBuilding ?: "")?.catalogId else selectedCatalogId
        val spec = id?.let { catalog[it] } ?: run { ghostValid = false; ghostMessage = "Elegí un edificio del catálogo"; return }
        val ignore = if (moving) selectedBuilding else null
        if (autoRotate) rot = c.bestRotation(spec, cell.first, cell.second, rot)
        val chk = c.canPlace(spec, cell.first, cell.second, rot, ignore)
        ghostValid = chk.ok; ghostMessage = if (chk.ok) chk.warnings.firstOrNull() else chk.message
        info = "Celda ${cell.first}, ${cell.second}  •  altura ${"%.1f".format(Heights.at(c.config.relief, c.config.reliefSeed, cell.first, cell.second))} m"
    }

    fun confirmGhost() {
        val c = city ?: return; val g = ghost ?: return
        if (moving) {
            val id = selectedBuilding ?: return
            val r = c.moveBuilding(id, g.first, g.second, rot)
            toast = if (r.ok) "Movido" else r.message; if (r.ok) { startPop(id); moving = false; ghost = null }
        } else {
            val id = selectedCatalogId ?: run { toast = "Elegí un edificio del catálogo"; return }
            val r = c.placeBuilding(id, g.first, g.second, rot)
            toast = if (r.ok) (r.warnings.firstOrNull() ?: "Colocado") else r.message
            if (r.ok) startPop(r.id)
        }
        bump(); updateGhost(g)
    }

    fun tapSelect(sx: Float, sy: Float) {
        val c = city ?: return
        val (cx, cy) = cellAt(sx, sy)
        val b = c.buildingAt(cx, cy) ?: run {
            // buscar edificio alto cuya silueta cubre el punto: probar celdas hacia abajo en pantalla
            (1..6).firstNotNullOfOrNull { k -> val (x2, y2) = cellAt(sx, sy + k * cam.zoom * 0.25f); c.buildingAt(x2, y2)?.takeIf { (c.catalog[it.catalogId]?.model?.heightM ?: 0.0) > 8.0 * k * 0.5 } }
        }
        if (b != null) { selectedBuilding = b.id; selectedEdge = null; replacing = false; return }
        val v = vertexAt(sx, sy)
        val near = c.edgesAt(v).firstOrNull() ?: (-1..1).flatMap { dx -> (-1..1).flatMap { dy -> c.edgesAt(Vtx(v.x + dx, v.y + dy)) } }.minByOrNull { e ->
            val (px, py) = cam.unproject(sx, sy); val mx = (e.a.x + e.b.x) / 2f; val my = (e.a.y + e.b.y) / 2f; (px - mx) * (px - mx) + (py - my) * (py - my) }
        selectedEdge = near?.key; selectedBuilding = null
    }

    fun selectedEdgeObj(): RoadEdge? { val c = city ?: return null; val k = selectedEdge ?: return null; return c.edges().firstOrNull { it.key == k } }

    fun commitRoad() {
        val c = city ?: return
        val p = roadPreview; roadPreview = emptyList()
        if (p.size < 2) return
        val r = c.drawRoad(p, roadType, if (oneWay) 1 else 0)
        toast = if (r.ok) (if (r.warnings.isNotEmpty()) "${r.message} (${r.warnings.size} omitidos: ${r.warnings.first().substringAfter(": ")})" else r.message) else (r.warnings.firstOrNull()?.substringAfter(": ") ?: r.message)
        bump()
    }

    fun placeRoundabout(sx: Float, sy: Float) {
        val c = city ?: return; val v = vertexAt(sx, sy)
        val r = c.addRoundabout(v.x, v.y, roadType); toast = if (r.ok) "Rotonda creada" else r.message; bump()
    }

    fun eraseRoad(path: List<Vtx>) { val c = city ?: return; if (path.size >= 2) { val r = c.removeRoadAlong(path); toast = r.message; bump() } }
    fun eraseAt(sx: Float, sy: Float) {
        val c = city ?: return; val (cx, cy) = cellAt(sx, sy)
        val b = c.buildingAt(cx, cy)
        if (b != null) { c.removeBuilding(b.id); toast = "Edificio eliminado"; bump(); return }
        val v = vertexAt(sx, sy); val r = c.removeEdgesNear(v); toast = if (r.ok) "Carretera eliminada" else "Nada que borrar"; if (r.ok) bump()
    }

    fun unlockAt(sx: Float, sy: Float) {
        val c = city ?: return; val (cx, cy) = cellAt(sx, sy)
        val r = c.unlockSector(Grid.sectorKey(cx, cy)); toast = if (r.ok) "Terreno ampliado" else r.message; if (r.ok) { bump(); loadVisible() }
    }

    fun undo() { city?.let { if (it.undo()) bump() } }
    fun redo() { city?.let { if (it.redo()) bump() } }

    // ---------- exportación ----------
    var exportUi by mutableStateOf<ExportUi?>(null)
    private var exportJob: Job? = null

    private fun snapshotInput(c: City): ExportInput {
        val snap = c.config.unlocked.associateWith { c.readSector(it) }
        val st = store
        return ExportInput(c.config, c.catalog, { snap[it] }, { path -> st?.let { File(it.dir, "assets/$path") }?.takeIf { it.exists() }?.readBytes()
            ?: File(extCatalogDir, path).takeIf { it.exists() }?.readBytes() })
    }

    fun requestExport() {
        val c = city ?: return
        saveNow()
        val (errors, warnings) = PackageExporter.validate(snapshotInput(c))
        exportUi = ExportUi.Confirm(warnings, errors)
    }

    fun cancelExport() { exportJob?.cancel(); if (exportUi is ExportUi.Running) exportUi = null }

    fun runExport() {
        val c = city ?: return
        val input = snapshotInput(c)
        val out = File(exportDir, "${c.config.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.dreamcity.zip")
        exportUi = ExportUi.Running("Preparando…", 0f)
        exportJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val res = PackageExporter.export(input, out, { p -> exportUi = ExportUi.Running(p.stage, p.fraction) }, { !isActive })
                val r = res.report
                val summary = "Sectores: ${r["sectors"]}  •  Edificios: ${r["buildings"]}  •  Segmentos viales: ${r["roadSegments"]}  •  Intersecciones: ${r["intersections"]}\n" +
                    "Archivos en el ZIP: ${res.entries + 1}  •  Tamaño: ${"%.2f".format(res.sizeBytes / 1048576.0)} MB\nPaquete verificado (CRC, SHA-256, referencias y texturas)."
                exportUi = ExportUi.Done(res.file, summary, res.warnings)
            } catch (e: ExportCancelled) { exportUi = null; out.delete() }
            catch (e: ExportFailed) { exportUi = ExportUi.Failed(e.message ?: "Error de exportación") }
            catch (e: OutOfMemoryError) { exportUi = ExportUi.Failed("Memoria insuficiente. Probá exportar una ciudad más pequeña o cerrar otras apps.") }
            catch (e: Exception) { exportUi = ExportUi.Failed("Error inesperado: ${e.message}") }
        }
    }

    fun copyExportTo(src: File, resolver: android.content.ContentResolver, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try { resolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }; toast = "Guardado en el teléfono (${src.length() / 1024} KB)" }
            catch (e: Exception) { toast = "No se pudo guardar: ${e.message}" }
        }
    }

    override fun onCleared() { saveNow() }
}
