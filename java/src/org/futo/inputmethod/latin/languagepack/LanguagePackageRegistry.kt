package org.futo.inputmethod.latin.languagepack

import java.io.File
import java.util.Locale

data class LanguagePackageComponentCoordinate(
    val packageId: String,
    val packageVersion: String,
    val componentId: String,
    val componentVersion: String,
) {
    override fun toString(): String = "$packageId@$packageVersion:$componentId@$componentVersion"
}

data class LanguagePackageProfileCoordinate(
    val packageId: String,
    val packageVersion: String,
    val profileId: String,
)

data class RegisteredLanguagePackageComponent(
    val installedPackage: InstalledLanguagePackage,
    val component: LanguagePackageComponent,
) {
    val coordinate = LanguagePackageComponentCoordinate(
        packageId = installedPackage.manifest.packageInfo.id,
        packageVersion = installedPackage.manifest.packageInfo.version,
        componentId = component.id,
        componentVersion = component.version,
    )

    val payloadFile: File
        get() = File(installedPackage.contentDirectory, component.payload.path)
}

data class RegisteredLanguagePackageProfile(
    val installedPackage: InstalledLanguagePackage,
    val profile: LanguagePackageProfile,
) {
    val coordinate = LanguagePackageProfileCoordinate(
        packageId = installedPackage.manifest.packageInfo.id,
        packageVersion = installedPackage.manifest.packageInfo.version,
        profileId = profile.id,
    )
}

sealed class LanguagePackageReferenceResolution {
    data class Resolved(
        val component: RegisteredLanguagePackageComponent,
        val alternatives: List<RegisteredLanguagePackageComponent>,
    ) : LanguagePackageReferenceResolution()

    data class Missing(val message: String) : LanguagePackageReferenceResolution()
    data class InvalidVersionRange(val value: String) : LanguagePackageReferenceResolution()
}

data class LanguagePackageTarget(
    val languageTag: String,
    val layout: String? = null,
)

data class LanguagePackageRuntimeEnvironment(
    val keyboardApi: Int,
    val androidAbi: String,
    val ramMb: Int,
    val supportedTasks: Set<String>,
    val supportedCapabilities: Set<String>,
    val runtimeFeatures: Set<String>,
)

enum class LanguagePackageCompatibilitySeverity {
    Error,
    Warning,
}

data class LanguagePackageCompatibilityIssue(
    val severity: LanguagePackageCompatibilitySeverity,
    val code: String,
    val message: String,
)

data class ResolvedLanguagePackageDependency(
    val reference: LanguagePackageComponentReference,
    val component: RegisteredLanguagePackageComponent,
)

data class LanguagePackageComponentEvaluation(
    val registeredComponent: RegisteredLanguagePackageComponent,
    val issues: List<LanguagePackageCompatibilityIssue>,
    val dependencies: List<ResolvedLanguagePackageDependency>,
) {
    val isCompatible: Boolean
        get() = issues.none { it.severity == LanguagePackageCompatibilitySeverity.Error }
}

class LanguagePackageRegistry private constructor(
    val installedPackages: List<InstalledLanguagePackage>,
    private val componentsByCoordinate: Map<LanguagePackageComponentCoordinate, RegisteredLanguagePackageComponent>,
    private val profilesByCoordinate: Map<LanguagePackageProfileCoordinate, RegisteredLanguagePackageProfile>,
) {
    val components: List<RegisteredLanguagePackageComponent> = componentsByCoordinate.values
        .sortedWith(componentIdentityComparator)

    val profiles: List<RegisteredLanguagePackageProfile> = profilesByCoordinate.values
        .sortedWith(
            compareBy<RegisteredLanguagePackageProfile> { it.coordinate.packageId }
                .thenByDescending { semanticVersion(it.coordinate.packageVersion) }
                .thenBy { it.coordinate.profileId },
        )

    fun findComponent(coordinate: LanguagePackageComponentCoordinate): RegisteredLanguagePackageComponent? {
        return componentsByCoordinate[coordinate]
    }

    fun findProfile(coordinate: LanguagePackageProfileCoordinate): RegisteredLanguagePackageProfile? {
        return profilesByCoordinate[coordinate]
    }

    fun resolveReference(
        ownerPackage: InstalledLanguagePackage?,
        reference: LanguagePackageComponentReference,
    ): LanguagePackageReferenceResolution {
        val range = reference.versionRange?.let { rawRange ->
            LanguagePackageVersionRange.parse(rawRange)
                ?: return LanguagePackageReferenceResolution.InvalidVersionRange(rawRange)
        }

        val ownerPackageId = ownerPackage?.manifest?.packageInfo?.id
        val ownerPackageVersion = ownerPackage?.manifest?.packageInfo?.version
        val targetPackageId = reference.packageId ?: ownerPackageId
            ?: return LanguagePackageReferenceResolution.Missing(
                "A package ID is required when resolving a reference without an owner package.",
            )

        val candidates = components.asSequence()
            .filter { it.coordinate.packageId == targetPackageId }
            .filter { it.coordinate.componentId == reference.componentId }
            .filter {
                reference.packageId != null || ownerPackageVersion == null ||
                    it.coordinate.packageVersion == ownerPackageVersion
            }
            .filter { candidate ->
                range == null || range.contains(semanticVersion(candidate.coordinate.componentVersion))
            }
            .sortedWith(componentPreferenceComparator)
            .toList()

        val selected = candidates.firstOrNull()
            ?: return LanguagePackageReferenceResolution.Missing(
                buildString {
                    append("No installed component matches '")
                    append(targetPackageId)
                    append(':')
                    append(reference.componentId)
                    append(''')
                    reference.versionRange?.let {
                        append(" with component version range '")
                        append(it)
                        append(''')
                    }
                    append('.')
                },
            )

        return LanguagePackageReferenceResolution.Resolved(
            component = selected,
            alternatives = candidates.drop(1),
        )
    }

    fun evaluateAll(
        target: LanguagePackageTarget,
        environment: LanguagePackageRuntimeEnvironment,
    ): Map<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation> {
        return CompatibilityEvaluator(target, environment).run {
            components.associate { component -> component.coordinate to evaluate(component) }
        }
    }

    fun evaluate(
        component: RegisteredLanguagePackageComponent,
        target: LanguagePackageTarget,
        environment: LanguagePackageRuntimeEnvironment,
    ): LanguagePackageComponentEvaluation {
        return CompatibilityEvaluator(target, environment).evaluate(component)
    }

    private inner class CompatibilityEvaluator(
        private val target: LanguagePackageTarget,
        private val environment: LanguagePackageRuntimeEnvironment,
    ) {
        private val cache = mutableMapOf<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation>()
        private val visiting = linkedSetOf<LanguagePackageComponentCoordinate>()

        fun evaluate(component: RegisteredLanguagePackageComponent): LanguagePackageComponentEvaluation {
            cache[component.coordinate]?.let { return it }

            if (!visiting.add(component.coordinate)) {
                return LanguagePackageComponentEvaluation(
                    registeredComponent = component,
                    issues = listOf(
                        LanguagePackageCompatibilityIssue(
                            severity = LanguagePackageCompatibilitySeverity.Error,
                            code = "dependency_cycle",
                            message = "Cross-package dependency cycle detected at ${component.coordinate}.",
                        ),
                    ),
                    dependencies = emptyList(),
                )
            }

            val issues = mutableListOf<LanguagePackageCompatibilityIssue>()
            val dependencies = mutableListOf<ResolvedLanguagePackageDependency>()

            fun error(code: String, message: String) {
                issues += LanguagePackageCompatibilityIssue(
                    severity = LanguagePackageCompatibilitySeverity.Error,
                    code = code,
                    message = message,
                )
            }

            fun warning(code: String, message: String) {
                issues += LanguagePackageCompatibilityIssue(
                    severity = LanguagePackageCompatibilitySeverity.Warning,
                    code = code,
                    message = message,
                )
            }

            val specification = component.component
            if (!languageTagsOverlap(target.languageTag, specification.languages)) {
                error(
                    "language_mismatch",
                    "Component does not support language '${target.languageTag}'.",
                )
            }

            val normalizedLayout = target.layout?.lowercase(Locale.ROOT)
            if (normalizedLayout != null && specification.layouts.isNotEmpty() &&
                specification.layouts.none { it.lowercase(Locale.ROOT) == normalizedLayout }
            ) {
                error(
                    "layout_mismatch",
                    "Component does not support layout '${target.layout}'.",
                )
            }

            val compatibility = specification.compatibility
            compatibility.minKeyboardApi?.let { minimum ->
                if (environment.keyboardApi < minimum) {
                    error(
                        "keyboard_api_too_old",
                        "Component requires keyboard API $minimum or newer.",
                    )
                }
            }
            compatibility.maxKeyboardApi?.let { maximum ->
                if (environment.keyboardApi > maximum) {
                    error(
                        "keyboard_api_too_new",
                        "Component supports keyboard API $maximum or older.",
                    )
                }
            }
            if (compatibility.androidAbis.isNotEmpty() &&
                compatibility.androidAbis.none { it.equals(environment.androidAbi, ignoreCase = true) }
            ) {
                error(
                    "abi_mismatch",
                    "Component does not support Android ABI '${environment.androidAbi}'.",
                )
            }
            compatibility.minRamMb?.let { minimum ->
                if (environment.ramMb < minimum) {
                    error(
                        "insufficient_ram",
                        "Component requires at least $minimum MB of RAM.",
                    )
                }
            }

            val unsupportedTasks = specification.tasks.filterNot(environment.supportedTasks::contains)
            if (unsupportedTasks.isNotEmpty()) {
                error(
                    "unsupported_tasks",
                    "Runtime does not support required tasks: ${unsupportedTasks.joinToString()}.",
                )
            }

            val unsupportedCapabilities = specification.capabilities.required
                .filterNot(environment.supportedCapabilities::contains)
            if (unsupportedCapabilities.isNotEmpty()) {
                error(
                    "unsupported_capabilities",
                    "Runtime does not support required capabilities: ${unsupportedCapabilities.joinToString()}.",
                )
            }

            val unsupportedRuntimeFeatures = compatibility.runtimeFeatures
                .filterNot(environment.runtimeFeatures::contains)
            if (unsupportedRuntimeFeatures.isNotEmpty()) {
                error(
                    "missing_runtime_features",
                    "Runtime features are unavailable: ${unsupportedRuntimeFeatures.joinToString()}.",
                )
            }

            val payload = component.payloadFile
            if (!payload.isFile) {
                error("missing_payload_file", "Installed component payload is missing.")
            } else if (payload.length() != specification.payload.sizeBytes) {
                error(
                    "payload_size_changed",
                    "Installed component payload size no longer matches its manifest.",
                )
            }

            specification.dependencies.forEach { reference ->
                when (val resolution = resolveReference(component.installedPackage, reference)) {
                    is LanguagePackageReferenceResolution.InvalidVersionRange -> {
                        val message = "Dependency uses invalid version range '${resolution.value}'."
                        if (reference.required) error("invalid_dependency_version_range", message)
                        else warning("invalid_optional_dependency_version_range", message)
                    }

                    is LanguagePackageReferenceResolution.Missing -> {
                        if (reference.required) {
                            error("missing_dependency", resolution.message)
                        } else {
                            warning("missing_optional_dependency", resolution.message)
                        }
                    }

                    is LanguagePackageReferenceResolution.Resolved -> {
                        val dependencyEvaluation = evaluate(resolution.component)
                        if (!dependencyEvaluation.isCompatible) {
                            val message = "Dependency ${resolution.component.coordinate} is incompatible."
                            if (reference.required) error("incompatible_dependency", message)
                            else warning("incompatible_optional_dependency", message)
                        } else {
                            dependencies += ResolvedLanguagePackageDependency(reference, resolution.component)
                        }
                    }
                }
            }

            visiting.remove(component.coordinate)
            val result = LanguagePackageComponentEvaluation(
                registeredComponent = component,
                issues = issues,
                dependencies = dependencies,
            )
            cache[component.coordinate] = result
            return result
        }
    }

    companion object {
        fun fromInstalledPackages(packages: List<InstalledLanguagePackage>): LanguagePackageRegistry {
            val components = linkedMapOf<LanguagePackageComponentCoordinate, RegisteredLanguagePackageComponent>()
            val profiles = linkedMapOf<LanguagePackageProfileCoordinate, RegisteredLanguagePackageProfile>()

            packages.forEach { installedPackage ->
                installedPackage.manifest.components.forEach { component ->
                    val registered = RegisteredLanguagePackageComponent(installedPackage, component)
                    require(components.put(registered.coordinate, registered) == null) {
                        "Duplicate installed component coordinate ${registered.coordinate}."
                    }
                }
                installedPackage.manifest.profiles.forEach { profile ->
                    val registered = RegisteredLanguagePackageProfile(installedPackage, profile)
                    require(profiles.put(registered.coordinate, registered) == null) {
                        "Duplicate installed profile coordinate ${registered.coordinate}."
                    }
                }
            }

            return LanguagePackageRegistry(
                installedPackages = packages.sortedWith(
                    compareBy<InstalledLanguagePackage> { it.manifest.packageInfo.id }
                        .thenByDescending { semanticVersion(it.manifest.packageInfo.version) },
                ),
                componentsByCoordinate = components,
                profilesByCoordinate = profiles,
            )
        }

        private fun semanticVersion(value: String): LanguagePackageSemanticVersion {
            return requireNotNull(LanguagePackageSemanticVersion.parse(value)) {
                "Installed package contains invalid semantic version '$value'."
            }
        }

        private val componentIdentityComparator =
            compareBy<RegisteredLanguagePackageComponent> { it.coordinate.packageId }
                .thenByDescending { semanticVersion(it.coordinate.packageVersion) }
                .thenBy { it.coordinate.componentId }
                .thenByDescending { semanticVersion(it.coordinate.componentVersion) }

        internal val componentPreferenceComparator =
            compareByDescending<RegisteredLanguagePackageComponent> { it.component.priority }
                .thenByDescending { semanticVersion(it.coordinate.componentVersion) }
                .thenByDescending { semanticVersion(it.coordinate.packageVersion) }
                .thenBy { it.coordinate.packageId }
                .thenBy { it.coordinate.componentId }
    }
}

private fun languageTagsOverlap(target: String, supported: List<String>): Boolean {
    val normalizedTarget = target.lowercase(Locale.ROOT)
    return supported.any { candidate ->
        val normalizedCandidate = candidate.lowercase(Locale.ROOT)
        normalizedTarget == normalizedCandidate ||
            normalizedTarget.startsWith("$normalizedCandidate-") ||
            normalizedCandidate.startsWith("$normalizedTarget-")
    }
}
