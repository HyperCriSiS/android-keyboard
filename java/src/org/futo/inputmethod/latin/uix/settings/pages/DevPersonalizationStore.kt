package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.personalization.AndroidPersonalDictionaryInventoryReader
import org.futo.inputmethod.latin.personalization.LegacyManualStoreMigrationApplyResult
import org.futo.inputmethod.latin.personalization.LegacyManualStoreMigrationPlan
import org.futo.inputmethod.latin.personalization.LegacyManualStoreMigrationPlanner
import org.futo.inputmethod.latin.personalization.LegacyManualStoreMigrationService
import org.futo.inputmethod.latin.personalization.PersonalizationStoreGeneration
import org.futo.inputmethod.latin.personalization.PersonalizationStoreReadResult
import org.futo.inputmethod.latin.personalization.PersonalizationStoreSnapshot
import org.futo.inputmethod.latin.uix.settings.ScreenTitle

private sealed interface PersonalizationStoreScreenState {
    data object Loading : PersonalizationStoreScreenState

    data class Loaded(
        val plan: LegacyManualStoreMigrationPlan,
        val currentSnapshot: PersonalizationStoreSnapshot?,
        val history: List<PersonalizationStoreGeneration>,
    ) : PersonalizationStoreScreenState

    data class Failed(val message: String) : PersonalizationStoreScreenState
}

@Composable
fun DevPersonalizationStoreScreen(
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val service = remember(context) {
        LegacyManualStoreMigrationService.forContext(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var state by remember {
        mutableStateOf<PersonalizationStoreScreenState>(PersonalizationStoreScreenState.Loading)
    }

    LaunchedEffect(refreshKey) {
        state = PersonalizationStoreScreenState.Loading
        state = try {
            withContext(Dispatchers.IO) {
                val inventory = AndroidPersonalDictionaryInventoryReader(context).read()
                val read = service.readCurrent()
                val current = when (read) {
                    PersonalizationStoreReadResult.Empty -> null
                    is PersonalizationStoreReadResult.Ready -> read.snapshot
                    is PersonalizationStoreReadResult.Failed -> throw IllegalStateException(
                        read.message,
                        read.cause,
                    )
                }
                val plan = LegacyManualStoreMigrationPlanner.plan(
                    inventory = inventory,
                    currentSnapshot = current,
                    migratedAt = System.currentTimeMillis(),
                )
                PersonalizationStoreScreenState.Loaded(
                    plan = plan,
                    currentSnapshot = current,
                    history = service.history(limit = 100),
                )
            }
        } catch (exception: Exception) {
            PersonalizationStoreScreenState.Failed(
                exception.message ?: exception.javaClass.simpleName,
            )
        }
    }

    LazyColumn {
        item {
            ScreenTitle("Experimental personalization store", showBack = true, navController)
        }
        item {
            Text(
                "This screen migrates Android personal-dictionary words into a separate experimental " +
                    "store. It does not change the production personal dictionary, user history, " +
                    "suggestions, or learning behavior.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !applying,
                    onClick = {
                        operationMessage = null
                        refreshKey++
                    },
                ) {
                    Text("Refresh")
                }

                val loaded = state as? PersonalizationStoreScreenState.Loaded
                Button(
                    enabled = loaded?.plan?.canApply == true && !applying,
                    onClick = {
                        val plan = loaded?.plan ?: return@Button
                        applying = true
                        operationMessage = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                service.apply(plan, committedAt = System.currentTimeMillis())
                            }
                            operationMessage = result.displayMessage()
                            applying = false
                            refreshKey++
                        }
                    },
                ) {
                    Text(if (applying) "Applying…" else "Apply to experimental store")
                }
            }
        }
        operationMessage?.let { message ->
            item {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        when (val current = state) {
            PersonalizationStoreScreenState.Loading -> item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Reading source inventory and validating store generations…")
                }
            }

            is PersonalizationStoreScreenState.Failed -> item {
                Text(
                    "Store inspection failed: ${current.message}",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is PersonalizationStoreScreenState.Loaded -> {
                val plan = current.plan
                item {
                    StoreSection("Migration preview")
                    StoreValue("Android source words", plan.sourceCount.toString())
                    StoreValue("Source truncated", plan.sourceTruncated.toString())
                    StoreValue("New records", plan.additions.toString())
                    StoreValue("Already present", plan.alreadyPresent.toString())
                    StoreValue("Protected by tombstone", plan.suppressedByTombstone.toString())
                    StoreValue("Conflicts", plan.conflicts.size.toString())
                    StoreValue(
                        "Status",
                        when {
                            plan.sourceTruncated -> "Blocked: source inventory is incomplete"
                            plan.conflicts.isNotEmpty() -> "Blocked: conflicts require review"
                            plan.isAlreadyUpToDate -> "Experimental store is already up to date"
                            plan.canApply -> "Ready to apply"
                            else -> "Unavailable"
                        },
                    )
                }

                item {
                    StoreSection("Current experimental generation")
                    val snapshot = current.currentSnapshot
                    if (snapshot == null) {
                        StoreValue("State", "Not initialized")
                    } else {
                        StoreValue("Generation", snapshot.generation.generation.toString())
                        StoreValue("Generation ID", snapshot.generation.generationId)
                        StoreValue("Reason", snapshot.generation.reason.name)
                        StoreValue("Manual words", snapshot.data.manualWords.size.toString())
                        StoreValue("Learned words", snapshot.data.learnedWords.size.toString())
                        StoreValue("Learned n-grams", snapshot.data.learnedNgrams.size.toString())
                        StoreValue("Rules", (snapshot.data.wordRules.size + snapshot.data.correctionRules.size).toString())
                        StoreValue("Data SHA-256", snapshot.generation.dataSha256)
                        StoreValue("Recovery issues", snapshot.recoveryIssues.size.toString())
                    }
                }

                if (current.currentSnapshot?.recoveryIssues?.isNotEmpty() == true) {
                    item { StoreSection("Recovery issues") }
                    items(
                        current.currentSnapshot.recoveryIssues,
                        key = { it.generationDirectory },
                    ) { issue ->
                        StoreListItem(issue.generationDirectory, issue.message, isError = true)
                    }
                }

                if (plan.conflicts.isNotEmpty()) {
                    item { StoreSection("Migration conflicts") }
                    items(plan.conflicts, key = { "${it.stableId}:${it.code}" }) { conflict ->
                        StoreListItem(conflict.code, conflict.message, isError = true)
                    }
                }

                if (plan.migrationIssues.isNotEmpty()) {
                    item { StoreSection("Migration notices") }
                    items(plan.migrationIssues, key = { "${it.stableId}:${it.code}" }) { issue ->
                        StoreListItem(issue.code, issue.message, isError = false)
                    }
                }

                item { StoreSection("Recent generations") }
                if (current.history.isEmpty()) {
                    item {
                        Text(
                            "No experimental generations exist.",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(current.history, key = { it.generationId }) { generation ->
                        GenerationItem(generation)
                    }
                }

                item { StoreSection("Planned records") }
                items(
                    plan.actions.take(250),
                    key = { "${it.stableId}:${it.decision}" },
                ) { action ->
                    StoreListItem(
                        title = "${action.decision}: ${action.word}",
                        body = buildString {
                            append(action.message)
                            action.locale?.let { append(" Locale: $it.") }
                        },
                        isError = action.decision.name == "Conflict",
                    )
                }
                if (plan.actions.size > 250) {
                    item {
                        Text(
                            "Only the first 250 of ${plan.actions.size} planned records are displayed.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreSection(title: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        HorizontalDivider()
        Text(
            title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StoreValue(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StoreListItem(title: String, body: String, isError: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            fontWeight = FontWeight.Medium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun GenerationItem(generation: PersonalizationStoreGeneration) {
    val formattedDate = remember(generation.committedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(generation.committedAt))
    }
    StoreListItem(
        title = "Generation ${generation.generation} · ${generation.reason}",
        body = buildString {
            append(formattedDate)
            append(" · ")
            append(generation.generationId)
            append(" · ")
            append(generation.changes.size)
            append(" journal changes")
        },
        isError = false,
    )
}

private fun LegacyManualStoreMigrationApplyResult.displayMessage(): String = when (this) {
    is LegacyManualStoreMigrationApplyResult.Applied -> {
        "Experimental generation ${storeSnapshot.generation.generation} was committed and compiled. " +
            "Production typing remains unchanged."
    }
    is LegacyManualStoreMigrationApplyResult.Unchanged -> {
        "The experimental store was already up to date at generation ${storeSnapshot.generation.generation}."
    }
    is LegacyManualStoreMigrationApplyResult.Rejected -> "Migration rejected: $message"
    is LegacyManualStoreMigrationApplyResult.Conflict -> "Migration conflict: $message"
    is LegacyManualStoreMigrationApplyResult.Failed -> "Migration failed: $message"
}
