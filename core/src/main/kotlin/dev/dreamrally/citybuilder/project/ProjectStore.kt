package dev.dreamrally.citybuilder.project

import dev.dreamrally.citybuilder.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class ProjectFile(
    val formatVersion: Int,
    val config: CityConfig,
    val catalogUsed: List<String>,
    val sectorsOnDisk: List<SectorId> = emptyList()
)

class ProjectFormatException(msg: String, cause: Throwable? = null) : IOException(msg, cause)

/**
 * Carpeta de proyecto versionada:
 *   project.json          configuración, terreno desbloqueado, referencias de catálogo
 *   catalog.json          especificaciones de edificios usadas (proyecto autocontenido)
 *   sectors/sec_X_Y.json  edificios y carreteras de cada sector (guardado incremental)
 *   assets/               GLB de catálogo ampliado referenciados por catalog.json
 * Las escrituras son atómicas (archivo temporal + renombrado).
 */
class ProjectStore(val dir: File) : SectorSource {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val sectorsDir get() = File(dir, "sectors")

    private fun atomicWrite(f: File, text: String) {
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun loadSector(id: SectorId): SectorData? {
        val f = File(sectorsDir, "${id.name}.json")
        if (!f.exists()) return null
        return try { json.decodeFromString<SectorData>(f.readText()) } catch (e: Exception) { throw ProjectFormatException("Sector corrupto: ${f.name}", e) }
    }

    /** Guarda solo lo modificado. Con [all]=true reescribe todos los sectores cargados. */
    fun save(city: City, all: Boolean = false): Int {
        val ids = if (all) city.loadedSectors.toList() else city.dirty.toList()
        for (id in ids) {
            val data = city.sectorData(id) ?: continue
            val f = File(sectorsDir, "${id.name}.json")
            if (data.buildings.isEmpty() && data.edges.isEmpty()) f.delete() else atomicWrite(f, json.encodeToString(data))
        }
        val onDisk = sectorsDir.listFiles { x -> x.name.endsWith(".json") }?.mapNotNull { SectorId.parse(it.nameWithoutExtension) }?.sortedWith(compareBy({ it.x }, { it.y })) ?: emptyList()
        val used = (city.buildings().map { it.catalogId }).distinct().sorted()
        val specs = used.mapNotNull { city.catalog[it] }
        atomicWrite(File(dir, "catalog.json"), Catalog.JSON.encodeToString(CatalogFile(buildings = specs)))
        atomicWrite(File(dir, "project.json"), json.encodeToString(ProjectFile(FORMAT_VERSION, city.config, used, onDisk)))
        city.markClean(ids)
        return ids.size
    }

    fun create(config: CityConfig, catalog: Catalog): City {
        dir.mkdirs()
        val city = City(config, catalog, this)
        city.configDirty = true
        save(city, all = true)
        return city
    }

    fun open(baseCatalog: Catalog): City {
        val pf = File(dir, "project.json")
        if (!pf.exists()) throw ProjectFormatException("No existe project.json en ${dir.path}")
        val proj = try { json.decodeFromString<ProjectFile>(pf.readText()) } catch (e: Exception) { throw ProjectFormatException("project.json inválido", e) }
        if (proj.formatVersion > FORMAT_VERSION) throw ProjectFormatException("Proyecto creado con una versión más nueva (${proj.formatVersion})")
        val cf = File(dir, "catalog.json")
        val catalog = if (cf.exists()) baseCatalog.plus(Catalog.fromJson(cf.readText()).all.filter { baseCatalog[it.id] == null }) else baseCatalog
        return City(proj.config, catalog, this)
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun listProjects(root: File): List<Pair<File, CityConfig>> =
            (root.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { d ->
                try { d to Json { ignoreUnknownKeys = true }.decodeFromString<ProjectFile>(File(d, "project.json").readText()).config } catch (_: Exception) { null }
            }.sortedBy { it.second.name.lowercase() }
    }
}
