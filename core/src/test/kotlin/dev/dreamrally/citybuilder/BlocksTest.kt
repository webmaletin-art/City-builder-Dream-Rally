package dev.dreamrally.citybuilder

import dev.dreamrally.citybuilder.demo.DemoCity
import dev.dreamrally.citybuilder.export.*
import dev.dreamrally.citybuilder.glb.*
import dev.dreamrally.citybuilder.model.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class BlocksTest {
    private fun newCity(cat: Catalog = DefaultCatalog.catalog) = City(CityConfig("t", "T"), cat).also { it.ensureLoaded(it.config.unlocked) }
    private val RECT = intArrayOf(-16, -16, 15, 15)
    private fun loop(c: City, x0: Int, y0: Int, x1: Int, y1: Int) {
        c.drawRoad(listOf(Vtx(x0, y0), Vtx(x1, y0), Vtx(x1, y1), Vtx(x0, y1), Vtx(x0, y0)), RoadType.STREET)
    }

    @Test fun closedLoopCreatesEnclosedBlockWithParcelsCoveringEveryCell() {
        val c = newCity(); loop(c, 0, 0, 6, 6)
        val m = c.blocks(RECT)
        val inner = m.blocks.single { it.cellCount == 36 }
        assertFalse(inner.open, "rodeada por calles")
        val outer = m.blocks.filter { it !== inner }
        assertTrue(outer.all { it.open }, "el exterior es terreno abierto")
        // las parcelas cubren cada celda sin solaparse
        val cells = inner.parcels.flatMap { it.cells }
        assertEquals(36, cells.size); assertEquals(36, cells.toSet().size)
        assertTrue(inner.parcels.all { it.w <= 3 && it.d <= 3 })
        // frente hacia la calle: la parcela del borde sur mira al sur, etc.
        assertEquals(0, m.parcelAt(1, 5)!!.frontRot); assertEquals(2, m.parcelAt(1, 0)!!.frontRot)
        assertEquals(1, m.parcelAt(5, 2)!!.frontRot, "lado este")
        assertNotNull(m.parcelAt(3, 3), "interior cubierto")
    }

    @Test fun removingARoadMergesBlocksAndUpdatesParcels() {
        val c = newCity(); loop(c, 0, 0, 6, 6)
        c.drawRoad(listOf(Vtx(3, 0), Vtx(3, 6)), RoadType.STREET)       // divide en dos manzanas
        val two = c.blocks(RECT).blocks.filter { !it.open }
        assertEquals(2, two.size); assertEquals(listOf(18, 18), two.map { it.cellCount })
        c.removeRoadAlong(listOf(Vtx(3, 2), Vtx(3, 3), Vtx(3, 4)))      // hueco: las dos manzanas se comunican
        val merged = c.blocks(RECT).blocks.filter { !it.open }
        assertEquals(1, merged.size); assertEquals(36, merged[0].cellCount)
        assertEquals(36, merged[0].parcels.flatMap { it.cells }.toSet().size)
    }

    @Test fun buildingsOccupyParcelsAndNewRoadsSplitBlocks() {
        val c = newCity(); loop(c, 0, 0, 6, 6)
        assertTrue(c.placeBuilding("res_house_small", 1, 1).ok)
        assertEquals(c.building("b1")!!.id, c.blocks(RECT).parcelAt(1, 1)!!.occupiedBy)
        assertFalse(c.drawRoad(listOf(Vtx(1, 1), Vtx(2, 2)), RoadType.STREET).ok, "no se traza una calle sobre un edificio")
        assertEquals(1, c.blocks(RECT).blocks.count { !it.open })
        c.drawRoad(listOf(Vtx(0, 3), Vtx(6, 3)), RoadType.STREET)
        assertEquals(2, c.blocks(RECT).blocks.count { !it.open }, "la calle nueva divide la manzana")
        assertEquals("b1", c.blocks(RECT).parcelAt(1, 1)!!.occupiedBy, "el edificio sigue ocupando su parcela")
    }

    @Test fun diagonalAndRoundaboutAreWallsNotBuildable() {
        val c = newCity(); c.addRoundabout(0, 0)
        val m = c.blocks(RECT)
        assertNull(m.parcelAt(-1, -1), "isla central reservada")
        assertNull(m.parcelAt(0, 0))
    }

    @Test fun alignToRoadFindsSpotWithFrontOnTheStreet() {
        val c = newCity(); c.drawRoad(listOf(Vtx(0, 5), Vtx(10, 5)), RoadType.STREET)
        val spec = DefaultCatalog.catalog["res_house_small"]!!
        val pl = c.alignToRoad(spec, 4, 8, 0)!!                         // tocado lejos, al sur de la calle
        assertEquals(Triple(4, 5, 2), Triple(pl.x, pl.y, pl.rot), "lo más cercano: junto a la calle, mirando al norte")
        assertTrue(c.frontTouchesRoad(pl.x, pl.y, 1, 1, pl.rot))
        assertTrue(c.canPlace(spec, pl.x, pl.y, pl.rot).ok)
        assertNull(c.alignToRoad(spec, -12, -12, 0), "sin calles cercanas")
    }

    @Test fun strictFrontRuleBlocksPlacement() {
        val spec = DefaultCatalog.catalog["res_house_small"]!!.let { it.copy(id = "strict_house", placement = it.placement.copy(strict = true)) }
        val c = newCity(DefaultCatalog.catalog.plus(listOf(spec)))
        c.drawRoad(listOf(Vtx(0, 5), Vtx(10, 5)), RoadType.STREET)
        assertFalse(c.placeBuilding("strict_house", 4, 4, 2).ok, "el frente (norte) no mira a la calle (sur)")
        assertTrue(c.placeBuilding("strict_house", 4, 4, 0).ok)
        assertFalse(c.placeBuilding("strict_house", 4, -10, 0).ok, "sin calle")
    }

    @Test fun upgradeVariantAndUndo() {
        val c = newCity(); val id = c.placeBuilding("res_house_small", 1, 1).id!!
        assertTrue(c.upgradeBuilding(id).ok); assertEquals(1, c.building(id)!!.level)
        assertFalse(c.upgradeBuilding(id).ok, "no hay nivel 3")
        assertFalse(c.upgradeBuilding(c.placeBuilding("dec_bush", 8, 8).id!!).ok, "sin evolución definida")
        c.undo(); c.undo(); assertEquals(0, c.building(id)!!.level)
        c.redo(); assertEquals(1, c.building(id)!!.level)
    }

    @Test fun curvesRoundCornersAndKeepEndpoints() {
        val pts = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(8.0, 0.0), doubleArrayOf(16.0, 0.0), doubleArrayOf(16.0, 8.0), doubleArrayOf(16.0, 16.0))
        val f = Curves.fillet(pts)
        assertContentEquals(doubleArrayOf(0.0, 0.0), f.first()); assertContentEquals(doubleArrayOf(16.0, 16.0), f.last())
        assertTrue(f.size > 5 && f.all { !it[0].isNaN() && !it[1].isNaN() })
        val corner = f.minOf { kotlin.math.hypot(it[0] - 16.0, it[1] - 0.0) }
        assertTrue(corner > 1.0, "la curva no pasa por la esquina viva (cortada: $corner)")
        assertEquals(2, Curves.fillet(listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(4.0, 0.0), doubleArrayOf(8.0, 0.0))).size, "recta: solo extremos")
    }

    // ---- reemplazo de modelos y exportación del estado real ----
    @Test fun replacingAModelKeepsLogicAndExportsTheNewGlb() {
        val base = DefaultCatalog.catalog
        // GLB autocontenido de usuario: sin texturas externas
        val selfContained = GlbWriter.write(listOf(GlbNode("n", MeshBuilder().also { it.quad(Mat("userwall", 0xAA5533), arrayOf(doubleArrayOf(-3.0, 0.0, 0.0), doubleArrayOf(3.0, 0.0, 0.0), doubleArrayOf(3.0, 6.0, 0.0), doubleArrayOf(-3.0, 6.0, 0.0)), Array(4) { doubleArrayOf(0.0, 0.0) }, doubleArrayOf(0.0, 0.0, 1.0)) })), "")
        val swapped = base.withModel("res_house_small", ModelSpec(kind = "glb", glbPath = "models/mi_casa.glb", heightM = 6.0), appearances = emptyList())
        val a = newCity(base); val b = newCity(swapped)
        val spec = swapped["res_house_small"]!!
        assertEquals(base["res_house_small"]!!.widthCells, spec.widthCells); assertEquals(base["res_house_small"]!!.placement, spec.placement)
        for (c in listOf(a, b)) { c.drawRoad(listOf(Vtx(0, 5), Vtx(8, 5)), RoadType.STREET) }
        for ((x, y, r) in listOf(Triple(2, 4, 0), Triple(2, 4, 2), Triple(2, 5, 0), Triple(100, 100, 0))) assertEquals(a.canPlace(base["res_house_small"]!!, x, y, r), b.canPlace(spec, x, y, r), "misma lógica en ($x,$y,$r)")
        b.placeBuilding("res_house_small", 2, 4, 0)
        val out = File(createTempDirectory("sw").toFile(), "x.zip")
        assertFailsWith<ExportFailed> { PackageExporter.export(b.exportInput(), out) }          // GLB aún no importado
        val res = PackageExporter.export(b.exportInput { if (it == "models/mi_casa.glb") selfContained else null }, out)
        val pkg = out.inputStream().use { PackageVerifier.readStream(it) }
        assertContentEquals(selfContained, pkg.files["geometry/models/res_house_small.glb"], "se copia el GLB del usuario tal cual")
        assertTrue(res.warnings.none { it.contains("excede") || it.contains("centrado") }, "el GLB cabe en la huella")
        assertTrue(PackageVerifier.verify(out).ok)
        // un GLB demasiado grande genera aviso (no bloquea)
        val huge = GlbWriter.write(listOf(GlbNode("n", MeshBuilder().also { it.quad(Mat("big", 0x888888), arrayOf(doubleArrayOf(-30.0, 0.0, 0.0), doubleArrayOf(30.0, 0.0, 0.0), doubleArrayOf(30.0, 6.0, 0.0), doubleArrayOf(-30.0, 6.0, 0.0)), Array(4) { doubleArrayOf(0.0, 0.0) }, doubleArrayOf(0.0, 0.0, 1.0)) })), "")
        val res2 = PackageExporter.export(b.exportInput { if (it == "models/mi_casa.glb") huge else null }, File(out.parentFile, "y.zip"))
        assertTrue(res2.warnings.any { it.contains("excede") })
    }

    @Test fun exportReflectsRealEngineStateBlocksLevelsAndCurves() {
        val c = DemoCity.build(); val id = c.buildings().first { it.catalogId == "res_house_small" }.id
        assertTrue(c.upgradeBuilding(id).ok)
        val out = File(createTempDirectory("ex").toFile(), "d.zip")
        val r = PackageExporter.export(c.exportInput(), out)
        val pkg = out.inputStream().use { PackageVerifier.readStream(it) }
        fun js(p: String) = Json.parseToJsonElement(String(pkg.files[p]!!)).jsonObject
        assertTrue("geometry/models/res_house_small__l1_v0.glb" in pkg.files, "modelo del nivel 2")
        val b = js("buildings.json")["buildings"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject
        assertEquals(1, b["level"]!!.jsonPrimitive.int); assertTrue(b["model"]!!.jsonPrimitive.content.endsWith("__l1_v0.glb"))
        val bl = js("blocks.json"); assertTrue(bl["blocks"]!!.jsonArray.size >= 9 && bl["parcels"]!!.jsonArray.size > 20)
        assertTrue(bl["blocks"]!!.jsonArray.count { !it.jsonObject["open"]!!.jsonPrimitive.boolean } >= 8, "manzanas cerradas del demo")
        val occupied = bl["parcels"]!!.jsonArray.count { it.jsonObject["occupiedBy"] !is JsonNull }
        assertTrue(occupied > 20)
        val seg = js("roads.json")["segments"]!!.jsonArray.first().jsonObject
        assertTrue(seg["curve"]!!.jsonArray.size >= 2)
        assertTrue(PackageVerifier.verify(out).ok); assertEquals("ok", js("metadata/export-report.json")["result"]!!.jsonPrimitive.content)
        // el verificador detecta una parcela que apunta a un edificio inexistente
        val bad = LinkedHashMap(pkg.files); bad["blocks.json"] = String(bad["blocks.json"]!!).replaceFirst("\"occupiedBy\": \"b", "\"occupiedBy\": \"zz").toByteArray()
        val rep = PackageVerifier.verify(PackageContents(bad)); assertFalse(rep.ok)
        assertNotNull(r)
    }
}
