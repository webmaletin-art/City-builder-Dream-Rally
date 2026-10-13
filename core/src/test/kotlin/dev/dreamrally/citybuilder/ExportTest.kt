package dev.dreamrally.citybuilder

import dev.dreamrally.citybuilder.demo.DemoCity
import dev.dreamrally.citybuilder.export.*
import dev.dreamrally.citybuilder.glb.*
import dev.dreamrally.citybuilder.model.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ExportTest {
    companion object {
        val demoZip: File by lazy {
            val f = File(createTempDirectory("exp").toFile(), "demo.zip")
            PackageExporter.export(DemoCity.build().exportInput(), f); f
        }
        val pkg: PackageContents by lazy { demoZip.inputStream().use { PackageVerifier.readStream(it) } }
        fun js(p: String) = Json.parseToJsonElement(String(pkg.files[p]!!)).jsonObject
    }

    @Test fun demoCityExportsAndVerifies() {
        val rep = PackageVerifier.verify(demoZip)
        assertTrue(rep.ok, rep.errors.joinToString("\n"))
        val files = pkg.files.keys
        for (p in listOf("manifest.json", "city.json", "roads.json", "buildings.json", "materials/materials.json", "metadata/export-report.json", "metadata/catalog.json"))
            assertTrue(p in files, "falta $p")
        assertEquals("manifest.json", java.util.zip.ZipFile(demoZip).entries().nextElement().name, "manifest primero")
        assertTrue(files.count { it.startsWith("geometry/models/") } >= 20, "modelos GLB reales por tipo de edificio")
        assertTrue(files.count { it.startsWith("geometry/sectors/") && it.endsWith("_terrain.glb") } >= 5)
        assertTrue(files.any { it.startsWith("geometry/sectors/") && it.endsWith("_roads.glb") })
        assertTrue(files.count { it.startsWith("textures/") && it.endsWith(".png") } >= 8)
        assertTrue(files.count { it.startsWith("terrain/") } >= 5)
    }

    @Test fun demoCityContent() {
        val city = js("city.json"); val roads = js("roads.json"); val b = js("buildings.json")
        assertTrue(city["sectors"]!!.jsonArray.size > 4, "más de un sector")
        assertTrue(b["buildings"]!!.jsonArray.size >= 100)
        val types = roads["segments"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("dirt", "street", "avenue", "highway"), types)
        assertEquals(1, roads["roundabouts"]!!.jsonArray.size)
        assertTrue(roads["intersections"]!!.jsonArray.any { it.jsonObject["kind"]!!.jsonPrimitive.content == "cross" })
        assertTrue(roads["intersections"]!!.jsonArray.any { it.jsonObject["kind"]!!.jsonPrimitive.content == "roundabout_entry" })
        val seg = roads["segments"]!!.jsonArray.first().jsonObject
        for (k in listOf("id", "type", "startNode", "endNode", "polyline", "widthMeters", "lanes", "oneWay", "elevation", "connections", "gps", "traffic", "geometry")) assertTrue(k in seg, k)
        val man = js("manifest.json")
        assertEquals("DreamCityPackage", man["format"]!!.jsonPrimitive.content)
        assertEquals("meters", man["units"]!!.jsonObject["length"]!!.jsonPrimitive.content)
    }

    @Test fun glbFilesAreValidGltfWithBoundsAndExternalTextures() {
        val glb = pkg.files["geometry/models/res_house_small.glb"]!!
        val j = GlbWriter.readJson(glb)
        assertEquals("2.0", j["asset"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        val prim = j["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray
        assertTrue(prim.isNotEmpty())
        val acc = j["accessors"]!!.jsonArray
        assertTrue(acc.any { it.jsonObject["min"] != null })
        val uris = j["images"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
        assertTrue(uris.all { it.startsWith("../../textures/") })
        // las normales guardadas coinciden con el sentido de giro de los triángulos (caras hacia fuera)
        val mb = ModelFactory.build(DefaultCatalog.catalog["res_house_small"]!!)
        for (p in mb.prims.values) for (t in 0 until p.idx.n step 3) {
            val i = IntArray(3) { p.idx.a[t + it] }
            val pt = Array(3) { doubleArrayOf(p.pos.a[i[it] * 3].toDouble(), p.pos.a[i[it] * 3 + 1].toDouble(), p.pos.a[i[it] * 3 + 2].toDouble()) }
            val n = MeshBuilder.normal(pt[0], pt[1], pt[2])
            val sn = doubleArrayOf(p.nrm.a[i[0] * 3].toDouble(), p.nrm.a[i[0] * 3 + 1].toDouble(), p.nrm.a[i[0] * 3 + 2].toDouble())
            assertTrue(MeshBuilder.dot(n, sn) > 0.99, "normal inconsistente con el bobinado")
        }
    }

    @Test fun everyCatalogShapeBuildsNonEmptyMesh() {
        for (s in DefaultCatalog.catalog.all) {
            val mb = ModelFactory.build(s)
            assertTrue(mb.prims.values.sumOf { it.idx.n } > 0, s.id)
            GlbWriter.readJson(GlbWriter.write(listOf(GlbNode(s.id, mb)), "../../textures/"))
        }
    }

    @Test fun exportIsDeterministic() {
        val f2 = File(createTempDirectory("exp2").toFile(), "d.zip")
        PackageExporter.export(DemoCity.build().exportInput(), f2)
        assertContentEquals(demoZip.readBytes(), f2.readBytes())
    }

    @Test fun roundTripImportRestoresLogic() {
        val original = DemoCity.build()
        val imp = PackageImporter.import(pkg)
        assertEquals(original.buildings().map { Triple(it.id, it.catalogId, Triple(it.x, it.y, it.rot)) }.toSet(), imp.buildings.map { Triple(it.id, it.catalogId, Triple(it.x, it.y, it.rot)) }.toSet())
        assertEquals(original.edges().map { it.id to it.type to it.oneWay }.toSet(), imp.edges.map { it.id to it.type to it.oneWay }.toSet())
        assertEquals(original.config.unlocked, imp.sectors)
        val back = PackageImporter.toCity(imp, DefaultCatalog.catalog)
        assertEquals(original.buildings().size, back.buildings().size)
        assertEquals(RoadGraph(original.edges()).segments.map { it.id }, RoadGraph(back.edges()).segments.map { it.id })
    }

    @Test fun cancelAndProgress() {
        val steps = ArrayList<Float>()
        assertFailsWith<ExportCancelled> {
            PackageExporter.export(DemoCity.build().exportInput(), File(createTempDirectory("c").toFile(), "x.zip"), { steps += it.fraction }, { steps.size > 3 })
        }
        assertTrue(steps.size in 3..6 && steps == steps.sorted())
    }

    @Test fun missingCatalogEntryOrGlbBlocksExport() {
        val city = DemoCity.build()
        val glbSpec = BuildingSpec("ext_glb", "Externo", Category.LANDMARK, 1, 1, ModelSpec(kind = "glb", glbPath = "models/ext.glb"), buildingType = "landmark")
        val cat = DefaultCatalog.catalog.plus(listOf(glbSpec))
        val c = City(city.config, cat).also { it.ensureLoaded(it.config.unlocked) }
        assertTrue(c.placeBuilding("ext_glb", 0, 0).ok || c.placeBuilding("ext_glb", 20, 20).ok)
        val ex = assertFailsWith<ExportFailed> { PackageExporter.export(c.exportInput(), File(createTempDirectory("m").toFile(), "x.zip")) }
        assertTrue(ex.errors.any { it.contains("GLB ausente") })
        // con el GLB presente (autocontenido) exporta y lo copia
        val glb = GlbWriter.write(listOf(GlbNode("n", ModelFactory.build(DefaultCatalog.catalog["res_house_small"]!!).also { m -> m.prims.values.forEach { } })), "../../textures/")
        val bad = assertFailsWith<ExportFailed> { PackageExporter.export(c.exportInput { glb }, File(createTempDirectory("m").toFile(), "x.zip")) }
        assertTrue(bad.errors.any { it.contains("texturas externas") }, "los GLB de terceros deben embeber texturas")
        // catálogo desconocido
        val unknown = City(CityConfig("u", "U"), DefaultCatalog.catalog).also { it.ensureLoaded(it.config.unlocked) }
        unknown.install(SectorData(SectorId(0, 0), buildings = listOf(PlacedBuilding("b1", "no_existe", 1, 1))))
        assertFailsWith<ExportFailed> { PackageExporter.export(unknown.exportInput(), File(createTempDirectory("m").toFile(), "x.zip")) }
    }

    // ---- verificador ante datos malformados / dependencias ausentes ----
    private fun rezip(mutate: (MutableMap<String, ByteArray>) -> Unit): ByteArray {
        val m = LinkedHashMap(pkg.files); mutate(m)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((k, v) in m) { z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test fun verifierDetectsMissingDependenciesAndTampering() {
        val noTex = PackageVerifier.verify(rezip { m -> m.remove(m.keys.first { it.startsWith("textures/") }) })
        assertTrue(noTex.errors.any { it.contains("textura ausente") || it.contains("Archivo declarado pero ausente") }, noTex.errors.toString())
        val noModel = PackageVerifier.verify(rezip { it.remove("geometry/models/res_house_small.glb") })
        assertTrue(noModel.errors.any { it.contains("modelo ausente") })
        val tampered = PackageVerifier.verify(rezip { it["roads.json"] = it["roads.json"]!! + " ".toByteArray() })
        assertTrue(tampered.errors.any { it.contains("SHA-256") })
        val extra = PackageVerifier.verify(rezip { it["extra.txt"] = ByteArray(1) })
        assertTrue(extra.errors.any { it.contains("no declarado") })
    }

    @Test fun verifierRejectsMalformedAndUnsafePackages() {
        assertFalse(PackageVerifier.verify(ByteArray(100) { 7 }).ok, "no es un ZIP")
        assertFalse(PackageVerifier.verify(ByteArray(0)).ok)
        assertTrue(PackageVerifier.verify(rezip { it["manifest.json"] = "{ roto".toByteArray() }).errors.any { it.contains("manifest.json no es JSON") })
        assertTrue(PackageVerifier.verify(rezip { it["../evil.txt"] = ByteArray(1) }).errors.any { it.contains("Ruta insegura") })
        assertTrue(PackageVerifier.verify(rezip { it["geometry/models/res_house_small.glb"] = ByteArray(40) }).errors.any { it.contains("GLB inválido") || it.contains("SHA") })
        assertFalse(PackageVerifier.verify(rezip { it.remove("manifest.json") }).ok)
        val truncated = demoZip.readBytes().copyOf(5000)
        assertFalse(PackageVerifier.verify(truncated).ok, "ZIP truncado")
        assertFailsWith<PackageImportException> { PackageImporter.import(PackageContents(mapOf("manifest.json" to "{}".toByteArray()))) }
        // esquema futuro
        val future = rezip { m ->
            val man = Json.parseToJsonElement(String(m["manifest.json"]!!)).jsonObject
            m["manifest.json"] = JsonObject(man + ("schemaVersion" to JsonPrimitive(99))).toString().toByteArray()
        }
        assertTrue(PackageVerifier.verify(future).errors.any { it.contains("más nuevo") })
    }

    @Test fun reliefHeightsAreReproducible() {
        assertEquals(Heights.at(Relief.HILLS, 5, 10, 20), Heights.at(Relief.HILLS, 5, 10, 20))
        assertEquals(0.0, Heights.at(Relief.FLAT, 5, 10, 20))
        val hs = (0..30).map { Heights.at(Relief.HILLS, 5, it, it * 2) }
        assertTrue(hs.max() - hs.min() > 1.0 && hs.all { kotlin.math.abs(it) <= Relief.HILLS.amplitude })
    }
}
