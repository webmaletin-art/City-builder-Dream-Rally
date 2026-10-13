package dev.dreamrally.citybuilder.demo

import dev.dreamrally.citybuilder.model.*

/** Ciudad de prueba: 16 manzanas, cuatro tipos de carretera, rotonda, intersecciones, edificios variados y 4 sectores. */
object DemoCity {
    fun build(catalog: Catalog = DefaultCatalog.catalog, biome: Biome = Biome.TEMPERATE, relief: Relief = Relief.GENTLE): City {
        val city = City(CityConfig("demo-city", "Ciudad de prueba", biome, relief, reliefSeed = 42), catalog)
        listOf(SectorId(1, -1), SectorId(1, 0), SectorId(0, 1), SectorId(-1, 1), SectorId(1, 1)).forEach { city.unlockSector(it) }
        city.ensureLoaded(city.config.unlocked)
        fun line(t: RoadType, x0: Int, y0: Int, x1: Int, y1: Int) { city.drawRoad(listOf(Vtx(x0, y0), Vtx(x1, y1)), t) }
        line(RoadType.AVENUE, -16, 0, 16, 0)
        line(RoadType.STREET, -16, -8, 16, -8)
        line(RoadType.STREET, -16, 8, -10, 8); line(RoadType.STREET, -6, 8, 16, 8)
        line(RoadType.STREET, -8, -16, -8, 6); line(RoadType.STREET, -8, 10, -8, 16)
        line(RoadType.AVENUE, 0, -16, 0, 16)
        line(RoadType.STREET, 8, -16, 8, 16)
        line(RoadType.HIGHWAY, 16, -16, 16, 16)
        line(RoadType.DIRT, -16, -16, 16, -16)
        city.addRoundabout(-8, 8, RoadType.STREET)

        val houses = listOf("res_house_small", "res_house_medium", "res_house_blue", "res_duplex")
        fun put(id: String, x: Int, y: Int, rot: Int = 0) { city.placeBuilding(id, x, y, rot) }
        for (bx in 0..3) for (by in 0..3) {
            val x0 = -16 + bx * 8; val y0 = -16 + by * 8
            when ((bx + by * 2) % 6) {
                0 -> { for (i in 0 until 8) { put(houses[(i + bx) % 4], x0 + i, y0 + 0); put(houses[(i + by) % 4], x0 + i, y0 + 7, 2) }; put("res_apartment_mid", x0 + 3, y0 + 3) }
                1 -> { put("com_supermarket", x0 + 0, y0 + 0); put("com_mall", x0 + 4, y0 + 0); put("com_gas_station", x0 + 0, y0 + 5); put("com_restaurant", x0 + 3, y0 + 6); put("com_bakery", x0 + 6, y0 + 6); put("com_shop", x0 + 7, y0 + 5) }
                2 -> { put("park_large", x0 + 0, y0 + 0); put("plaza_fountain", x0 + 4, y0 + 0); put("dec_tree_oak", x0 + 5, y0 + 4); put("dec_tree_pine", x0 + 6, y0 + 4); put("dec_bush", x0 + 7, y0 + 5); put("dec_lamp", x0 + 4, y0 + 5); put("pub_school", x0 + 0, y0 + 5); put("res_townhouse", x0 + 4, y0 + 6) }
                3 -> { put("off_skyscraper", x0 + 0, y0 + 0); put("off_tower", x0 + 4, y0 + 0); put("off_small", x0 + 6, y0 + 3); put("pub_townhall", x0 + 0, y0 + 4); put("pub_library", x0 + 4, y0 + 5) }
                4 -> { put("ind_factory", x0 + 0, y0 + 0); put("ind_warehouse", x0 + 3, y0 + 5); put("ind_silo", x0 + 5, y0 + 0); put("ind_chimney_plant", x0 + 5, y0 + 2); put("utl_substation", x0 + 0, y0 + 3) }
                else -> { put("pub_hospital", x0 + 0, y0 + 0); put("pub_police", x0 + 4, y0 + 0); put("pub_fire", x0 + 6, y0 + 0); put("res_apartment_low", x0 + 0, y0 + 4); put("res_tower", x0 + 4, y0 + 4); put("dec_tree_palm", x0 + 7, y0 + 7) }
            }
        }
        return city
    }
}
