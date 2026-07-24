package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.Subtypes
import org.futo.inputmethod.latin.SubtypesSetting
import org.futo.inputmethod.latin.localeFromString
import org.futo.inputmethod.latin.personalization.AndroidPersonalDictionaryInventoryReader
import org.futo.inputmethod.latin.personalization.LegacyManualWordInventoryItem
import org.futo.inputmethod.latin.personalization.LegacyPersonalDictionaryInventory
import org.futo.inputmethod.latin.personalization.LegacyUserHistoryInventory
import org.futo.inputmethod.latin.personalization.LegacyUserHistoryInventoryService
import org.futo.inputmethod.latin.personalization.LegacyUserHistoryWordInventoryItem
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue

private const val INVENTORY_VISIBLE_LIMIT = 250

private sealed interface PersonalizationInventoryLoadState<out T> {
    data object Loading : PersonalizationInventoryLoadState<Nothing>
    data class Loaded<T>(val value: T) : PersonalizationInventoryLoadState<T>
    data class Failed(val message: String) : PersonalizationInventoryLoadState<Nothing>
}

@Composable
fun DevPersonalizationInventoryScreen(
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val historyService = remember(context) { LegacyUserHistoryInventoryService(context) }
    val subtypeValues = useDataStoreValue(SubtypesSetting).orEmpty()
    val enabledLocales = remember(subtypeValues) {
        subtypeValues.map { value ->
            Subtypes.getLocale(Subtypes.convertToSubtype(value)).toLanguageTag()
        }.filter { it.isNotBlank() }.distinct().sorted()
    }

    var refreshGeneration by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var manualState by remember {
        mutableStateOf<PersonalizationInventoryLoadState<LegacyPersonalDictionaryInventory>>(
            PersonalizationInventoryLoadState.Loading,
        )
    }

    LaunchedEffect(refreshGeneration) {
        manualState = PersonalizationInventoryLoadState.Loading
        manualState = try {
            PersonalizationInventoryLoadState.Loaded(
                withContext(Dispatchers.IO) {
                    AndroidPersonalDictionaryInventoryReader(context).read()
                },
            )
        } catch (exception: Exception) {
            PersonalizationInventoryLoadState.Failed(
                exception.message ?: exception.javaClass.simpleName,
            )
        }
    }

    val manualLocales = (manualState as? PersonalizationInventoryLoadState.Loaded)
        ?.value
        ?.words
        ?.mapNotNull { it.locale }
        ?.map { localeFromString(it).toLanguageTag() }
        .orEmpty()
    val availableLocales = remember(enabledLocales, manualLocales) {
        (enabledLocales + manualLocales + Locale.getDefault().toLanguageTag())
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
    var selectedLocale by rememberSaveable {
        mutableStateOf(enabledLocales.firstOrNull() ?: Locale.getDefault().toLanguageTag())
    }
    LaunchedEffect(availableLocales) {
        if (selectedLocale !in availableLocales && availableLocales.isNotEmpty()) {
            selectedLocale = availableLocales.first()
        }
    }

    var historyState by remember(selectedLocale) {
        mutableStateOf<PersonalizationInventoryLoadState<LegacyUserHistoryInventory>>(
            PersonalizationInventoryLoadState.Loading,
        )
    }
    LaunchedEffect(selectedLocale, refreshGeneration) {
        historyState = PersonalizationInventoryLoadState.Loading
        historyState = try {
            val result = suspendCancellableCoroutine { continuation ->
                val cancellation = AtomicBoolean(false)
                continuation.invokeOnCancellation { cancellation.set(true) }
                historyService.readAsync(
                    locale = Locale.forLanguageTag(selectedLocale),
                    cancellation = cancellation,
                ) { inventoryResult ->
                    if (continuation.isActive) continuation.resume(inventoryResult)
                }
            }
            val inventory = result.inventory
            if (inventory != null) {
                PersonalizationInventoryLoadState.Loaded(inventory)
            } else {
                PersonalizationInventoryLoadState.Failed(
                    result.message ?: "History snapshot failed with ${result.status}.",
                )
            }
        } catch (exception: Exception) {
            PersonalizationInventoryLoadState.Failed(
                exception.message ?: exception.javaClass.simpleName,
            )
        }
    }

    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val manualWords = (manualState as? PersonalizationInventoryLoadState.Loaded)
        ?.value
        ?.words
        ?.filter { item ->
            val localeMatches = item.locale == null ||
                localeFromString(item.locale).toLanguageTag().equals(selectedLocale, true)
            val queryMatches = normalizedQuery.isEmpty() ||
                item.word.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                item.shortcut?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true
            localeMatches && queryMatches
        }
        .orEmpty()
    val historyWords = (historyState as? PersonalizationInventoryLoadState.Loaded)
        ?.value
        ?.words
        ?.filter { item ->
            normalizedQuery.isEmpty() || item.word.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                item.ngrams.any { ngram ->
                    ngram.targetWord.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                        ngram.contextTerms.any {
                            it.lowercase(Locale.ROOT).contains(normalizedQuery)
                        }
                }
        }
        .orEmpty()

    LazyColumn {
        item {
            ScreenTitle("Personalization inventory", showBack = true, navController)
        }
        item {
            Text(
                "Read-only developer view. No word, rule, or history entry is modified by this screen.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { refreshGeneration++ }) {
                    Text("Refresh")
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Search words and n-grams") },
                singleLine = true,
            )
        }
        item {
            Text(
                "Locale",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(availableLocales, key = { it }) { localeTag ->
                    val locale = Locale.forLanguageTag(localeTag)
                    FilterChip(
                        selected = selectedLocale == localeTag,
                        onClick = { selectedLocale = localeTag },
                        label = {
                            Text("${locale.getDisplayName(locale)} · $localeTag")
                        },
                    )
                }
            }
        }

        item { InventorySectionHeader("Manual personal words") }
        when (val state = manualState) {
            PersonalizationInventoryLoadState.Loading -> item { InventoryLoading() }
            is PersonalizationInventoryLoadState.Failed -> item {
                InventoryError(state.message)
            }
            is PersonalizationInventoryLoadState.Loaded -> {
                item {
                    InventorySummary(
                        "${manualWords.size} matching of ${state.value.words.size} total" +
                            if (state.value.truncated) " · source truncated" else "",
                    )
                }
                if (manualWords.isEmpty()) {
                    item { InventoryEmpty("No matching manual words.") }
                } else {
                    items(
                        manualWords.take(INVENTORY_VISIBLE_LIMIT),
                        key = { "manual:${it.stableId}" },
                    ) { item ->
                        ManualWordRow(item)
                    }
                    if (manualWords.size > INVENTORY_VISIBLE_LIMIT) {
                        item {
                            InventorySummary(
                                "Showing the first $INVENTORY_VISIBLE_LIMIT matches. Refine the search.",
                            )
                        }
                    }
                }
            }
        }

        item { InventorySectionHeader("Automatic user history") }
        when (val state = historyState) {
            PersonalizationInventoryLoadState.Loading -> item { InventoryLoading() }
            is PersonalizationInventoryLoadState.Failed -> item {
                InventoryError(state.message)
            }
            is PersonalizationInventoryLoadState.Loaded -> {
                item {
                    InventorySummary(
                        "${historyWords.size} matching of ${state.value.words.size} words" +
                            if (state.value.truncated) " · snapshot truncated" else "",
                    )
                }
                item {
                    Text(
                        state.value.rawSemanticsWarning,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (historyWords.isEmpty()) {
                    item { InventoryEmpty("No matching learned words.") }
                } else {
                    items(
                        historyWords.take(INVENTORY_VISIBLE_LIMIT),
                        key = { "history:${it.stableId}" },
                    ) { item ->
                        HistoryWordRow(item)
                    }
                    if (historyWords.size > INVENTORY_VISIBLE_LIMIT) {
                        item {
                            InventorySummary(
                                "Showing the first $INVENTORY_VISIBLE_LIMIT matches. Refine the search.",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InventorySectionHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
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
private fun InventoryLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text("Loading inventory…")
    }
}

@Composable
private fun InventoryError(message: String) {
    Text(
        "Inventory error: $message",
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun InventoryEmpty(message: String) {
    Text(
        message,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun InventorySummary(message: String) {
    Text(
        message,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ManualWordRow(item: LegacyManualWordInventoryItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(item.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            buildString {
                append(item.locale ?: "all locales")
                append(" · frequency ")
                append(item.frequency)
                item.shortcut?.let {
                    append(" · shortcut ")
                    append(it)
                }
                if (item.appId != 0) {
                    append(" · legacy app ID ")
                    append(item.appId)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun HistoryWordRow(item: LegacyUserHistoryWordInventoryItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(item.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            buildString {
                append("probability ")
                append(item.evidence.probability)
                item.evidence.countRaw?.let {
                    append(" · raw count ")
                    append(it)
                }
                item.evidence.levelRaw?.let {
                    append(" · raw level ")
                    append(it)
                }
                item.evidence.timestampRaw?.let {
                    append(" · raw timestamp ")
                    append(it)
                }
                append(" · n-grams ")
                append(item.ngrams.size)
                if (item.isNotAWord) append(" · not-a-word")
                if (item.isPossiblyOffensive) append(" · possibly-offensive")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        item.ngrams.take(5).forEach { ngram ->
            Text(
                "${ngram.contextTerms.joinToString(" ")} → ${ngram.targetWord}" +
                    " · probability ${ngram.evidence.probability}",
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (item.ngrams.size > 5) {
            Text(
                "+ ${item.ngrams.size - 5} more n-grams",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}
