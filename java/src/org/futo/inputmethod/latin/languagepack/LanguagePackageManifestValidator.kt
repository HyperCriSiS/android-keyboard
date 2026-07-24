package org.futo.inputmethod.latin.languagepack

enum class LanguagePackageValidationSeverity {
    Error,
    Warning,
}

data class LanguagePackageValidationIssue(
    val severity: LanguagePackageValidationSeverity,
    val code: String,
    val path: String,
    val message: String,
)

data class LanguagePackageValidationResult(
    val issues: List<LanguagePackageValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.none { it.severity == LanguagePackageValidationSeverity.Error }

    val errors: List<LanguagePackageValidationIssue>
        get() = issues.filter { it.severity == LanguagePackageValidationSeverity.Error }

    val warnings: List<LanguagePackageValidationIssue>
        get() = issues.filter { it.severity == LanguagePackageValidationSeverity.Warning }
}

object LanguagePackageManifestValidator {
    private val globalIdRegex = Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)+$")
    private val localIdRegex = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
    private val semanticVersionRegex = Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
            "(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$",
    )
    private val languageTagRegex = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
    private val sha256Regex = Regex("^[A-Fa-f0-9]{64}$")

    fun validate(manifest: LanguagePackageManifest): LanguagePackageValidationResult {
        val issues = mutableListOf<LanguagePackageValidationIssue>()

        fun error(code: String, path: String, message: String) {
            issues += LanguagePackageValidationIssue(
                severity = LanguagePackageValidationSeverity.Error,
                code = code,
                path = path,
                message = message,
            )
        }

        fun warning(code: String, path: String, message: String) {
            issues += LanguagePackageValidationIssue(
                severity = LanguagePackageValidationSeverity.Warning,
                code = code,
                path = path,
                message = message,
            )
        }

        if (manifest.formatVersion != CURRENT_LANGUAGE_PACKAGE_FORMAT_VERSION) {
            error(
                code = "unsupported_format_version",
                path = "formatVersion",
                message = "Unsupported language package format version '${manifest.formatVersion}'.",
            )
        }

        validatePackageInfo(manifest.packageInfo, ::error, ::warning)

        val componentsById = linkedMapOf<String, LanguagePackageComponent>()
        manifest.components.forEachIndexed { index, component ->
            val path = "components[$index]"
            validateComponent(component, manifest.packageInfo.languages, path, ::error, ::warning)

            if (componentsById.put(component.id, component) != null) {
                error(
                    code = "duplicate_component_id",
                    path = "$path.id",
                    message = "Component ID '${component.id}' occurs more than once.",
                )
            }
        }

        when (manifest.packageInfo.kind) {
            LanguagePackageKind.Bundle -> {
                if (manifest.components.isEmpty()) {
                    warning(
                        code = "empty_bundle",
                        path = "components",
                        message = "A bundle normally contains at least one component.",
                    )
                }
            }

            LanguagePackageKind.Component -> {
                if (manifest.components.size != 1) {
                    warning(
                        code = "component_package_cardinality",
                        path = "components",
                        message = "A component package normally contains exactly one primary component.",
                    )
                }
            }

            LanguagePackageKind.Profile -> {
                if (manifest.profiles.isEmpty()) {
                    error(
                        code = "profile_package_without_profile",
                        path = "profiles",
                        message = "A profile package must contain at least one profile.",
                    )
                }
            }
        }

        manifest.components.forEachIndexed { index, component ->
            component.dependencies.forEachIndexed { dependencyIndex, dependency ->
                validateReference(
                    reference = dependency,
                    currentPackageId = manifest.packageInfo.id,
                    componentsById = componentsById,
                    expectedKind = null,
                    path = "components[$index].dependencies[$dependencyIndex]",
                    error = ::error,
                    warning = ::warning,
                )
            }
        }

        val profilesById = linkedMapOf<String, LanguagePackageProfile>()
        manifest.profiles.forEachIndexed { index, profile ->
            val path = "profiles[$index]"
            validateProfile(
                profile = profile,
                currentPackageId = manifest.packageInfo.id,
                componentsById = componentsById,
                path = path,
                error = ::error,
                warning = ::warning,
            )

            if (profilesById.put(profile.id, profile) != null) {
                error(
                    code = "duplicate_profile_id",
                    path = "$path.id",
                    message = "Profile ID '${profile.id}' occurs more than once.",
                )
            }
        }

        manifest.defaultProfile?.let { defaultProfile ->
            if (!profilesById.containsKey(defaultProfile)) {
                error(
                    code = "unknown_default_profile",
                    path = "defaultProfile",
                    message = "Default profile '$defaultProfile' is not declared by this package.",
                )
            }
        }

        val ownedPaths = linkedMapOf<String, String>()
        manifest.components.forEachIndexed { index, component ->
            registerOwnedPath(
                archivePath = component.payload.path,
                ownerPath = "components[$index].payload.path",
                ownedPaths = ownedPaths,
                error = ::error,
            )
        }

        manifest.resources.forEachIndexed { index, resource ->
            val path = "resources[$index]"
            validatePayloadFields(
                archivePath = resource.path,
                sha256 = resource.sha256,
                sizeBytes = resource.sizeBytes,
                path = path,
                error = ::error,
            )
            registerOwnedPath(
                archivePath = resource.path,
                ownerPath = "$path.path",
                ownedPaths = ownedPaths,
                error = ::error,
            )
        }

        detectInternalDependencyCycles(
            packageId = manifest.packageInfo.id,
            componentsById = componentsById,
            error = ::error,
        )

        return LanguagePackageValidationResult(issues)
    }

    private fun validatePackageInfo(
        packageInfo: LanguagePackageInfo,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        if (!globalIdRegex.matches(packageInfo.id)) {
            error("invalid_package_id", "package.id", "Package ID must use reverse-domain notation.")
        }
        if (packageInfo.name.isBlank()) {
            error("blank_package_name", "package.name", "Package name must not be blank.")
        }
        if (!semanticVersionRegex.matches(packageInfo.version)) {
            error("invalid_package_version", "package.version", "Package version must use semantic versioning.")
        }
        if (packageInfo.authors.isEmpty()) {
            error("missing_package_author", "package.authors", "At least one package author is required.")
        }
        packageInfo.authors.forEachIndexed { index, author ->
            if (author.name.isBlank()) {
                error("blank_author_name", "package.authors[$index].name", "Author name must not be blank.")
            }
        }
        if (packageInfo.license.isBlank()) {
            error("blank_package_license", "package.license", "Package license must not be blank.")
        }
        validateLanguages(packageInfo.languages, "package.languages", error, warning)
    }

    private fun validateComponent(
        component: LanguagePackageComponent,
        packageLanguages: List<String>,
        path: String,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        if (!localIdRegex.matches(component.id)) {
            error("invalid_component_id", "$path.id", "Component ID contains unsupported characters.")
        }
        if (component.name.isBlank()) {
            error("blank_component_name", "$path.name", "Component name must not be blank.")
        }
        if (!semanticVersionRegex.matches(component.version)) {
            error("invalid_component_version", "$path.version", "Component version must use semantic versioning.")
        }

        val expectedActivation = when (component.kind) {
            LanguagePackageComponentKind.Dictionary,
            LanguagePackageComponentKind.LanguageRules,
            LanguagePackageComponentKind.PersonalizationAdapter,
            -> LanguagePackageActivation.Stackable

            LanguagePackageComponentKind.CandidateGenerator,
            LanguagePackageComponentKind.ContextRanker,
            LanguagePackageComponentKind.CorrectionModel,
            LanguagePackageComponentKind.SwipeModel,
            -> LanguagePackageActivation.Exclusive
        }

        if (component.activation != expectedActivation) {
            error(
                "invalid_component_activation",
                "$path.activation",
                "${component.kind} components must use ${expectedActivation.name.lowercase()} activation.",
            )
        }

        validateLanguages(component.languages, "$path.languages", error, warning)
        if (component.languages.none { componentLanguage ->
                packageLanguages.any { packageLanguage ->
                    languageTagsOverlap(componentLanguage, packageLanguage)
                }
            }
        ) {
            error(
                "component_language_outside_package",
                "$path.languages",
                "Component languages do not overlap with the package languages.",
            )
        }

        if (component.tasks.isEmpty()) {
            error("missing_component_task", "$path.tasks", "At least one component task is required.")
        }
        warnOnDuplicates(component.tasks, "$path.tasks", "duplicate_task", warning)
        warnOnDuplicates(component.layouts, "$path.layouts", "duplicate_layout", warning)
        warnOnDuplicates(
            component.capabilities.required,
            "$path.capabilities.required",
            "duplicate_required_capability",
            warning,
        )
        warnOnDuplicates(
            component.capabilities.optional,
            "$path.capabilities.optional",
            "duplicate_optional_capability",
            warning,
        )

        val overlappingCapabilities = component.capabilities.required
            .intersect(component.capabilities.optional.toSet())
        if (overlappingCapabilities.isNotEmpty()) {
            warning(
                "capability_required_and_optional",
                "$path.capabilities",
                "Capabilities cannot meaningfully be both required and optional: ${overlappingCapabilities.joinToString()}.",
            )
        }

        val compatibility = component.compatibility
        if (
            compatibility.minKeyboardApi != null &&
            compatibility.maxKeyboardApi != null &&
            compatibility.minKeyboardApi > compatibility.maxKeyboardApi
        ) {
            error(
                "invalid_keyboard_api_range",
                "$path.compatibility",
                "minKeyboardApi must not exceed maxKeyboardApi.",
            )
        }
        if (compatibility.minKeyboardApi != null && compatibility.minKeyboardApi < 1) {
            error("invalid_min_keyboard_api", "$path.compatibility.minKeyboardApi", "Value must be positive.")
        }
        if (compatibility.maxKeyboardApi != null && compatibility.maxKeyboardApi < 1) {
            error("invalid_max_keyboard_api", "$path.compatibility.maxKeyboardApi", "Value must be positive.")
        }
        if (compatibility.minRamMb != null && compatibility.minRamMb < 0) {
            error("invalid_min_ram", "$path.compatibility.minRamMb", "Value must not be negative.")
        }
        warnOnDuplicates(
            compatibility.androidAbis,
            "$path.compatibility.androidAbis",
            "duplicate_android_abi",
            warning,
        )
        warnOnDuplicates(
            compatibility.runtimeFeatures,
            "$path.compatibility.runtimeFeatures",
            "duplicate_runtime_feature",
            warning,
        )

        validatePayloadFields(
            archivePath = component.payload.path,
            sha256 = component.payload.sha256,
            sizeBytes = component.payload.sizeBytes,
            path = "$path.payload",
            error = error,
        )
        if (component.payload.mediaType.isBlank() || !component.payload.mediaType.contains('/')) {
            error("invalid_media_type", "$path.payload.mediaType", "Payload media type is invalid.")
        }
    }

    private fun validateProfile(
        profile: LanguagePackageProfile,
        currentPackageId: String,
        componentsById: Map<String, LanguagePackageComponent>,
        path: String,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        if (!localIdRegex.matches(profile.id)) {
            error("invalid_profile_id", "$path.id", "Profile ID contains unsupported characters.")
        }
        if (profile.name.isBlank()) {
            error("blank_profile_name", "$path.name", "Profile name must not be blank.")
        }

        val usedSlots = mutableSetOf<LanguagePackageComponentKind>()
        profile.selections.forEachIndexed { index, selection ->
            val selectionPath = "$path.selections[$index]"
            if (!usedSlots.add(selection.slot)) {
                error(
                    "duplicate_profile_slot",
                    "$selectionPath.slot",
                    "A profile may declare each component slot only once.",
                )
            }

            val slotIsStackable = selection.slot in setOf(
                LanguagePackageComponentKind.Dictionary,
                LanguagePackageComponentKind.LanguageRules,
                LanguagePackageComponentKind.PersonalizationAdapter,
            )

            if (!slotIsStackable && selection.strategy == LanguagePackageSelectionStrategy.Append) {
                error(
                    "append_on_exclusive_slot",
                    "$selectionPath.strategy",
                    "Exclusive component slots cannot use the append strategy.",
                )
            }
            if (!slotIsStackable && selection.components.size > 1) {
                error(
                    "multiple_components_for_exclusive_slot",
                    "$selectionPath.components",
                    "An exclusive component slot may reference at most one component.",
                )
            }

            selection.components.forEachIndexed { referenceIndex, reference ->
                validateReference(
                    reference = reference,
                    currentPackageId = currentPackageId,
                    componentsById = componentsById,
                    expectedKind = selection.slot,
                    path = "$selectionPath.components[$referenceIndex]",
                    error = error,
                    warning = warning,
                )
            }
        }
    }

    private fun validateReference(
        reference: LanguagePackageComponentReference,
        currentPackageId: String,
        componentsById: Map<String, LanguagePackageComponent>,
        expectedKind: LanguagePackageComponentKind?,
        path: String,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        if (!localIdRegex.matches(reference.componentId)) {
            error("invalid_component_reference", "$path.componentId", "Referenced component ID is invalid.")
        }
        if (reference.packageId != null && !globalIdRegex.matches(reference.packageId)) {
            error("invalid_package_reference", "$path.packageId", "Referenced package ID is invalid.")
        }
        if (reference.versionRange != null && reference.versionRange.isBlank()) {
            error("blank_version_range", "$path.versionRange", "Version range must not be blank.")
        }
        if (!reference.weight.isFinite() || reference.weight < 0.0f || reference.weight > 100.0f) {
            error("invalid_component_weight", "$path.weight", "Component weight must be between 0 and 100.")
        }

        val isInternal = reference.packageId == null || reference.packageId == currentPackageId
        if (isInternal) {
            val component = componentsById[reference.componentId]
            if (component == null) {
                error(
                    "unknown_internal_component",
                    "$path.componentId",
                    "Component '${reference.componentId}' is not declared by this package.",
                )
            } else if (expectedKind != null && component.kind != expectedKind) {
                error(
                    "component_slot_mismatch",
                    "$path.componentId",
                    "Component '${reference.componentId}' has kind ${component.kind}, not $expectedKind.",
                )
            }
        } else if (reference.versionRange == null) {
            warning(
                "unpinned_external_component",
                path,
                "External component reference has no version range.",
            )
        }
    }

    private fun validateLanguages(
        languages: List<String>,
        path: String,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        if (languages.isEmpty()) {
            error("missing_language", path, "At least one language tag is required.")
            return
        }

        languages.forEachIndexed { index, language ->
            if (!languageTagRegex.matches(language)) {
                error("invalid_language_tag", "$path[$index]", "'$language' is not a valid language tag.")
            }
        }
        warnOnDuplicates(languages.map { it.lowercase() }, path, "duplicate_language", warning)
    }

    private fun validatePayloadFields(
        archivePath: String,
        sha256: String,
        sizeBytes: Long,
        path: String,
        error: (String, String, String) -> Unit,
    ) {
        if (!isSafeArchivePath(archivePath)) {
            error("unsafe_archive_path", "$path.path", "Archive path is not a safe normalized relative path.")
        }
        if (!sha256Regex.matches(sha256)) {
            error("invalid_sha256", "$path.sha256", "SHA-256 digest must contain exactly 64 hexadecimal characters.")
        }
        if (sizeBytes < 0) {
            error("invalid_payload_size", "$path.sizeBytes", "Payload size must not be negative.")
        }
    }

    private fun registerOwnedPath(
        archivePath: String,
        ownerPath: String,
        ownedPaths: MutableMap<String, String>,
        error: (String, String, String) -> Unit,
    ) {
        val normalized = archivePath.lowercase()
        val previousOwner = ownedPaths.put(normalized, ownerPath)
        if (previousOwner != null) {
            error(
                "duplicate_archive_path",
                ownerPath,
                "Archive path '$archivePath' is already owned by $previousOwner.",
            )
        }
    }

    private fun detectInternalDependencyCycles(
        packageId: String,
        componentsById: Map<String, LanguagePackageComponent>,
        error: (String, String, String) -> Unit,
    ) {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val stack = mutableListOf<String>()
        val reportedCycles = mutableSetOf<String>()

        fun visit(componentId: String) {
            if (componentId in visited) return
            if (!visiting.add(componentId)) {
                val cycleStart = stack.indexOf(componentId).coerceAtLeast(0)
                val cycle = (stack.drop(cycleStart) + componentId).joinToString(" -> ")
                if (reportedCycles.add(cycle)) {
                    error(
                        "component_dependency_cycle",
                        "components",
                        "Internal component dependency cycle detected: $cycle.",
                    )
                }
                return
            }

            stack += componentId
            componentsById[componentId]?.dependencies
                ?.filter { it.packageId == null || it.packageId == packageId }
                ?.map { it.componentId }
                ?.filter { componentsById.containsKey(it) }
                ?.forEach(::visit)
            stack.removeAt(stack.lastIndex)
            visiting.remove(componentId)
            visited.add(componentId)
        }

        componentsById.keys.forEach(::visit)
    }

    private fun isSafeArchivePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return false
        val segments = path.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun languageTagsOverlap(first: String, second: String): Boolean {
        val a = first.lowercase()
        val b = second.lowercase()
        return a == b || a.startsWith("$b-") || b.startsWith("$a-")
    }

    private fun warnOnDuplicates(
        values: List<String>,
        path: String,
        code: String,
        warning: (String, String, String) -> Unit,
    ) {
        val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            warning(code, path, "Duplicate values: ${duplicates.joinToString()}.")
        }
    }
}
