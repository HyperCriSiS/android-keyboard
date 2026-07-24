package org.futo.inputmethod.latin.uix.settings.pages

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.languagepack.LanguagePackageCompatibilitySeverity
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponentCoordinate
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponentKind
import org.futo.inputmethod.latin.languagepack.LanguagePackageRegistry
import org.futo.inputmethod.latin.languagepack.LanguagePackageResolutionPlan
import org.futo.inputmethod.latin.languagepack.LanguagePackageResolutionRequest
import org.futo.inputmethod.latin.languagepack.LanguagePackageResolutionSeverity
import org.futo.inputmethod.latin.languagepack.LanguagePackageResolver
import org.futo.inputmethod.latin.languagepack.LanguagePackageRuntimeEnvironment
import org.futo.inputmethod.latin.languagepack.LanguagePackageStore
import org.futo.inputmethod.latin.languagepack.LanguagePackageTarget
import org.futo.inputmethod.latin.languagepack.RegisteredLanguagePackageComponent
import org.futo.inputmethod.latin.languagepack.buildRegistry
import org.futo.inputmethod.latin.languagepack.ranker.CANDIDATE_RANKING_TASK_V1
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerCandidate
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerOpenOutcome
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerOutcome
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerProbeOutcome
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerRequest
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerScoringPolicy
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankingPurpose
import org.futo.inputmethod.latin.languagepack.ranker.FULL_CANDIDATE_LOGPROB_CAPABILITY
import org.futo.inputmethod.latin.languagepack.ranker.createDefaultCandidateRankerProviderRegistry
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.theme.Typography

private data class LanguagePackageRegistrySnapshot(
    val registry: LanguagePackageRegistry,
    val plan: LanguagePackageResolutionPlan,
    val environment: LanguagePackageRuntimeEnvironment,
)

private sealed class RankerDebugResult {
    data object Loading : RankerDebugResult()
    data class Message(val text: String, val isError: Boolean) : RankerDebugResult()
}

@Composable
fun DevLanguagePackageRegistryScreen(
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) {
        LanguagePackageStore.forContext(context.applicationContext)
    }
    val providerRegistry = remember { createDefaultCandidateRankerProviderRegistry() }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<LanguagePackageRegistrySnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val rankerResults = remember { mutableStateMapOf<LanguagePackageComponentCoordinate, RankerDebugResult>() }

    LaunchedEffect(revision) {
        loading = true
        loadError = null
        snapshot = try {
            withContext(Dispatchers.IO) {
                val registry = store.buildRegistry()
                val environment = runtimeEnvironment(context)
                val plan = LanguagePackageResolver(registry).resolve(
                    LanguagePackageResolutionRequest(
                        target = LanguagePackageTarget("de-DE", "qwertz"),
                        environment = environment,
                    ),
                )
                LanguagePackageRegistrySnapshot(registry, plan, environment)
            }
        } catch (exception: Exception) {
            loadError = exception.message ?: exception.javaClass.simpleName
            null
        } finally {
            loading = false
        }
    }

    ScrollableList(spacing = 8.dp) {
        ScreenTitle("Language package registry", showBack = true, navController)
        Tip(
            "Debug-only registry and ranker console. It evaluates an inactive de-DE/QWERTZ plan. " +
                "Probing or scoring loads a model temporarily but never activates it.",
        )

        Button(
            onClick = {
                rankerResults.clear()
                revision += 1
            },
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text("Refresh installed packages")
        }

        if (loading) {
            ProgressRow("Building registry…")
        }
        loadError?.let { error ->
            DebugStatusCard(isError = true) {
                Text("Registry failed", style = Typography.Heading.RegularMl)
                Text(error, style = Typography.Body.RegularMl)
            }
        }

        snapshot?.let { current ->
            RegistrySummary(current)
            ResolutionPlanView(current.plan)

            Text(
                "Installed components",
                style = Typography.Heading.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (current.registry.components.isEmpty()) {
                DebugCard("No components") {
                    Text("Install a .futolanguage package through the package inspector first.")
                }
            } else {
                current.registry.components.forEach { component ->
                    val evaluation = current.plan.evaluations[component.coordinate]
                    ComponentDebugCard(
                        component = component,
                        compatible = evaluation?.isCompatible == true,
                        issues = evaluation?.issues.orEmpty().map {
                            "${it.severity.name}: ${it.code} — ${it.message}"
                        },
                        rankerResult = rankerResults[component.coordinate],
                        onProbe = if (
                            component.component.kind == LanguagePackageComponentKind.ContextRanker &&
                            component.component.runtime != null
                        ) {
                            {
                                rankerResults[component.coordinate] = RankerDebugResult.Loading
                                scope.launch {
                                    rankerResults[component.coordinate] = probeRanker(
                                        providerRegistry = providerRegistry,
                                        component = component,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        onScore = if (
                            component.component.kind == LanguagePackageComponentKind.ContextRanker &&
                            component.component.runtime != null &&
                            evaluation?.isCompatible == true
                        ) {
                            {
                                rankerResults[component.coordinate] = RankerDebugResult.Loading
                                scope.launch {
                                    rankerResults[component.coordinate] = scoreGermanSample(
                                        providerRegistry = providerRegistry,
                                        component = component,
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RegistrySummary(snapshot: LanguagePackageRegistrySnapshot) {
    DebugCard("Registry") {
        Property("Packages", snapshot.registry.installedPackages.size.toString())
        Property("Components", snapshot.registry.components.size.toString())
        Property("Profiles", snapshot.registry.profiles.size.toString())
        Property("Target", "de-DE · qwertz")
        Property("ABI", snapshot.environment.androidAbi)
        Property("Detected RAM", "${snapshot.environment.ramMb} MB")
    }
}

@Composable
private fun ResolutionPlanView(plan: LanguagePackageResolutionPlan) {
    DebugStatusCard(isError = !plan.isValid) {
        Text(
            if (plan.isValid) "Automatic plan is valid" else "Automatic plan is invalid",
            style = Typography.Heading.RegularMl,
        )
        Text(
            "${plan.selectedComponents.size} selected components · ${plan.issues.size} plan issues",
            style = Typography.Body.RegularMl,
        )
    }

    DebugCard("Selection plan") {
        plan.slots.values.forEach { slot ->
            val selection = when {
                slot.explicitlyDisabled -> "Disabled"
                slot.selected.isEmpty() -> "Built-in fallback / none"
                else -> slot.selected.joinToString { selected ->
                    "${selected.component.component.name} [${selected.source.name}]"
                }
            }
            Property(slot.kind.name, selection)
        }
    }

    if (plan.issues.isNotEmpty()) {
        DebugCard("Plan issues") {
            plan.issues.forEach { issue ->
                Text(
                    "${issue.severity.name}: ${issue.code}" +
                        (issue.slot?.let { " · ${it.name}" } ?: ""),
                    style = Typography.Heading.RegularMl,
                    color = if (issue.severity == LanguagePackageResolutionSeverity.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        LocalContentColor.current
                    },
                )
                Text(issue.message, style = Typography.Body.RegularMl)
            }
        }
    }
}

@Composable
private fun ComponentDebugCard(
    component: RegisteredLanguagePackageComponent,
    compatible: Boolean,
    issues: List<String>,
    rankerResult: RankerDebugResult?,
    onProbe: (() -> Unit)?,
    onScore: (() -> Unit)?,
) {
    DebugCard(component.component.name) {
        Property("Coordinate", component.coordinate.toString())
        Property("Kind", component.component.kind.name)
        Property("Activation", component.component.activation.name)
        Property("Compatibility", if (compatible) "Compatible" else "Unavailable")
        Property("Tasks", component.component.tasks.joinToString())
        Property("Runtime", component.component.runtime?.let { "${it.id} API ${it.apiVersion}" } ?: "None")
        Property("Payload", component.payloadFile.absolutePath)

        issues.forEach { issue ->
            Text(
                issue,
                style = Typography.SmallMl,
                color = if (issue.startsWith(LanguagePackageCompatibilitySeverity.Error.name)) {
                    MaterialTheme.colorScheme.error
                } else {
                    LocalContentColor.current.copy(alpha = 0.8f)
                },
            )
        }

        if (onProbe != null || onScore != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onProbe?.let {
                    Button(onClick = it, modifier = Modifier.weight(1f)) {
                        Text("Probe")
                    }
                }
                onScore?.let {
                    Button(onClick = it, modifier = Modifier.weight(1f)) {
                        Text("Score sample")
                    }
                }
            }
        }

        when (rankerResult) {
            RankerDebugResult.Loading -> ProgressRow("Running model operation…")
            is RankerDebugResult.Message -> {
                Surface(
                    color = if (rankerResult.isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (rankerResult.isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        rankerResult.text,
                        modifier = Modifier.padding(12.dp),
                        style = Typography.SmallMl,
                    )
                }
            }
            null -> Unit
        }
    }
}

private suspend fun probeRanker(
    providerRegistry: org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerProviderRegistry,
    component: RegisteredLanguagePackageComponent,
): RankerDebugResult {
    return when (val result = providerRegistry.probe(component)) {
        is CandidateRankerProbeOutcome.Ready -> RankerDebugResult.Message(
            text = buildString {
                append("Ready: ")
                append(result.descriptor.modelName)
                append(" · context ")
                append(result.descriptor.maxContextTokens)
                append(" tokens · batch ")
                append(result.descriptor.maxBatchSize)
                append(" · boundary ")
                append(result.descriptor.boundaryMode.name)
                if (result.issues.isNotEmpty()) {
                    append("\n")
                    append(result.issues.joinToString("\n") { "${it.code}: ${it.message}" })
                }
            },
            isError = false,
        )

        is CandidateRankerProbeOutcome.Unavailable -> RankerDebugResult.Message(
            text = result.issues.joinToString("\n") { "${it.code}: ${it.message}" },
            isError = true,
        )
    }
}

private suspend fun scoreGermanSample(
    providerRegistry: org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerProviderRegistry,
    component: RegisteredLanguagePackageComponent,
): RankerDebugResult {
    return when (val opened = providerRegistry.open(component)) {
        is CandidateRankerOpenOutcome.Failed -> RankerDebugResult.Message(
            text = "${opened.failure.code}: ${opened.failure.message}",
            isError = true,
        )

        is CandidateRankerOpenOutcome.Opened -> {
            opened.runtime.use { runtime ->
                val request = CandidateRankerRequest(
                    requestId = "debug-german-sample",
                    languageTag = "de-DE",
                    purpose = CandidateRankingPurpose.Correction,
                    leftContext = "Das ist",
                    typedText = " warscheinlich",
                    rightContext = " richtig.",
                    candidates = listOf(
                        CandidateRankerCandidate("likely", "wahrscheinlich", " wahrscheinlich"),
                        CandidateRankerCandidate("apparently", "anscheinend", " anscheinend"),
                        CandidateRankerCandidate("really", "wirklich", " wirklich"),
                    ),
                    rightContextTokenLimit = 8,
                )
                when (val outcome = runtime.rank(request)) {
                    is CandidateRankerOutcome.Failure -> RankerDebugResult.Message(
                        text = "${outcome.failure.code}: ${outcome.failure.message}",
                        isError = true,
                    )

                    is CandidateRankerOutcome.Success -> {
                        val policy = CandidateRankerScoringPolicy()
                        val byId = request.candidates.associateBy { it.id }
                        val rows = outcome.scores
                            .sortedByDescending { it.normalizedScore(policy) }
                            .joinToString("\n") { score ->
                                val candidate = byId.getValue(score.candidateId)
                                "${candidate.displayText}: ${"%.4f".format(score.normalizedScore(policy))} " +
                                    "(${score.candidateTokenCount}+${score.rightContextTokenCount} tokens)"
                            }
                        RankerDebugResult.Message(
                            text = buildString {
                                append(rows)
                                append("\n")
                                append("Time: ")
                                append("%.2f".format(outcome.diagnostics.elapsedMicros / 1000.0))
                                append(" ms · native batches: ")
                                append(outcome.diagnostics.batchCount)
                            },
                            isError = false,
                        )
                    }
                }
            }
        }
    }
}

private fun runtimeEnvironment(context: Context): LanguagePackageRuntimeEnvironment {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memory = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memory)
    val ramMb = (memory.totalMem / (1024L * 1024L))
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()

    return LanguagePackageRuntimeEnvironment(
        keyboardApi = 2,
        androidAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        ramMb = ramMb,
        supportedTasks = setOf(
            "dictionary-lookup-v1",
            "language-rules-v1",
            "capitalization-rules-v1",
            "german-compounding-v1",
            "candidate-generation-v1",
            CANDIDATE_RANKING_TASK_V1,
            "tap-correction-v1",
            "swipe-decoding-v1",
            "personalization-v1",
        ),
        supportedCapabilities = setOf(
            "unicode-graphemes",
            "offline-only",
            "word-frequency",
            FULL_CANDIDATE_LOGPROB_CAPABILITY,
        ),
        runtimeFeatures = setOf("gguf-causal-lm-v1"),
    )
}

@Composable
private fun DebugCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = Typography.Heading.RegularMl)
            content()
        }
    }
}

@Composable
private fun DebugStatusCard(
    isError: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Property(name: String, value: String) {
    Column {
        Text(name, style = Typography.SmallMl, color = LocalContentColor.current.copy(alpha = 0.7f))
        Text(value, style = Typography.Body.RegularMl)
    }
}

@Composable
private fun ProgressRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(start = 12.dp))
    }
}
