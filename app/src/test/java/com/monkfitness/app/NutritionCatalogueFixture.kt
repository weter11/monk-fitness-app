package com.monkfitness.app

import com.monkfitness.app.data.model.NutritionCatalogue
import java.io.File

/** The tests read the real asset and the real exports, the same files this change ships. */
object NutritionCatalogueFixture {

    fun load() {
        NutritionCatalogue.load(asset("catalogue.json").readText())
    }

    fun asset(name: String): File = locate("src/main/assets/nutrition/$name")

    fun export(path: String): File = locate("data/nutrition/$path")

    fun testResource(path: String): File = locate("src/test/resources/nutrition/$path")

    fun rows(path: String): List<List<String>> = csv(export(path))

    fun csv(file: File): List<List<String>> = file.readLines()
        .filter { it.isNotBlank() }
        .map { splitCsv(it) }

    private fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                quoted && c == '"' && line.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    fields += field.toString()
                    field.clear()
                }
                else -> field.append(c)
            }
            index++
        }
        fields += field.toString()
        return fields
    }

    private fun locate(path: String): File =
        listOf(File(path), File("../$path"), File("app/$path"))
            .firstOrNull { it.isFile }
            ?: error("missing $path from ${File(".").absolutePath}")
}
