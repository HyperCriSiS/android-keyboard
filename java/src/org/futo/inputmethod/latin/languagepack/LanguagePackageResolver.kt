package org.futo.inputmethod.latin.languagepack

import java.util.Locale

enum class LanguagePackageResolutionSeverity {
    Error,
    Warning,
}

data class LanguagePackageResolutionIssue(
    val severity: LanguagePackageResolutionSeverity,
    val code: String,
    val slot: LanguagePackageComponentKind? = null,
    val message: String,
)

enum class LanguagePackageSelectionSource {
    User,
    Profile,
    Automatic,
    Dependency,
}

data class PlannedLanguagePackageComponent(
    val component: RegisteredLanguagePackageComponent,
    val source: LanguagePackageSelectionSource,
    val weight: Float = 1.0f,
)

data class ResolvedLanguagePackageSlot(
    val kind: LanguagePackageComponentKind,
    val activation: LanguagePackageActivation,
    val selected: List<PlannedLanguagePackageComponent>,
    val compatibleAlternatives: List<RegisteredLanguagePackageComponent>,
    val explicitlyDisabled: Boolean,
)

data class LanguagePackageResolutionPlan(
    val target: LanguagePackageTarget,
    val profile: RegisteredLanguagePackageProfile?,
    val slots: Map<LanguagePackageComponentKind, ResolvedLanguagePackageSlot>,
    val evaluations: Map<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation>,
    val issues: List<LanguagePackageResolutionIssue>,
) {
    val isValid: Boolean
        get() = issues.none { it.severity == LanguagePackageResolutionSeverity.Error }

    val selectedComponents: List<PlannedLanguagePackageComponent>
        get() = slots.values.flatMap { it.selected }
}

sealed class LanguagePackageSlotOverride {
    data object Automatic : LanguagePackageSlotOverride()
    data object Disabled : LanguagePackageSlotOverride()

    data class Select(
        val strategy: LanguagePackageSelectionStrategy,
        val components: List<LanguagePackageComponentCoordinate>,
    ) : LanguagePackageSlotOverride()
}

data class LanguagePackageResolutionRequest(
    val target: LanguagePackageTarget,
    val environment: LanguagePackageRuntimeEnvironment,
    val profile: LanguagePackageProfileCoordinate? = null,
    val overrides: Map<LanguagePackageComponentKind, LanguagePackageSlotOverride> = emptyMap(),
)

class LanguagePackageResolver(
    private val registry: LanguagePackageRegistry,
) {
    fun resolve(request: LanguagePackageResolutionRequest): LanguagePackageResolutionPlan {
        val issues = mutableListOf<LanguagePackageResolutionIssue>()
        val evaluations = registry.evaluateAll(request.target, request.environment)
        val profile = request.profile?.let { coordinate ->
            registry.findProfile(coordinate).also { resolved ->
                if (resolved == null) {
                    issues += LanguagePackageResolutionIssue(
                        severity = LanguagePackageResolutionSeverity.Error,
                        code = "missing_profile",
                        message = "Installed profile '$coordinate' was not found.",
                    )
                }
            }
        }

        val profileSelections = profile?.profile?.selections
            ?.associateBy { it.slot }
            .orEmpty()
        val selectedBySlot = LanguagePackageComponentKind.entries.associateWith {
            mutableListOf<PlannedLanguagePackageComponent>()
        }.toMutableMap()
        val disabledSlots = mutableSetOf<LanguagePackageComponentKind>()

        LanguagePackageComponentKind.entries.forEach { kind ->
            val activation = activationFor(kind)
            val override = request.overrides[kind]
            val profileSelection = profileSelections[kind]

            when (override) {
                LanguagePackageSlotOverride.Disabled -> disabledSlots += kind

                LanguagePackageSlotOverride.Automatic -> {
                    selectAutomatically(
                        kind = kind,
                        activation = activation,
                        target = request.target,
                        evaluations = evaluations,
                        destination = selectedBySlot.getValue(kind),
                        issues = issues,
                    )
                }

                is LanguagePackageSlotOverride.Select -> {
                    if (override.strategy == LanguagePackageSelectionStrategy.Append &&
                        activation == LanguagePackageActivation.Exclusive
                    ) {
                        issues += LanguagePackageResolutionIssue(
                            severity = LanguagePackageResolutionSeverity.Error,
                            code = "append_on_exclusive_slot",
                            slot = kind,
                            message = "Exclusive slot $kind cannot use append selection.",
                        )
                    } else {
                        if (override.strategy == LanguagePackageSelectionStrategy.Append && profileSelection != null) {
                            resolveProfileSelection(
                                profile = profile,
                                selection = profileSelection,
                                evaluations = evaluations,
                                destination = selectedBySlot.getValue(kind),
                                issues = issues,
                            )
                        }
                        resolveUserSelection(
                            kind = kind,
                            coordinates = override.components,
                            evaluations = evaluations,
                            destination = selectedBySlot.getValue(kind),
                            issues = issues,
                        )
                    }
                }

                null -> {
                    if (profileSelection != null) {
                        resolveProfileSelection(
                            profile = profile,
                            selection = profileSelection,
                            evaluations = evaluations,
                            destination = selectedBySlot.getValue(kind),
                            issues = issues,
                        )
                    } else {
                        selectAutomatically(
                            kind = kind,
                            activation = activation,
                            target = request.target,
                            evaluations = evaluations,
                            destination = selectedBySlot.getValue(kind),
                            issues = issues,
                        )
                    }
                }
            }

            enforceSlotCardinality(kind, activation, selectedBySlot.getValue(kind), issues)
        }

        addRequiredDependencyClosure(
            selectedBySlot = selectedBySlot,
            disabledSlots = disabledSlots,
            evaluations = evaluations,
            issues = issues,
        )

        val slots = LanguagePackageComponentKind.entries.associateWith { kind ->
            val activation = activationFor(kind)
            val alternatives = compatibleCandidates(kind, evaluations)
                .sortedWith(automaticComparator(request.target))
            val selected = selectedBySlot.getValue(kind)
                .distinctBy { it.component.coordinate }
                .let { components ->
                    if (activation == LanguagePackageActivation.Stackable) {
                        components.sortedWith(
                            compareBy<PlannedLanguagePackageComponent> { it.component.component.priority }
                                .thenBy { it.component.coordinate.packageId }
                                .thenBy { it.component.coordinate.componentId },
                        )
                    } else {
                        components
                    }
                }

            ResolvedLanguagePackageSlot(
                kind = kind,
                activation = activation,
                selected = selected,
                compatibleAlternatives = alternatives,
                explicitlyDisabled = kind in disabledSlots,
            )
        }

        return LanguagePackageResolutionPlan(
            target = request.target,
            profile = profile,
            slots = slots,
            evaluations = evaluations,
            issues = issues,
        )
    }

    private fun resolveProfileSelection(
        profile: RegisteredLanguagePackageProfile?,
        selection: LanguagePackageProfileSelection,
        evaluations: Map<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation>,
        destination: MutableList<PlannedLanguagePackageComponent>,
        issues: MutableList<LanguagePackageResolutionIssue>,
    ) {
        if (profile == null) return
        if (selection.strategy == LanguagePackageSelectionStrategy.Replace) destination.clear()

        selection.components.forEach { reference ->
            when (val resolution = registry.resolveReference(profile.installedPackage, reference)) {
                is LanguagePackageReferenceResolution.InvalidVersionRange -> {
                    addReferenceIssue(
                        required = reference.required,
                        code = "invalid_profile_version_range",
                        slot = selection.slot,
                        message = "Profile reference uses invalid version range '${resolution.value}'.",
                        issues = issues,
                    )
                }

                is LanguagePackageReferenceResolution.Missing -> {
                    addReferenceIssue(
                        required = reference.required,
                        code = "missing_profile_component",
                        slot = selection.slot,
                        message = resolution.message,
                        issues = issues,
                    )
                }

                is LanguagePackageReferenceResolution.Resolved -> {
                    addSelectedComponent(
                        expectedKind = selection.slot,
                        component = resolution.component,
                        source = LanguagePackageSelectionSource.Profile,
                        weight = reference.weight,
                        evaluations = evaluations,
                        destination = destination,
                        issues = issues,
                    )
                }
            }
        }
    }

    private fun resolveUserSelection(
        kind: LanguagePackageComponentKind,
        coordinates: List<LanguagePackageComponentCoordinate>,
        evaluations: Map<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation>,
        destination: MutableList<PlannedLanguagePackageComponent>,
        issues: MutableList<LanguagePackageResolutionIssue>,
    ) {
        coordinates.forEach { coordinate ->
            val component = registry.findComponent(coordinate)
            if (component == null) {
                issues += LanguagePackageResolutionIssue(
                    severity = LanguagePackageResolutionSeverity.Error,
                    code = "missing_user_component",
                    slot = kind,
                    message = "User-selected component '$coordinate' is not installed.",
                )
            } else {
                addSelectedComponent(
                    expectedKind = kind,
                    component = component,
                    source = LanguagePackageSelectionSource.User,
                    weight = 1.0f,
                    evaluations = evaluations,
                    destination = destination,
                    issues = issues,
                )
            }
        }
    }

    private fun addSelectedComponent(
        expectedKind: LanguagePackageComponentKind,
        component: RegisteredLanguagePackageComponent,
        source: LanguagePackageSelectionSource,
        weight: Float,
        evaluations: Map<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation>,
        destination: MutableList<PlannedLanguagePackageComponent>,
        issues: MutableList<LanguagePackageResolutionIssue>,
    ) {
        if (component.component.kind != expectedKind) {
            issues += LanguagePackageResolutionIssue(
                severity = LanguagePackageResolutionSeverity.Error,
                code = "component_slot_mismatch",
                slot = expectedKind,
                message = "Component ${component.coordinate} belongs to ${component.component.kind}.",
            )
            return
        }

        val evaluation = evaluations[component.coordinate]
        if (evaluation == null || !evaluation.isCompatible) {
            val reasons = evaluation?.issues
                ?.filter { it.severity == LanguagePackageCompatibilitySeverity.Error }
                ?.joinToString { it.message }
                .orEmpty()
            issues += LanguagePackageResolutionIssue(
                severity = LanguagePackageResolutionSeverity.Error,
                code = "selected_component_incompatible",
                slot = expectedKind,
                message = "Component ${component.coordinate} is incompatible. $reasons".trim(),
            )
            return
        }

        mergePlannedComponent(
            destination,
            PlannedLanguagePackageComponent(component, source, weight),
        )
    }

    private fun selectAutomatically(
        kind: LanguagePackageComponentKind,
        activation: LanguagePackageActivation,
        target: LanguagePackageTarget,
        evaluations: Map<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation>,
        destination: MutableList<PlannedLanguagePackageComponent>,
        issues: MutableList<LanguagePackageResolutionIssue>,
    ) {
        if (activation == LanguagePackageActivation.Stackable) {
            // Installing a specialist dictionary or rule set must not silently activate it.
            return
        }

        val candidates = compatibleCandidates(kind, evaluations)
            .sortedWith(automaticComparator(target))
        val selected = candidates.firstOrNull() ?: return
        destination += PlannedLanguagePackageComponent(
            component = selected,
            source = LanguagePackageSelectionSource.Automatic,
        )

        if (candidates.size > 1) {
            issues += LanguagePackageResolutionIssue(
                severity = LanguagePackageResolutionSeverity.Warning,
                code = "automatic_component_selected",
                slot = kind,
                message = "Automatically selected ${selected.coordinate} from ${candidates.size} compatible candidates.",
            )
        }
    }

    private fun addRequiredDependencyClosure(
        selectedBySlot: MutableMap<LanguagePackageComponentKind, MutableList<PlannedLanguagePackageComponent>>,
        disabledSlots: Set<LanguagePackageComponentKind>,
        evaluations: Map<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation>,
        issues: MutableList<LanguagePackageResolutionIssue>,
    ) {
        val queue = ArrayDeque(
            selectedBySlot.values.flatten().map { it.component },
        )
        val visited = mutableSetOf<LanguagePackageComponentCoordinate>()

        while (queue.isNotEmpty()) {
            val selected = queue.removeFirst()
            if (!visited.add(selected.coordinate)) continue

            val evaluation = evaluations[selected.coordinate] ?: continue
            evaluation.dependencies
                .filter { it.reference.required }
                .forEach { dependency ->
                    val component = dependency.component
                    val kind = component.component.kind
                    if (kind in disabledSlots) {
                        issues += LanguagePackageResolutionIssue(
                            severity = LanguagePackageResolutionSeverity.Error,
                            code = "required_dependency_disabled",
                            slot = kind,
                            message = "${selected.coordinate} requires ${component.coordinate}, but the slot is disabled.",
                        )
                        return@forEach
                    }

                    val slot = selectedBySlot.getValue(kind)
                    val existing = slot.firstOrNull { it.component.coordinate == component.coordinate }
                    if (existing != null) return@forEach

                    if (component.component.activation == LanguagePackageActivation.Exclusive && slot.isNotEmpty()) {
                        issues += LanguagePackageResolutionIssue(
                            severity = LanguagePackageResolutionSeverity.Error,
                            code = "exclusive_dependency_conflict",
                            slot = kind,
                            message = "${selected.coordinate} requires ${component.coordinate}, but ${slot.first().component.coordinate} is already selected.",
                        )
                        return@forEach
                    }

                    val planned = PlannedLanguagePackageComponent(
                        component = component,
                        source = LanguagePackageSelectionSource.Dependency,
                        weight = dependency.reference.weight,
                    )
                    slot += planned
                    queue += component
                }
        }
    }

    private fun enforceSlotCardinality(
        kind: LanguagePackageComponentKind,
        activation: LanguagePackageActivation,
        selected: List<PlannedLanguagePackageComponent>,
        issues: MutableList<LanguagePackageResolutionIssue>,
    ) {
        if (activation == LanguagePackageActivation.Exclusive &&
            selected.distinctBy { it.component.coordinate }.size > 1
        ) {
            issues += LanguagePackageResolutionIssue(
                severity = LanguagePackageResolutionSeverity.Error,
                code = "multiple_exclusive_components",
                slot = kind,
                message = "Exclusive slot $kind has more than one selected component.",
            )
        }
    }

    private fun compatibleCandidates(
        kind: LanguagePackageComponentKind,
        evaluations: Map<LanguagePackageComponentCoordinate, LanguagePackageComponentEvaluation>,
    ): List<RegisteredLanguagePackageComponent> {
        return evaluations.values
            .filter { it.isCompatible && it.registeredComponent.component.kind == kind }
            .map { it.registeredComponent }
    }

    private fun automaticComparator(target: LanguagePackageTarget): Comparator<RegisteredLanguagePackageComponent> {
        return compareByDescending<RegisteredLanguagePackageComponent> {
            languageSpecificity(target.languageTag, it.component.languages)
        }.thenByDescending {
            layoutSpecificity(target.layout, it.component.layouts)
        }.thenByDescending {
            it.component.priority
        }.thenByDescending {
            semanticVersion(it.coordinate.componentVersion)
        }.thenByDescending {
            semanticVersion(it.coordinate.packageVersion)
        }.thenBy {
            it.coordinate.packageId
        }.thenBy {
            it.coordinate.componentId
        }
    }

    private fun mergePlannedComponent(
        destination: MutableList<PlannedLanguagePackageComponent>,
        incoming: PlannedLanguagePackageComponent,
    ) {
        val index = destination.indexOfFirst {
            it.component.coordinate == incoming.component.coordinate
        }
        if (index < 0) {
            destination += incoming
            return
        }

        val existing = destination[index]
        destination[index] = when {
            sourcePriority(incoming.source) > sourcePriority(existing.source) -> incoming
            sourcePriority(incoming.source) < sourcePriority(existing.source) -> existing
            incoming.weight > existing.weight -> incoming
            else -> existing
        }
    }

    private fun addReferenceIssue(
        required: Boolean,
        code: String,
        slot: LanguagePackageComponentKind,
        message: String,
        issues: MutableList<LanguagePackageResolutionIssue>,
    ) {
        issues += LanguagePackageResolutionIssue(
            severity = if (required) {
                LanguagePackageResolutionSeverity.Error
            } else {
                LanguagePackageResolutionSeverity.Warning
            },
            code = code,
            slot = slot,
            message = message,
        )
    }

    companion object {
        fun activationFor(kind: LanguagePackageComponentKind): LanguagePackageActivation {
            return when (kind) {
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
        }

        private fun sourcePriority(source: LanguagePackageSelectionSource): Int = when (source) {
            LanguagePackageSelectionSource.User -> 4
            LanguagePackageSelectionSource.Profile -> 3
            LanguagePackageSelectionSource.Automatic -> 2
            LanguagePackageSelectionSource.Dependency -> 1
        }

        private fun languageSpecificity(target: String, supported: List<String>): Int {
            val normalizedTarget = target.lowercase(Locale.ROOT)
            return when {
                supported.any { it.lowercase(Locale.ROOT) == normalizedTarget } -> 2
                supported.any {
                    val normalized = it.lowercase(Locale.ROOT)
                    normalizedTarget.startsWith("$normalized-") || normalized.startsWith("$normalizedTarget-")
                } -> 1
                else -> 0
            }
        }

        private fun layoutSpecificity(target: String?, supported: List<String>): Int {
            if (target == null) return if (supported.isEmpty()) 1 else 0
            val normalizedTarget = target.lowercase(Locale.ROOT)
            return when {
                supported.any { it.lowercase(Locale.ROOT) == normalizedTarget } -> 2
                supported.isEmpty() -> 1
                else -> 0
            }
        }

        private fun semanticVersion(value: String): LanguagePackageSemanticVersion {
            return requireNotNull(LanguagePackageSemanticVersion.parse(value)) {
                "Invalid semantic version '$value'."
            }
        }
    }
}
