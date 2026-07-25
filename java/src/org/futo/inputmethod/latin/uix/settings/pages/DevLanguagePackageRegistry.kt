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
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerProviderRegistry
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerRequest
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerScoringPolicy
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankingPurpose
import org.futo.inputmethod.latin.languagepack.ranker.FULL_CANDIDATE_LOGPROB_CAPABILITY
import org.futo.inputmethod.latin.languagepack.ranker.createDefaultCandidateRankerProviderRegistry
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.theme.Typography

private data class RegistrySnapshot(
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
    val providers = remember { createDefaultCandidateRankerProviderRegistry() }
    val scope = rememberCoroutineScope()
    val rankerResults = remember {
        mutableStateMapOf<LanguagePackageComponentCoordinate, RankerDebugResult>()
    }
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<RegistrySnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(revision) {
        loading = true
        loadError = null
        snapshot = try {
            withContext(Dispatchers.IO) {
                val registry = store.buildRegistry()
                val environment = runtimeEnvironment(context)
                RegistrySnapshot(
                    registry = registry,
                    environment = environment,
                    plan = LanguagePackageResolver(registry).resolve(
                        LanguagePackageResolutionRequest(
                            target = LanguagePackageTarget("de-DE", "qwertz"),
                            environment = environment,
                        ),
                    ),
                )
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
            "Debug-only inactive de-DE/QWERTZ plan. Probe and score operations load a ranker " +
                "temporarily but never activate it.",
        )
        Button(
            onClick = {
                rankerResults.clear()
                revision += 1
            },
            enabled = !loading && rankerResults.values.none { it is RankerDebugResult.Loading },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("Refresh installed packages")
        }

        if (loading) DebugProgress("Building registry…")
        loadError?.let { DebugStatus(true, "Registry failed", it) }

        snapshot?.let { current ->
            DebugCard("Registry") {
                Property("Packages", current.registry.installedPackages.size.toString())
                Property("Components", current.registry.components.size.toString())
                Property("Profiles", current.registry.profiles.size.toString())
                Property("Target", "de-DE · qwertz")
                Property("ABI", current.environment.androidAbi)
                Property("Detected RAM", "${current.environment.ramMb} MB")
            }
            PlanView(current.plan)

            Text(
                "Installed components",
                style = Typography.Heading.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (current.registry.components.isEmpty()) {
                DebugCard("No components") {
                    Text("Install a .futolanguage package through the package inspector first.")
                }
            }

            current.registry.components.forEach { component ->
                val evaluation = current.plan.evaluations[component.coordinate]
                val result = rankerResults[component.coordinate]
                val busy = result is RankerDebugResult.Loading
                ComponentView(
                    component = component,
                    compatible = evaluation?.isCompatible == true,
                    issues = evaluation?.issues.orEmpty().map {
                        "${it.severity.name}: ${it.code} — ${it.message}"
                    },
                    result = result,
                    onProbe = if (component.isBoundRanker() && !busy) {
                        {
                            rankerResults[component.coordinate] = RankerDebugResult.Loading
                            scope.launch {
                                rankerResults[component.coordinate] = probeRanker(providers, component)
                            }
                        }
                    } else {
                        null
                    },
                    onScore = if (
                        component.isBoundRanker() && evaluation?.isCompatible == true && !busy
                    ) {
                        {
                            rankerResults[component.coordinate] = RankerDebugResult.Loading
                            scope.launch {
                                rankerResults[component.coordinate] = scoreGermanSample(providers, component)
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlanView(plan: LanguagePackageResolutionPlan) {
    DebugStatus(
        isError = !plan.isValid,
        title = if (plan.isValid) "Automatic plan is valid" else "Automatic plan is invalid",
        body = "${plan.selectedComponents.size} selected components · ${plan.issues.size} plan issues",
    )
    DebugCard("Selection plan") {
        plan.slots.values.forEach { slot ->
            Property(
                slot.kind.name,
                when {
                    slot.explicitlyDisabled -> "Disabled"
                    slot.selected.isEmpty() -> "Built-in fallback / none"
                    else -> slot.selected.joinToString {
                        "${it.component.component.name} [${it.source.name}]"
                    }
                },
            )
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
private fun ComponentView(
    component: RegisteredLanguagePackageComponent,
    compatible: Boolean,
    issues: List<String>,
    result: RankerDebugResult?,
    onProbe: (() -> Unit)?,
    onScore: (() -> Unit)?,
) {
    DebugCard(component.component.name) {
        Property("Coordinate", component.coordinate.toString())
        Property("Kind", component.component.kind.name)
        Property("Compatibility", if (compatible) "Compatible" else "Unavailable")
        Property("Tasks", component.component.tasks.joinToString())
        Property(
            "Runtime",
            component.component.runtime?.let { "${it.id} API ${it.apiVersion}" } ?: "None",
        )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onProbe?.let { Button(onClick = it) { Text("Probe") } }
                onScore?.let { Button(onClick = it) { Text("Score sample") } }
            }
        }
        when (result) {
            RankerDebugResult.Loading -> DebugProgress("Running model operation…")
            is RankerDebugResult.Message -> DebugStatus(result.isError, "Ranker result", result.text)
            null -> Unit
        }
    }
}

private fun RegisteredLanguagePackageComponent.isBoundRanker(): Boolean {
    return component.kind == LanguagePackageComponentKind.ContextRanker && component.runtime != null
}

private suspend fun probeRanker(
    providers: CandidateRankerProviderRegistry,
    component: RegisteredLanguagePackageComponent,
): RankerDebugResult {
    return when (val result = providers.probe(component)) {
        is CandidateRankerProbeOutcome.Ready -> RankerDebugResult.Message(
            buildString {
                append("Ready: ${result.descriptor.modelName}")
                append(" · context ${result.descriptor.maxContextTokens}")
                append(" · batch ${result.descriptor.maxBatchSize}")
                append(" · boundary ${result.descriptor.boundaryMode.name}")
                if (result.issues.isNotEmpty()) {
                    append("\n")
                    append(result.issues.joinToString("\n") { "${it.code}: ${it.message}" })
                }
            },
            false,
        )
        is CandidateRankerProbeOutcome.Unavailable -> RankerDebugResult.Message(
            result.issues.joinToString("\n") { "${it.code}: ${it.message}" },
            true,
        )
    }
}

private suspend fun scoreGermanSample(
    providers: CandidateRankerProviderRegistry,
    component: RegisteredLanguagePackageComponent,
): RankerDebugResult {
    return when (val opened = providers.open(component)) {
        is CandidateRankerOpenOutcome.Failed -> RankerDebugResult.Message(
            "${opened.failure.code}: ${opened.failure.message}",
            true,
        )
        is CandidateRankerOpenOutcome.Opened -> opened.runtime.use { runtime ->
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
                    "${outcome.failure.code}: ${outcome.failure.message}",
                    true,
                )
                is CandidateRankerOutcome.Success -> {
                    val policy = CandidateRankerScoringPolicy()
                    val candidates = request.candidates.associateBy { it.id }
                    val rows = outcome.scores
                        .sortedByDescending { it.normalizedScore(policy) }
                        .joinToString("\n") { score ->
                            val candidate = candidates.getValue(score.candidateId)
                            "${candidate.displayText}: ${"%.4f".format(score.normalizedScore(policy))} " +
                                "(${score.candidateTokenCount}+${score.rightContextTokenCount} tokens)"
                        }
                    RankerDebugResult.Message(
                        rows + "\nTime: " +
                            "%.2f".format(outcome.diagnostics.elapsedMicros / 1000.0) +
                            " ms · native batches: ${outcome.diagnostics.batchCount}",
                        false,
                    )
                }
            }
        }
    }
}

private fun runtimeEnvironment(context: Context): LanguagePackageRuntimeEnvironment {
    val memory = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
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
private fun DebugCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
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
private fun DebugStatus(isError: Boolean, title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = Typography.Heading.RegularMl)
            Text(body, style = Typography.Body.RegularMl)
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
private fun DebugProgress(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(start = 12.dp))
    }
}
