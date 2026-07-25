package org.futo.inputmethod.latin.personalization

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object PersonalizationDataCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        encodeDefaults = true
    }

    fun decodeManifest(source: String): PersonalizationExportManifest {
        return json.decodeFromString(removeExtensionFields(json.parseToJsonElement(source)).toString())
    }

    fun encodeManifest(manifest: PersonalizationExportManifest): String {
        return json.encodeToString(manifest)
    }

    fun decodeData(source: String): PersonalizationDataSet {
        return json.decodeFromString(removeExtensionFields(json.parseToJsonElement(source)).toString())
    }

    fun encodeData(data: PersonalizationDataSet): String {
        return json.encodeToString(data)
    }

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
