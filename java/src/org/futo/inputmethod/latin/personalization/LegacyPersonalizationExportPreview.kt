package org.futo.inputmethod.latin.personalization

data class LegacyManualExportPreview(
    val preparedArchive: PreparedPersonalizationArchive,
    val archiveBytes: ByteArray,
    val inspection: PersonalizationArchiveInspection,
    val migrationIssues: List<LegacyManualWordMigrationIssue>,
    val sourceTruncated: Boolean,
) {
    val isValid: Boolean
        get() = inspection.isValid

    val isComplete: Boolean
        get() = isValid && !sourceTruncated
}

object LegacyPersonalizationExportPreview {
    fun prepareManualWords(
        inventory: LegacyPersonalDictionaryInventory,
        application: PersonalizationExportApplication,
        createdAt: Long,
        originDeviceId: String? = null,
        exportIdFactory: PersonalizationExportIdFactory = RandomPersonalizationExportIdFactory,
    ): LegacyManualExportPreview {
        require(createdAt >= 0L) { "createdAt must not be negative." }

        val migration = LegacyManualWordMigration.convert(
            inventory = inventory,
            migratedAt = createdAt,
            originDeviceId = originDeviceId,
        )
        val data = PersonalizationDataSet(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            manualWords = migration.records,
        )
        val prepared = PersonalizationArchiveBuilder.prepare(
            source = data,
            options = PersonalizationExportOptions(
                application = application,
                createdAt = createdAt,
                originDeviceId = originDeviceId,
                includedCategories = setOf(PersonalizationCategory.ManualWords),
                encrypted = false,
                exportIdFactory = exportIdFactory,
            ),
        )
        val archiveBytes = prepared.toByteArray()
        val inspection = PersonalizationArchiveInspector.inspect(archiveBytes)

        check(inspection.isValid) {
            "Generated manual-word export failed its own archive inspection: " +
                inspection.issues.joinToString { "${it.path}: ${it.message}" }
        }
        return LegacyManualExportPreview(
            preparedArchive = prepared,
            archiveBytes = archiveBytes,
            inspection = inspection,
            migrationIssues = migration.issues,
            sourceTruncated = inventory.truncated,
        )
    }
}
