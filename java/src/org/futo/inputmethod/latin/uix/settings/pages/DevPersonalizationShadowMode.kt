package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.futo.inputmethod.latin.personalization.PersonalizationShadowEvent
import org.futo.inputmethod.latin.personalization.PersonalizationShadowFinding
import org.futo.inputmethod.latin.personalization.PersonalizationShadowMode
import org.futo.inputmethod.latin.personalization.PersonalizationShadowSnapshot
import org.futo.inputmethod.latin.uix.settings.ScreenTitle

@Composable
fun DevPersonalizationShadowModeScreen(
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(PersonalizationShadowMode.snapshot()) }

    LaunchedEffect(Unit) {
        PersonalizationShadowMode.configure(context.applicationContext)
        while (isActive) {
            snapshot = PersonalizationShadowMode.snapshot()
            delay(750)
        }
    }

    LazyColumn {
        item {
            ScreenTitle("Personalization shadow mode", showBack = true, navController)
        }
        item {
            Text(
                "Shadow mode observes normal typing suggestions without changing their order, " +
                    "autocorrection, or learning. Events remain in memory and contain salted " +
                    "fingerprints instead of words or surrounding text.",
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
                    onClick = {
                        PersonalizationShadowMode.setEnabled(!snapshot.enabled)
                        snapshot = PersonalizationShadowMode.snapshot()
                    },
                ) {
                    Text(if (snapshot.enabled) "Disable" else "Enable")
                }
                Button(
                    onClick = {
                        PersonalizationShadowMode.refreshAsync()
                        snapshot = PersonalizationShadowMode.snapshot()
                    },
                ) {
                    Text("Reload runtime")
                }
                Button(
                    onClick = {
                        PersonalizationShadowMode.clearEvents()
                        snapshot = PersonalizationShadowMode.snapshot()
                    },
                ) {
                    Text("Clear")
                }
            }
        }

        item {
            ShadowSection("Status")
            ShadowValue("Enabled", snapshot.enabled.toString())
            ShadowValue("Runtime generation", snapshot.runtimeGenerationId ?: "Not loaded")
            ShadowValue("Runtime data SHA-256", snapshot.runtimeDataSha256 ?: "Not loaded")
            ShadowValue(
                "Last runtime refresh",
                snapshot.lastRefreshAt?.let(::formatShadowTime) ?: "Not refreshed",
            )
            ShadowValue("Observed events", snapshot.observed.toString())
            ShadowValue("Dropped queue items", snapshot.dropped.toString())
            ShadowValue("Skipped without runtime", snapshot.skippedWithoutRuntime.toString())
            snapshot.lastError?.let {
                ShadowValue("Last error", it, isError = true)
            }
        }

        item { ShadowSection("Findings") }
        PersonalizationShadowFinding.entries.forEach { finding ->
            item {
                ShadowValue(
                    finding.name,
                    (snapshot.findings[finding] ?: 0L).toString(),
                    isError = (snapshot.findings[finding] ?: 0L) > 0,
                )
            }
        }

        item { ShadowSection("Recent fingerprinted events") }
        if (snapshot.recentEvents.isEmpty()) {
            item {
                Text(
                    if (snapshot.enabled) {
                        "No typing observations have been evaluated yet."
                    } else {
                        "Enable shadow mode, return to the keyboard, and type normally."
                    },
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            itemsIndexed(snapshot.recentEvents) { index, event ->
                ShadowEventItem(index, event)
            }
        }
    }
}

@Composable
private fun ShadowEventItem(index: Int, event: PersonalizationShadowEvent) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "#${index + 1} · ${formatShadowTime(event.observedAt)} · ${event.locale}",
            fontWeight = FontWeight.Medium,
        )
        Text(
            "typed=${event.typedWord.hash}/${event.typedWord.codePointLength}cp · " +
                "top=${event.productionTopCandidate?.hash ?: "none"}/" +
                "${event.productionTopSourceType ?: "none"} · candidates=${event.candidateCount}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "manual=${event.manualPrefixCandidatesVisible}/${event.manualPrefixCandidateCount} · " +
                "learned=${event.learnedTypedWordPresent} · historyVisible=" +
                "${event.productionHistoryTypedWordVisible} · eval=${event.evaluationMicros}µs",
            style = MaterialTheme.typography.bodySmall,
        )
        if (event.findings.isNotEmpty()) {
            Text(
                event.findings.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ShadowSection(title: String) {
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
private fun ShadowValue(label: String, value: String, isError: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatShadowTime(timestamp: Long): String = DateFormat
    .getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    .format(Date(timestamp))
