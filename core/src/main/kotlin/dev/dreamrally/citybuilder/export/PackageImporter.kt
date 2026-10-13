package dev.dreamrally.citybuilder.export

import dev.dreamrally.citybuilder.model.*
import kotlinx.serialization.json.*

class PackageImportException(msg: String) : Exception(msg)

/** Reconstruye la lógica de la ciudad (edificios + red vial) a partir de un DreamCity Package. Sirve de referencia para el importador de Godot. */
object PackageImporter {
    class Imported(val cityId: String, val name: String, val buildings: List<PlacedBuilding>, val edges: List<RoadEdge>, val roundabouts: List<Roundabout>, val sectors: Set<SectorId>, val biome: Biome, val relief: Relief, val reliefSeed: Int)

    fun import(pkg: PackageContents): Imported {
        val rep = PackageVerifier.verify(pkg)
        if (!rep.ok) throw PackageImportException("Paquete inválido:\n" + rep.errors.joinToString("\n") { "• $it" })
        fun js(p: String) = Json.parseToJsonElement(String(pkg.files[p]!!, Charsets.UTF_8)).jsonObject
        val city = js("city.json"); val roads = js("roads.json"); val b = js("buildings.json")
        val sectors = city["sectors"]!!.jsonArray.map { SectorId(it.jsonObject["sx"]!!.jsonPrimitive.int, it.jsonObject["sy"]!!.jsonPrimitive.int) }.toSet()
        val t = city["terrain"]!!.jsonObject
        val buildings = b["buildings"]!!.jsonArray.map {
            val o = it.jsonObject; val cell = o["cell"]!!.jsonArray
            PlacedBuilding(o["id"]!!.jsonPrimitive.content, o["catalogId"]!!.jsonPrimitive.content, cell[0].jsonPrimitive.int, cell[1].jsonPrimitive.int, o["rot"]!!.jsonPrimitive.int, o["level"]?.jsonPrimitive?.int ?: 0, o["variant"]?.jsonPrimitive?.int ?: 0)
        }
        val edges = ArrayList<RoadEdge>()
        for (s in roads["segments"]!!.jsonArray) {
            val o = s.jsonObject; val type = RoadType.valueOf(o["type"]!!.jsonPrimitive.content.uppercase())
            val oneWay = o["oneWay"]!!.jsonPrimitive.boolean; val group = o["roundabout"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val cells = o["nodeIds"]!!.jsonArray.map { id -> val m = Regex("n_(-?\\d+)_(-?\\d+)").matchEntire(id.jsonPrimitive.content)!!; Vtx(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
            for (i in 0 until cells.size - 1) edges += RoadEdge.of(cells[i], cells[i + 1], type, if (oneWay) 1 else 0, group)
        }
        val rbs = roads["roundabouts"]!!.jsonArray.map { val o = it.jsonObject; Roundabout(o["id"]!!.jsonPrimitive.content, o["centerCell"]!!.jsonArray[0].jsonPrimitive.int, o["centerCell"]!!.jsonArray[1].jsonPrimitive.int, RoadType.valueOf(o["type"]!!.jsonPrimitive.content.uppercase())) }
        return Imported(city["cityId"]!!.jsonPrimitive.content, city["name"]!!.jsonPrimitive.content, buildings, edges, rbs, sectors,
            Biome.valueOf(t["biome"]!!.jsonPrimitive.content.uppercase()), Relief.valueOf(t["relief"]!!.jsonPrimitive.content.uppercase()), t["reliefSeed"]!!.jsonPrimitive.int)
    }

    fun toCity(imp: Imported, catalog: Catalog): City {
        val cfg = CityConfig(imp.cityId, imp.name, imp.biome, imp.relief, imp.reliefSeed, unlocked = imp.sectors,
            nextBuildingId = (imp.buildings.maxOfOrNull { it.id.drop(1).toIntOrNull() ?: 0 } ?: 0) + 1,
            nextRoundaboutId = (imp.roundabouts.maxOfOrNull { it.id.drop(2).toIntOrNull() ?: 0 } ?: 0) + 1, roundabouts = imp.roundabouts)
        val city = City(cfg, catalog)
        for (s in imp.sectors) city.install(SectorData(s, imp.buildings.filter { it.sector == s }, imp.edges.filter { it.sector == s }))
        return city
    }
}
