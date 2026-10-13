package dev.dreamrally.citybuilder

import dev.dreamrally.citybuilder.demo.DemoCity
import dev.dreamrally.citybuilder.model.*
import dev.dreamrally.citybuilder.project.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ProjectStoreTest {
    private fun tmp() = createTempDirectory("proj").toFile()

    @Test fun saveAndReopenKeepsEverythingAndIds() {
        val dir = File(tmp(), "p1"); val store = ProjectStore(dir)
        val demo = DemoCity.build()
        val c = store.create(demo.config, DefaultCatalog.catalog)
        demo.config.unlocked.forEach { id -> demo.sectorData(id)?.let { c.install(it) } }
        assertTrue(store.save(c, all = true) > 0)
        val reopened = store.open(DefaultCatalog.catalog)
        reopened.ensureLoaded(reopened.config.unlocked)
        assertEquals(demo.buildings().map { it.id }.toSet(), reopened.buildings().map { it.id }.toSet())
        assertEquals(demo.edges().toSet(), reopened.edges().toSet())
        assertEquals(demo.config, reopened.config)
        // se puede seguir editando: el siguiente id continúa la serie
        val n = reopened.placeBuilding("dec_bush", -15, 6)
        assertTrue(n.ok, n.message); assertEquals("b${demo.config.nextBuildingId}", n.id)
    }

    @Test fun incrementalSaveWritesOnlyDirtySectors() {
        val dir = tmp(); val store = ProjectStore(dir)
        val city = store.create(CityConfig("x", "X"), DefaultCatalog.catalog)
        city.ensureLoaded(city.config.unlocked)
        city.placeBuilding("res_house_small", 1, 1); city.placeBuilding("res_house_small", -3, -3)
        assertEquals(2, store.save(city))
        city.placeBuilding("res_house_small", 2, 1)
        assertEquals(1, store.save(city), "solo el sector (0,0)")
        assertEquals(0, store.save(city))
        assertTrue(File(dir, "sectors/sec_0_0.json").exists() && File(dir, "sectors/sec_-1_-1.json").exists())
    }

    @Test fun lazyLoadingAndTrim() {
        val dir = tmp(); val store = ProjectStore(dir)
        val city = store.create(CityConfig("x", "X"), DefaultCatalog.catalog)
        city.ensureLoaded(city.config.unlocked)
        city.placeBuilding("res_house_small", 1, 1); city.placeBuilding("res_house_small", -3, -3)
        store.save(city)
        val fresh = store.open(DefaultCatalog.catalog)
        assertTrue(fresh.loadedSectors.isEmpty(), "abrir no carga sectores")
        fresh.ensureLoaded(listOf(SectorId(0, 0)))
        assertEquals(1, fresh.buildings().size)
        assertEquals(1, fresh.trim(emptySet())); assertTrue(fresh.buildings().isEmpty(), "liberado")
    }

    @Test fun malformedProjectFilesAreRejected() {
        val dir = tmp(); val store = ProjectStore(dir)
        assertFailsWith<ProjectFormatException> { store.open(DefaultCatalog.catalog) }
        File(dir, "project.json").writeText("{ no es json")
        assertFailsWith<ProjectFormatException> { store.open(DefaultCatalog.catalog) }
        File(dir, "project.json").writeText("""{"formatVersion":99,"config":{"cityId":"a","name":"b"},"catalogUsed":[]}""")
        assertFailsWith<ProjectFormatException> { store.open(DefaultCatalog.catalog) }
        val s2 = ProjectStore(tmp()); s2.create(CityConfig("x", "X"), DefaultCatalog.catalog)
        File(s2.dir, "sectors").mkdirs(); File(s2.dir, "sectors/sec_0_0.json").writeText("garbage")
        assertFailsWith<ProjectFormatException> { s2.loadSector(SectorId(0, 0)) }
    }
}
