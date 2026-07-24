package org.futo.inputmethod.latin.uix.settings.pages

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.languagepack.LanguagePackageArchiveInspector
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponent
import org.futo.inputmethod.latin.languagepack.LanguagePackageInspectionResult
import org.futo.inputmethod.latin.languagepack.LanguagePackageValidationIssue
import org.futo.inputmethod.latin.languagepack.LanguagePackageValidationSeverity
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.theme.Typography

private data class InspectedLanguagePackage(
    val displayName: String,
    val result: LanguagePackageInspectionResult,
)

@Composable
fun DevLanguagePackageInspectorScreen(
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inspectedPackage by remember { mutableStateOf<InspectedLanguagePackage?>(null) }
    var isInspecting by remember { mutableStateOf(false) }
    var readFailure by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult

            inspectedPackage = null
            readFailure = null
            isInspecting = true

            scope.launch {
                try {
                    inspectedPackage = withContext(Dispatchers.IO) {
                        val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "Selected package"
                        val result = context.contentResolver.openInputStream(uri)?.use { input ->
                            LanguagePackageArchiveInspector.inspect(input)
                        } ?: throw IllegalStateException("The selected document could not be opened.")

                        InspectedLanguagePackage(displayName, result)
                    }
                } catch (exception: Exception) {
                    readFailure = exception.message ?: exception.javaClass.simpleName
                } finally {
                    isInspecting = false
                }
            }
        },
    )

    ScrollableList(spacing = 8.dp) {
        ScreenTitle("Language package inspector", showBack = true, navController)

        Tip(
            "Debug-only preview. Packages are read and validated in memory. " +
                "Nothing is installed, extracted, or activated.",
        )

        Button(
            onClick = {
                picker.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/x-zip",
                        "application/octet-stream",
                    ),
                )
            },
            enabled = !isInspecting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(if (inspectedPackage == null) "Select .futolanguage package" else "Select another package")
        }

        if (isInspecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(
                    "Inspecting package…",
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }

        readFailure?.let { message ->
            StatusSurface(isError = true) {
                Text("Could not read package", style = Typography.Heading.RegularMl)
                Text(message, style = Typography.Body.RegularMl)
            }
        }

        inspectedPackage?.let { inspected ->
            InspectionResult(inspected)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InspectionResult(inspected: InspectedLanguagePackage) {
    val result = inspected.result
    val manifest = result.manifest
    val errors = result.issues.filter { it.severity == LanguagePackageValidationSeverity.Error }
    val warnings = result.issues.filter { it.severity == LanguagePackageValidationSeverity.Warning }

    StatusSurface(isError = !result.isValid) {
        Text(
            if (result.isValid) "Package is valid" else "Package is not valid",
            style = Typography.Heading.RegularMl,
        )
        Text(inspected.displayName, style = Typography.Body.RegularMl)
        Text(
            "${result.entries.size} files · ${humanReadableByteCount(result.entries.values.sumOf { it.sizeBytes })}",
            style = Typography.SmallMl,
        )
        Text(
            "${errors.size} errors · ${warnings.size} warnings",
            style = Typography.SmallMl,
        )
    }

    if (manifest != null) {
        SectionSurface("Package") {
            PropertyRow("Name", manifest.packageInfo.name)
            PropertyRow("ID", manifest.packageInfo.id)
            PropertyRow("Version", manifest.packageInfo.version)
            PropertyRow("Kind", manifest.packageInfo.kind.name)
            PropertyRow("Languages", manifest.packageInfo.languages.joinToString())
            PropertyRow("License", manifest.packageInfo.license)
            PropertyRow("Components", manifest.components.size.toString())
            PropertyRow("Profiles", manifest.profiles.size.toString())
            manifest.defaultProfile?.let { PropertyRow("Default profile", it) }
        }

        Text(
            "Components",
            style = Typography.Heading.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (manifest.components.isEmpty()) {
            Text(
                "This package does not contain local components.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = Typography.Body.RegularMl,
            )
        } else {
            manifest.components.forEach(::ComponentSurface)
        }
    }

    if (result.issues.isNotEmpty()) {
        Text(
            "Validation results",
            style = Typography.Heading.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        result.issues
            .sortedBy { if (it.severity == LanguagePackageValidationSeverity.Error) 0 else 1 }
            .forEach(::IssueSurface)
    }
}

@Composable
private fun ComponentSurface(component: LanguagePackageComponent) {
    SectionSurface(component.name) {
        PropertyRow("ID", component.id)
        PropertyRow("Kind", component.kind.name)
        PropertyRow("Activation", component.activation.name)
        PropertyRow("Version", component.version)
        PropertyRow("Languages", component.languages.joinToString())
        if (component.layouts.isNotEmpty()) {
            PropertyRow("Layouts", component.layouts.joinToString())
        }
        PropertyRow("Tasks", component.tasks.joinToString())
        PropertyRow("Payload", component.payload.path)
        PropertyRow("Size", humanReadableByteCount(component.payload.sizeBytes))
    }
}

@Composable
private fun IssueSurface(issue: LanguagePackageValidationIssue) {
    StatusSurface(isError = issue.severity == LanguagePackageValidationSeverity.Error) {
        Text(
            "${issue.severity.name}: ${issue.code}",
            style = Typography.Heading.RegularMl,
        )
        Text(issue.path, style = Typography.SmallMl)
        Text(issue.message, style = Typography.Body.RegularMl)
    }
}

@Composable
private fun SectionSurface(
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = Typography.Heading.RegularMl)
            content()
        }
    }
}

@Composable
private fun StatusSurface(
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
private fun PropertyRow(name: String, value: String) {
    Column {
        Text(name, style = Typography.SmallMl, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = Typography.Body.RegularMl)
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
    }
}

private fun humanReadableByteCount(bytes: Long): String {
    if (-1000 < bytes && bytes < 1000) return "$bytes B"

    var value = bytes
    val units: CharacterIterator = StringCharacterIterator("kMGTPE")
    while (value <= -999950 || value >= 999950) {
        value /= 1000
        units.next()
    }
    return String.format("%.1f %cB", value / 1000.0, units.current())
}

@Preview(showBackground = true)
@Composable
private fun DevLanguagePackageInspectorScreenPreview() {
    DevLanguagePackageInspectorScreen()
}
