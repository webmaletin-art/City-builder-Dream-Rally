package dev.dreamrally.citybuilder.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class Category(val label: String) {
    RESIDENTIAL("Residencial"), COMMERCIAL("Comercial"), INDUSTRIAL("Industrial"),
    PUBLIC("Servicios públicos"), OFFICE("Oficinas"), PARKS("Parques y plazas"),
    UTILITY("Instalaciones urbanas"), LANDMARK("Emblemáticos"), DECOR("Decoración"),
    ROAD_INFRA("Infraestructura vial")
}

/**
 * Descripción del modelo 3D. kind="procedural": se genera la malla desde parámetros (recursos propios).
 * kind="glb": referencia a un GLB externo (carpeta de catálogo ampliado, ver docs); se copia tal cual al paquete.
 */
@Serializable
data class ModelSpec(
    val kind: String = "procedural",
    val shape: String = "box",        // box | gable | pyramid | stepped | tree | pine | palm | bush | park | plaza | fountain | lamp | silo | chimney
    val heightM: Double = 8.0,
    val wallRgb: Int = 0xCCCCCC,
    val roofRgb: Int = 0x8A4B3A,
    val facade: String = "residential", // residential | commercial | industrial | office | civic | none
    val glbPath: String? = null
)

@Serializable
data class PlacementRules(
    val needsRoad: Boolean = false,       // si true, la edición avisa (no bloquea) cuando no toca una carretera
    val maxSlopeM: Double = 6.0
)

@Serializable
data class BuildingSpec(
    val id: String,
    val name: String,
    val category: Category,
    val widthCells: Int,
    val depthCells: Int,
    val model: ModelSpec,
    val placement: PlacementRules = PlacementRules(),
    val buildingType: String,
    val origin: String = "footprint_center_base", // origen del modelo: centro de la huella, a nivel del suelo
    val meta: Map<String, String> = emptyMap()
) {
    fun footprintW(rot: Int) = if (rot % 2 == 0) widthCells else depthCells
    fun footprintD(rot: Int) = if (rot % 2 == 0) depthCells else widthCells
}

@Serializable
data class CatalogFile(val formatVersion: Int = 1, val buildings: List<BuildingSpec>)

class Catalog(specs: Collection<BuildingSpec>) {
    private val map = LinkedHashMap<String, BuildingSpec>().also { m -> specs.forEach { m[it.id] = it } }
    val all: List<BuildingSpec> get() = map.values.toList()
    operator fun get(id: String): BuildingSpec? = map[id]
    fun byCategory(c: Category) = map.values.filter { it.category == c }
    fun plus(extra: Collection<BuildingSpec>) = Catalog(map.values + extra)

    fun toJson(): String = JSON.encodeToString(CatalogFile(buildings = all))

    companion object {
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): Catalog = Catalog(JSON.decodeFromString<CatalogFile>(text).buildings)
    }
}

/** Catálogo base con recursos propios (formas procedurales). Extensible por JSON + GLB sin tocar el editor. */
object DefaultCatalog {
    private fun b(
        id: String, name: String, cat: Category, w: Int, d: Int, type: String, shape: String, h: Double,
        wall: Int, roof: Int, facade: String, needsRoad: Boolean = true, meta: Map<String, String> = emptyMap()
    ) = BuildingSpec(id, name, cat, w, d, ModelSpec("procedural", shape, h, wall, roof, facade), PlacementRules(needsRoad), type, meta = meta)

    val catalog: Catalog by lazy {
        Catalog(listOf(
            // Residencial
            b("res_house_small", "Casa pequeña", Category.RESIDENTIAL, 1, 1, "house", "gable", 6.0, 0xF2E3C6, 0xB5533C, "residential", meta = mapOf("zone" to "residential", "density" to "low")),
            b("res_house_medium", "Casa mediana", Category.RESIDENTIAL, 1, 1, "house", "gable", 7.5, 0xE8D2B0, 0x8E3B2E, "residential", meta = mapOf("zone" to "residential", "density" to "low")),
            b("res_house_blue", "Casa azul", Category.RESIDENTIAL, 1, 1, "house", "gable", 7.0, 0xA9C4E0, 0x44566B, "residential", meta = mapOf("zone" to "residential", "density" to "low")),
            b("res_duplex", "Dúplex", Category.RESIDENTIAL, 2, 1, "house", "gable", 7.5, 0xF0DCC0, 0x9C4A38, "residential", meta = mapOf("zone" to "residential", "density" to "low")),
            b("res_townhouse", "Casa adosada", Category.RESIDENTIAL, 2, 1, "house", "box", 9.0, 0xD9A58C, 0x6B4A3E, "residential", meta = mapOf("zone" to "residential", "density" to "medium")),
            b("res_apartment_low", "Edificio bajo", Category.RESIDENTIAL, 2, 2, "apartment", "box", 14.0, 0xE3D8C8, 0x7A7A7A, "residential", meta = mapOf("zone" to "residential", "density" to "medium")),
            b("res_apartment_mid", "Edificio de apartamentos", Category.RESIDENTIAL, 2, 2, "apartment", "box", 24.0, 0xC9BBA6, 0x6E6E6E, "residential", meta = mapOf("zone" to "residential", "density" to "high")),
            b("res_tower", "Torre residencial", Category.RESIDENTIAL, 2, 2, "apartment", "stepped", 42.0, 0xB7C3CC, 0x5C6770, "office", meta = mapOf("zone" to "residential", "density" to "high")),
            // Comercial
            b("com_shop", "Tienda", Category.COMMERCIAL, 1, 1, "shop", "box", 6.0, 0xF5C95B, 0xA83A32, "commercial", meta = mapOf("zone" to "commercial")),
            b("com_bakery", "Panadería", Category.COMMERCIAL, 1, 1, "shop", "gable", 6.5, 0xF2B38A, 0x7E3F2E, "commercial", meta = mapOf("zone" to "commercial")),
            b("com_supermarket", "Supermercado", Category.COMMERCIAL, 3, 2, "market", "box", 7.0, 0xE9EEF2, 0x3B7A57, "commercial", meta = mapOf("zone" to "commercial")),
            b("com_mall", "Centro comercial", Category.COMMERCIAL, 4, 3, "mall", "box", 12.0, 0xDCE3EA, 0x445566, "commercial", meta = mapOf("zone" to "commercial")),
            b("com_gas_station", "Estación de servicio", Category.COMMERCIAL, 2, 2, "gas_station", "box", 5.0, 0xE04B3C, 0xF2F2F2, "commercial", meta = mapOf("zone" to "commercial", "service" to "fuel")),
            b("com_restaurant", "Restaurante", Category.COMMERCIAL, 2, 1, "restaurant", "gable", 6.5, 0xF7E1B5, 0x8C3B2A, "commercial", meta = mapOf("zone" to "commercial")),
            // Industrial
            b("ind_factory", "Fábrica", Category.INDUSTRIAL, 3, 2, "factory", "box", 10.0, 0xB9B4A8, 0x6B6B6B, "industrial", meta = mapOf("zone" to "industrial")),
            b("ind_warehouse", "Almacén", Category.INDUSTRIAL, 3, 2, "warehouse", "gable", 9.0, 0xA8B6C2, 0x5F6B75, "industrial", meta = mapOf("zone" to "industrial")),
            b("ind_silo", "Silo", Category.INDUSTRIAL, 1, 1, "silo", "silo", 18.0, 0xD7DADD, 0x9AA0A6, "none", needsRoad = false, meta = mapOf("zone" to "industrial")),
            b("ind_chimney_plant", "Planta con chimenea", Category.INDUSTRIAL, 3, 3, "factory", "chimney", 12.0, 0xA89F94, 0x5A5A5A, "industrial", meta = mapOf("zone" to "industrial")),
            // Servicios públicos
            b("pub_police", "Comisaría", Category.PUBLIC, 2, 2, "police", "box", 9.0, 0x4F6FA8, 0x2E3F63, "civic", meta = mapOf("service" to "police")),
            b("pub_fire", "Bomberos", Category.PUBLIC, 2, 2, "fire_station", "box", 9.0, 0xC8382E, 0x7A1F1A, "civic", meta = mapOf("service" to "fire")),
            b("pub_hospital", "Hospital", Category.PUBLIC, 3, 3, "hospital", "box", 16.0, 0xF3F5F7, 0xB8C2CC, "civic", meta = mapOf("service" to "health")),
            b("pub_school", "Escuela", Category.PUBLIC, 3, 2, "school", "box", 8.0, 0xE9C46A, 0x8B5A2B, "civic", meta = mapOf("service" to "education")),
            b("pub_townhall", "Ayuntamiento", Category.PUBLIC, 3, 2, "town_hall", "stepped", 14.0, 0xE8E2D4, 0x6D6A60, "civic", meta = mapOf("service" to "government")),
            b("pub_library", "Biblioteca", Category.PUBLIC, 2, 2, "library", "box", 8.0, 0xD9C8B0, 0x7C5C45, "civic", meta = mapOf("service" to "culture")),
            // Oficinas
            b("off_small", "Oficinas pequeñas", Category.OFFICE, 2, 2, "office", "box", 20.0, 0x9DB4C8, 0x56677A, "office", meta = mapOf("zone" to "office")),
            b("off_tower", "Torre de oficinas", Category.OFFICE, 2, 2, "stepped", "stepped", 60.0, 0x7FA2BF, 0x44596B, "office", meta = mapOf("zone" to "office")),
            b("off_skyscraper", "Rascacielos", Category.OFFICE, 3, 3, "office", "stepped", 90.0, 0x6E93B3, 0x3A4E60, "office", meta = mapOf("zone" to "office")),
            // Parques
            b("park_small", "Parque pequeño", Category.PARKS, 2, 2, "park", "park", 0.3, 0x6FB04A, 0x6FB04A, "none", needsRoad = false),
            b("park_large", "Parque grande", Category.PARKS, 4, 4, "park", "park", 0.3, 0x6FB04A, 0x6FB04A, "none", needsRoad = false),
            b("plaza_stone", "Plaza de piedra", Category.PARKS, 2, 2, "plaza", "plaza", 0.2, 0xC9C2B4, 0xC9C2B4, "none", needsRoad = false),
            b("plaza_fountain", "Plaza con fuente", Category.PARKS, 3, 3, "plaza", "fountain", 2.0, 0xC9C2B4, 0x8FC1E3, "none", needsRoad = false),
            // Instalaciones urbanas
            b("utl_power", "Planta eléctrica", Category.UTILITY, 3, 2, "power_plant", "chimney", 14.0, 0xB5B0A5, 0x6A6A6A, "industrial", meta = mapOf("service" to "power")),
            b("utl_water", "Torre de agua", Category.UTILITY, 1, 1, "water_tower", "silo", 16.0, 0x8FB5D9, 0x5C7C99, "none", needsRoad = false, meta = mapOf("service" to "water")),
            b("utl_substation", "Subestación", Category.UTILITY, 2, 1, "substation", "box", 5.0, 0x9A9FA6, 0x5A5F66, "industrial", meta = mapOf("service" to "power")),
            // Emblemáticos
            b("lmk_tower", "Torre emblemática", Category.LANDMARK, 3, 3, "landmark", "stepped", 120.0, 0xD9D3C4, 0x8A8575, "office", needsRoad = false),
            b("lmk_stadium", "Estadio", Category.LANDMARK, 5, 4, "stadium", "box", 18.0, 0xE5E8EB, 0x8C95A0, "civic", needsRoad = false),
            // Decoración
            b("dec_tree_oak", "Roble", Category.DECOR, 1, 1, "tree", "tree", 9.0, 0x6B4A2B, 0x3F8F3A, "none", needsRoad = false),
            b("dec_tree_pine", "Pino", Category.DECOR, 1, 1, "tree", "pine", 11.0, 0x5A4026, 0x2B6B3A, "none", needsRoad = false),
            b("dec_tree_palm", "Palmera", Category.DECOR, 1, 1, "tree", "palm", 10.0, 0x8A6A45, 0x3FA44A, "none", needsRoad = false),
            b("dec_bush", "Arbusto", Category.DECOR, 1, 1, "bush", "bush", 1.5, 0x5C4030, 0x4C9A3F, "none", needsRoad = false),
            b("dec_lamp", "Farola", Category.DECOR, 1, 1, "lamp", "lamp", 6.0, 0x3C3F44, 0xFFE9A8, "none", needsRoad = false),
            // Infraestructura vial
            b("rd_busstop", "Parada de autobús", Category.ROAD_INFRA, 1, 1, "bus_stop", "box", 3.0, 0x3B7EA1, 0x2E5E78, "none", needsRoad = false),
            b("rd_toll", "Peaje", Category.ROAD_INFRA, 2, 1, "toll", "box", 5.0, 0xE0E0E0, 0xC83B2E, "none", needsRoad = false),
            b("rd_parking", "Estacionamiento", Category.ROAD_INFRA, 2, 2, "parking", "plaza", 0.1, 0x4A4D52, 0x4A4D52, "none", needsRoad = false)
        ))
    }
}
