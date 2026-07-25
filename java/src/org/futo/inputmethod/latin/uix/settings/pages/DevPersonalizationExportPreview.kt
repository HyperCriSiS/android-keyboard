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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.personalization.AndroidPersonalDictionaryInventoryReader
import org.futo.inputmethod.latin.personalization.LegacyManualExportPreview
import org.futo.inputmethod.latin.personalization.LegacyPersonalizationExportPreview
import org.futo.inputmethod.latin.personalization.PersonalizationExportApplication
import org.futo.inputmethod.latin.uix.settings.ScreenTitle

private sealed interface PersonalizationExportPreviewState {
    data object Loading : PersonalizationExportPreviewState
    data class Loaded(val preview: LegacyManualExportPreview) : PersonalizationExportPreviewState
    data class Failed(val message: String) : PersonalizationExportPreviewState
}

@Composable
fun DevPersonalizationExportPreviewScreen(
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    var generation by remember { mutableIntStateOf(0) }
    var state by remember {
        mutableStateOf<PersonalizationExportPreviewState>(
            PersonalizationExportPreviewState.Loading,
        )
    }

    LaunchedEffect(generation) {
        state = PersonalizationExportPreviewState.Loading
        state = try {
            PersonalizationExportPreviewState.Loaded(
                withContext(Dispatchers.IO) {
                    val inventory = AndroidPersonalDictionaryInventoryReader(context).read()
                    LegacyPersonalizationExportPreview.prepareManualWords(
                        inventory = inventory,
                        application = PersonalizationExportApplication(
                            id = BuildConfig.APPLICATION_ID,
                            versionName = BuildConfig.VERSION_NAME,
                            versionCode = BuildConfig.VERSION_CODE.toLong(),
                        ),
                        createdAt = System.currentTimeMillis(),
                    )
                },
            )
        } catch (exception: Exception) {
            PersonalizationExportPreviewState.Failed(
                exception.message ?: exception.javaClass.simpleName,
            )
        }
    }

    LazyColumn {
        item {
            ScreenTitle("Personalization export preview", showBack = true, navController)
        }
        item {
            Text(
                "This screen creates and validates a .futopersonal archive only in memory. " +
                    "It does not write a file or change personal data.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Text(
                "Only Android personal-dictionary words are included. Automatic user history is " +
                    "deliberately excluded until its legacy evidence has a validated migration mapping.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        item {
            Button(
                onClick = { generation++ },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Rebuild preview")
            }
        }

        when (val current = state) {
            PersonalizationExportPreviewState.Loading -> item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Building and validating preview…")
                }
            }

            is PersonalizationExportPreviewState.Failed -> item {
                Text(
                    "Preview failed: ${current.message}",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is PersonalizationExportPreviewState.Loaded -> {
                val preview = current.preview
                val manifest = preview.preparedArchive.manifest
                item {
                    PreviewSection("Result")
                    PreviewValue(
                        "Status",
                        when {
                            !preview.isValid -> "Invalid"
                            preview.sourceTruncated -> "Valid, but source inventory was truncated"
                            else -> "Valid and complete"
                        },
                    )
                    PreviewValue("Archive bytes", preview.archiveBytes.size.toString())
                    PreviewValue("Data bytes", manifest.data.sizeBytes.toString())
                    PreviewValue("Data SHA-256", manifest.data.sha256)
                    PreviewValue("Export ID", manifest.exportId)
                }
                item {
                    PreviewSection("Contents")
                    PreviewValue("Manual words", manifest.counts.manualWords.toString())
                    PreviewValue(
                        "Locales",
                        manifest.locales.joinToString().ifBlank { "Only locale-independent entries" },
                    )
                    PreviewValue(
                        "Categories",
                        manifest.includedCategories.joinToString { it.name },
                    )
                    PreviewValue("Contains n-grams", manifest.privacy.containsNgrams.toString())
                    PreviewValue("Contains app scopes", manifest.privacy.containsAppScopes.toString())
                    PreviewValue("Encrypted", manifest.privacy.encrypted.toString())
                }
                item {
                    PreviewSection("Archive inspection")
                    PreviewValue(
                        "Entries",
                        preview.inspection.entries.keys.joinToString(),
                    )
                    PreviewValue(
                        "Errors",
                        preview.inspection.issues.count {
                            it.severity.name == "Error"
                        }.toString(),
                    )
                    PreviewValue(
                        "Warnings",
                        preview.inspection.issues.count {
                            it.severity.name == "Warning"
                        }.toString(),
                    )
                }
                if (preview.migrationIssues.isNotEmpty()) {
                    item { PreviewSection("Migration notices") }
                    items(
                        preview.migrationIssues,
                        key = { "${it.stableId}:${it.code}" },
                    ) { issue ->
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(issue.code, fontWeight = FontWeight.Medium)
                            Text(
                                issue.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSection(title: String) {
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
private fun PreviewValue(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
