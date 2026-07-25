package org.futo.inputmethod.latin.languagepack.benchmark

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object CorrectionBenchmarkReportCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        explicitNulls = false
    }

    fun decode(source: String): CorrectionBenchmarkReport {
        val parsed = json.parseToJsonElement(source)
        val withoutExtensions = removeExtensionFields(parsed)
        return json.decodeFromString(withoutExtensions.toString())
    }

    fun encode(report: CorrectionBenchmarkReport): String = json.encodeToString(report)

    private fun removeExtensionFields(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .asSequence()
                .filterNot { (key, _) -> key.startsWith("x-") }
                .associate { (key, value) -> key to removeExtensionFields(value) },
        )
        is JsonArray -> JsonArray(element.map(::removeExtensionFields))
        else -> element
    }
}
