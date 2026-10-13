package dev.dreamrally.citybuilder.demo

import dev.dreamrally.citybuilder.export.*
import java.io.File

/** Genera la ciudad de prueba y escribe el ZIP. Uso: gradlew :core:run --args="examples/demo-city.zip" */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "examples/demo-city.dreamcity.zip" })
    val city = DemoCity.build()
    val res = PackageExporter.export(city.exportInput(), out, { println("%3d%%  %s".format((it.fraction * 100).toInt(), it.stage)) })
    println("OK ${res.file} (${res.sizeBytes} bytes, ${res.entries} archivos)")
    res.warnings.take(10).forEach { println("aviso: $it") }
}
