package dev.dreamrally.citybuilder

import dev.dreamrally.citybuilder.model.*
import kotlin.test.*

class CityTest {
    private fun newCity() = City(CityConfig("t", "Test"), DefaultCatalog.catalog).also { it.ensureLoaded(it.config.unlocked) }

    @Test fun createsEmptyCityWithSectors() {
        val c = newCity()
        assertEquals(4, c.config.unlocked.size)
        assertTrue(c.buildings().isEmpty())
    }

    @Test fun placesBuildingsAndRejectsOverlapAndOutOfBounds() {
        val c = newCity()
        val a = c.placeBuilding("res_house_small", 0, 0)
        assertTrue(a.ok); assertEquals("b1", a.id)
        assertFalse(c.placeBuilding("res_house_small", 0, 0).ok, "solape")
        assertFalse(c.placeBuilding("res_house_small", 40, 40).ok, "fuera del terreno")
        assertTrue(c.placeBuilding("com_mall", 2, 2).ok) // 4x3
        assertFalse(c.placeBuilding("com_shop", 5, 3).ok, "dentro del mall")
        assertEquals(2, c.buildings().size)
    }

    @Test fun rotateMoveDeleteReplace() {
        val c = newCity()
        val id = c.placeBuilding("res_duplex", 0, 0).id!!   // 2x1
        assertTrue(c.rotateBuilding(id).ok)
        assertEquals(1, c.building(id)!!.rot)
        assertTrue(c.buildingAt(0, 1) != null, "tras girar ocupa 1x2")
        assertTrue(c.moveBuilding(id, 5, 5).ok); assertNull(c.buildingAt(0, 0))
        assertTrue(c.replaceBuilding(id, "res_house_small").ok)
        assertEquals("res_house_small", c.building(id)!!.catalogId)
        assertTrue(c.removeBuilding(id).ok); assertTrue(c.buildings().isEmpty())
    }

    @Test fun undoRedo() {
        val c = newCity()
        c.placeBuilding("res_house_small", 0, 0); c.placeBuilding("res_house_small", 3, 0)
        c.drawRoad(listOf(Vtx(0, 5), Vtx(6, 5)), RoadType.STREET)
        val edges = c.edges().size; assertEquals(6, edges)
        assertTrue(c.undo()); assertEquals(0, c.edges().size)
        assertTrue(c.undo()); assertEquals(1, c.buildings().size)
        assertTrue(c.redo()); assertEquals(2, c.buildings().size)
        assertTrue(c.redo()); assertEquals(6, c.edges().size)
        assertFalse(c.redo())
    }

    @Test fun roadCreatesStableConnectedNetworkAndIntersection() {
        val c = newCity()
        c.drawRoad(listOf(Vtx(-4, 0), Vtx(4, 0)), RoadType.STREET)
        c.drawRoad(listOf(Vtx(0, -4), Vtx(0, 4)), RoadType.STREET)
        val g = RoadGraph(c.edges())
        assertEquals(1, g.components)
        val inter = g.intersections.single()
        assertEquals("cross", inter.kind); assertEquals(Vtx(0, 0), inter.node)
        assertEquals(4, g.segments.size, "4 brazos entre el cruce y los extremos")
        assertTrue(g.nodes.values.none { it.kind == NodeKind.THROUGH && it.segments.size > 1 })
        // ids estables al reconstruir el grafo
        assertEquals(g.segments.map { it.id }, RoadGraph(c.edges().shuffled()).segments.map { it.id })
        assertEquals("e_-4_0_-3_0", c.edges().minOf { it.id }.let { c.edge(Vtx(-4, 0), Vtx(-3, 0))!!.id })
    }

    @Test fun invalidConnectionsAreDetected() {
        val c = newCity()
        assertTrue(c.drawRoad(listOf(Vtx(0, 0), Vtx(1, 1)), RoadType.STREET).ok)
        val cross = c.drawRoad(listOf(Vtx(1, 0), Vtx(0, 1)), RoadType.STREET)
        assertFalse(cross.ok, "diagonales opuestas sin vértice"); assertTrue(cross.warnings.first().contains("Cruce"))
        assertFalse(c.canBuildEdge(Vtx(0, 0), Vtx(3, 0)).ok, "no elemental")
        assertFalse(c.canBuildEdge(Vtx(100, 100), Vtx(101, 100)).ok, "fuera de terreno")
        c.placeBuilding("res_house_small", 5, 5)
        assertFalse(c.canBuildEdge(Vtx(5, 5), Vtx(6, 6)).ok, "diagonal sobre un edificio")
        assertTrue(c.canBuildEdge(Vtx(5, 5), Vtx(6, 5)).ok, "el borde de la huella es transitable")
        c.placeBuilding("com_mall", 8, 8) // 4x3
        assertFalse(c.canBuildEdge(Vtx(9, 9), Vtx(10, 9)).ok, "arista interior de un edificio")
        assertFalse(c.placeBuilding("res_house_small", 0, 0).ok, "edificio sobre diagonal")
    }

    @Test fun roadBuildingAdjacency() {
        val c = newCity()
        c.drawRoad(listOf(Vtx(0, 0), Vtx(5, 0)), RoadType.STREET)
        val wrongSide = c.placeBuilding("res_house_small", 1, 0, 0)            // la calle queda al norte, el frente mira al sur
        assertTrue(wrongSide.ok); assertTrue(wrongSide.warnings.single().contains("frente"))
        c.removeBuilding(wrongSide.id!!)
        val ok = c.placeBuilding("res_house_small", 1, 0, 2)                   // frente al norte, hacia la calle
        assertTrue(ok.ok); assertTrue(ok.warnings.isEmpty())
        val far = c.placeBuilding("res_house_small", 1, 6)
        assertTrue(far.ok); assertTrue(far.warnings.single().contains("carretera"))
    }

    @Test fun roundaboutAndSegmentOps() {
        val c = newCity()
        val r = c.addRoundabout(0, 0, RoadType.STREET)
        assertTrue(r.ok)
        assertEquals(12, c.edges().size)
        assertTrue(c.edges().all { it.group == "rb1" && it.oneWay != 0 })
        c.drawRoad(listOf(Vtx(2, 0), Vtx(8, 0)), RoadType.AVENUE)
        val g = RoadGraph(c.edges())
        assertEquals(1, g.intersections.count { it.kind == "roundabout_entry" })
        assertEquals(1, g.components)
        assertFalse(c.placeBuilding("res_house_small", 0, 0).ok, "isla de la rotonda")
        val ave = c.edge(Vtx(3, 0), Vtx(4, 0))!!
        assertTrue(c.retypeSegment(ave, RoadType.HIGHWAY).ok)
        assertEquals(RoadType.HIGHWAY, c.edge(Vtx(7, 0), Vtx(8, 0))!!.type)
        assertTrue(c.removeSegment(c.edge(Vtx(3, 0), Vtx(4, 0))!!).ok)
        assertNull(c.edge(Vtx(5, 0), Vtx(6, 0)))
    }

    @Test fun expandTerrainAndSectorLimit() {
        val c = newCity()
        assertFalse(c.unlockSector(SectorId(5, 5)).ok, "no adyacente")
        assertTrue(c.unlockSector(SectorId(1, 0)).ok)
        assertTrue(c.placeBuilding("res_house_small", 20, 2).ok)
        c.config = c.config.copy(maxSectors = 5)
        assertFalse(c.unlockSector(SectorId(1, 1)).ok, "límite configurable")
    }

    @Test fun spatialQueries() {
        val c = newCity()
        c.placeBuilding("res_house_small", 2, 2); c.placeBuilding("res_house_small", 12, 12)
        assertEquals(1, c.buildingsIn(0, 0, 5, 5).size)
        assertEquals(2, c.buildingsIn(0, 0, 15, 15).size)
    }

    @Test fun catalogIsExtensibleThroughJson() {
        val extra = BuildingSpec("custom_kiosk", "Quiosco", Category.COMMERCIAL, 1, 1, ModelSpec(shape = "box", heightM = 4.0), buildingType = "kiosk")
        val cat = Catalog.fromJson(DefaultCatalog.catalog.plus(listOf(extra)).toJson())
        assertNotNull(cat["custom_kiosk"]); assertEquals(DefaultCatalog.catalog.all.size + 1, cat.all.size)
        val c = City(CityConfig("t", "T"), cat).also { it.ensureLoaded(it.config.unlocked) }
        assertTrue(c.placeBuilding("custom_kiosk", 0, 0).ok)
        assertEquals(Category.entries.toSet(), DefaultCatalog.catalog.all.map { it.category }.toSet(), "todas las categorías tienen edificios")
    }
}

class AutoRotateTest {
    @Test fun buildingTurnsItsFrontTowardTheRoad() {
        val c = City(CityConfig("t", "T"), DefaultCatalog.catalog).also { it.ensureLoaded(it.config.unlocked) }
        val spec = DefaultCatalog.catalog["res_house_small"]!!
        c.drawRoad(listOf(Vtx(0, 5), Vtx(8, 5)), RoadType.STREET)       // calle al sur de la casa en (2,4)
        assertEquals(0, c.bestRotation(spec, 2, 4, 2), "frente al sur")
        c.drawRoad(listOf(Vtx(10, 0), Vtx(10, 8)), RoadType.STREET)     // calle al este de la casa en (9,2)
        assertEquals(1, c.bestRotation(spec, 9, 2, 0), "frente al este")
        assertEquals(2, c.bestRotation(spec, 3, 5, 0), "frente al norte (la calle queda arriba)")
        assertEquals(0, c.bestRotation(spec, -12, -12, 0), "sin calles: se conserva el giro")
        assertTrue(c.nodesWithDegreeAtLeast(3, -20, -20, 20, 20).isEmpty())
        c.drawRoad(listOf(Vtx(5, 0), Vtx(5, 8)), RoadType.STREET)
        assertEquals(listOf(Vtx(5, 5)), c.nodesWithDegreeAtLeast(3, -20, -20, 20, 20))
    }
}
