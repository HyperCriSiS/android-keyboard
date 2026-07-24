package org.futo.inputmethod.latin.languagepack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val CURRENT_LANGUAGE_PACKAGE_FORMAT_VERSION = "0.1"

@Serializable
data class LanguagePackageManifest(
    val formatVersion: String,
    @SerialName("package") val packageInfo: LanguagePackageInfo,
    val components: List<LanguagePackageComponent>,
    val profiles: List<LanguagePackageProfile> = emptyList(),
    val defaultProfile: String? = null,
    val resources: List<LanguagePackageResource> = emptyList(),
)

@Serializable
data class LanguagePackageInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val kind: LanguagePackageKind,
    val authors: List<LanguagePackageAuthor>,
    val license: String,
    val sourceUrl: String? = null,
    val homepageUrl: String? = null,
    val languages: List<String>,
)

@Serializable
enum class LanguagePackageKind {
    @SerialName("bundle")
    Bundle,

    @SerialName("component")
    Component,

    @SerialName("profile")
    Profile,
}

@Serializable
data class LanguagePackageAuthor(
    val name: String,
    val url: String? = null,
)

/**
 * Selects a runtime adapter without requiring adapter-specific metadata to be written into the
 * component payload itself. This allows an existing standard GGUF model to be wrapped by a package.
 */
@Serializable
data class LanguagePackageRuntimeBinding(
    val id: String,
    val apiVersion: Int = 1,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class LanguagePackageComponent(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val kind: LanguagePackageComponentKind,
    val activation: LanguagePackageActivation,
    val priority: Int = 0,
    val languages: List<String>,
    val layouts: List<String> = emptyList(),
    val tasks: List<String>,
    val capabilities: LanguagePackageCapabilities = LanguagePackageCapabilities(),
    val compatibility: LanguagePackageCompatibility = LanguagePackageCompatibility(),
    val dependencies: List<LanguagePackageComponentReference> = emptyList(),
    val runtime: LanguagePackageRuntimeBinding? = null,
    val payload: LanguagePackagePayload,
)

@Serializable
enum class LanguagePackageComponentKind {
    @SerialName("dictionary")
    Dictionary,

    @SerialName("language-rules")
    LanguageRules,

    @SerialName("candidate-generator")
    CandidateGenerator,

    @SerialName("context-ranker")
    ContextRanker,

    @SerialName("correction-model")
    CorrectionModel,

    @SerialName("swipe-model")
    SwipeModel,

    @SerialName("personalization-adapter")
    PersonalizationAdapter,
}

@Serializable
enum class LanguagePackageActivation {
    @SerialName("stackable")
    Stackable,

    @SerialName("exclusive")
    Exclusive,
}

@Serializable
data class LanguagePackageCapabilities(
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
)

@Serializable
data class LanguagePackageCompatibility(
    val minKeyboardApi: Int? = null,
    val maxKeyboardApi: Int? = null,
    val androidAbis: List<String> = emptyList(),
    val minRamMb: Int? = null,
    val runtimeFeatures: List<String> = emptyList(),
)

@Serializable
data class LanguagePackagePayload(
    val path: String,
    val mediaType: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class LanguagePackageComponentReference(
    val packageId: String? = null,
    val componentId: String,
    val versionRange: String? = null,
    val required: Boolean = true,
    val weight: Float = 1.0f,
)

@Serializable
data class LanguagePackageProfile(
    val id: String,
    val name: String,
    val description: String? = null,
    val selections: List<LanguagePackageProfileSelection>,
)

@Serializable
data class LanguagePackageProfileSelection(
    val slot: LanguagePackageComponentKind,
    val strategy: LanguagePackageSelectionStrategy,
    val components: List<LanguagePackageComponentReference>,
)

@Serializable
enum class LanguagePackageSelectionStrategy {
    @SerialName("replace")
    Replace,

    @SerialName("append")
    Append,
}

@Serializable
data class LanguagePackageResource(
    val path: String,
    val mediaType: String,
    val sha256: String,
    val sizeBytes: Long,
    val role: LanguagePackageResourceRole? = null,
)

@Serializable
enum class LanguagePackageResourceRole {
    @SerialName("benchmark")
    Benchmark,

    @SerialName("license")
    License,

    @SerialName("notice")
    Notice,

    @SerialName("thumbnail")
    Thumbnail,

    @SerialName("documentation")
    Documentation,

    @SerialName("signature")
    Signature,
}
