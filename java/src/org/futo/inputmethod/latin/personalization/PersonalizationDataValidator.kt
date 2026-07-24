package org.futo.inputmethod.latin.personalization

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class PersonalizationValidationSeverity {
    Error,
    Warning,
}

data class PersonalizationValidationIssue(
    val severity: PersonalizationValidationSeverity,
    val code: String,
    val path: String,
    val message: String,
)

data class PersonalizationValidationResult(
    val issues: List<PersonalizationValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.none { it.severity == PersonalizationValidationSeverity.Error }

    val errors: List<PersonalizationValidationIssue>
        get() = issues.filter { it.severity == PersonalizationValidationSeverity.Error }

    val warnings: List<PersonalizationValidationIssue>
        get() = issues.filter { it.severity == PersonalizationValidationSeverity.Warning }
}

object PersonalizationDataValidator {
    private val uuidRegex = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-" +
            "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
    )
    private val languageTagRegex = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
    private val packageNameRegex = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val sha256Regex = Regex("^[A-Fa-f0-9]{64}$")

    fun validate(
        manifest: PersonalizationExportManifest,
        data: PersonalizationDataSet,
        rawDataBytes: ByteArray? = null,
    ): PersonalizationValidationResult {
        val issues = mutableListOf<PersonalizationValidationIssue>()

        fun error(code: String, path: String, message: String) {
            issues += PersonalizationValidationIssue(
                PersonalizationValidationSeverity.Error,
                code,
                path,
                message,
            )
        }

        fun warning(code: String, path: String, message: String) {
            issues += PersonalizationValidationIssue(
                PersonalizationValidationSeverity.Warning,
                code,
                path,
                message,
            )
        }

        validateManifest(manifest, data, rawDataBytes, ::error, ::warning)
        validateData(data, ::error, ::warning)
        validateCrossRecordSemantics(data, ::error, ::warning)

        return PersonalizationValidationResult(issues)
    }

    fun validateData(data: PersonalizationDataSet): PersonalizationValidationResult {
        val issues = mutableListOf<PersonalizationValidationIssue>()
        fun error(code: String, path: String, message: String) {
            issues += PersonalizationValidationIssue(
                PersonalizationValidationSeverity.Error,
                code,
                path,
                message,
            )
        }
        fun warning(code: String, path: String, message: String) {
            issues += PersonalizationValidationIssue(
                PersonalizationValidationSeverity.Warning,
                code,
                path,
                message,
            )
        }

        validateData(data, ::error, ::warning)
        validateCrossRecordSemantics(data, ::error, ::warning)
        return PersonalizationValidationResult(issues)
    }

    private fun validateManifest(
        manifest: PersonalizationExportManifest,
        data: PersonalizationDataSet,
        rawDataBytes: ByteArray?,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        if (manifest.formatVersion != PERSONALIZATION_FORMAT_VERSION) {
            error(
                "unsupported_manifest_version",
                "formatVersion",
                "Unsupported personalization manifest version '${manifest.formatVersion}'.",
            )
        }
        validateUuid(manifest.exportId, "exportId", error)
        manifest.originDeviceId?.let { validateUuid(it, "originDeviceId", error) }
        if (manifest.createdAt < 0L) {
            error("invalid_created_at", "createdAt", "Creation time must not be negative.")
        }
        if (manifest.application.id.isBlank()) {
            error("blank_application_id", "application.id", "Application ID must not be blank.")
        }
        if (manifest.application.versionName.isBlank()) {
            error(
                "blank_application_version",
                "application.versionName",
                "Application version name must not be blank.",
            )
        }
        if (manifest.application.versionCode < 0L) {
            error(
                "invalid_application_version_code",
                "application.versionCode",
                "Application version code must not be negative.",
            )
        }
        if (manifest.includedCategories.isEmpty()) {
            error(
                "missing_categories",
                "includedCategories",
                "At least one personalization category must be included.",
            )
        }
        warnOnDuplicates(
            manifest.includedCategories.map { it.name },
            "includedCategories",
            "duplicate_category",
            warning,
        )
        validateLocales(manifest.locales, "locales", error, warning)

        if (manifest.privacy.containsSentenceText) {
            error(
                "sentence_text_forbidden",
                "privacy.containsSentenceText",
                "Portable personalization exports must not contain sentence text.",
            )
        }
        if (manifest.data.path != "data.json") {
            error("invalid_data_path", "data.path", "Personalization data must use data.json.")
        }
        if (manifest.data.mediaType != PERSONALIZATION_DATA_MEDIA_TYPE) {
            error(
                "invalid_data_media_type",
                "data.mediaType",
                "Unexpected personalization data media type.",
            )
        }
        if (!sha256Regex.matches(manifest.data.sha256)) {
            error(
                "invalid_data_sha256",
                "data.sha256",
                "SHA-256 must contain exactly 64 hexadecimal characters.",
            )
        }
        if (manifest.data.sizeBytes < 0L) {
            error("invalid_data_size", "data.sizeBytes", "Data size must not be negative.")
        }

        val expectedCounts = PersonalizationCounts.from(data)
        if (manifest.counts != expectedCounts) {
            error(
                "record_count_mismatch",
                "counts",
                "Manifest record counts do not match data.json.",
            )
        }

        val expectedLocales = data.locales().map { it.lowercase(Locale.ROOT) }.toSet()
        val manifestLocales = manifest.locales.map { it.lowercase(Locale.ROOT) }.toSet()
        if (manifestLocales != expectedLocales) {
            error(
                "locale_inventory_mismatch",
                "locales",
                "Manifest locales do not match locales present in data.json.",
            )
        }

        val expectedContainsNgrams = data.learnedNgrams.isNotEmpty()
        if (manifest.privacy.containsNgrams != expectedContainsNgrams) {
            error(
                "ngram_privacy_flag_mismatch",
                "privacy.containsNgrams",
                "N-gram privacy flag does not match exported data.",
            )
        }
        val expectedContainsAppScopes = data.correctionRules.any { it.appScope != null }
        if (manifest.privacy.containsAppScopes != expectedContainsAppScopes) {
            error(
                "app_scope_privacy_flag_mismatch",
                "privacy.containsAppScopes",
                "App-scope privacy flag does not match exported data.",
            )
        }

        validateCategoryInclusion(manifest.includedCategories.toSet(), data, error)

        rawDataBytes?.let { bytes ->
            if (manifest.data.sizeBytes != bytes.size.toLong()) {
                error(
                    "data_size_mismatch",
                    "data.sizeBytes",
                    "Manifest data size does not match the supplied payload bytes.",
                )
            }
            val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            if (!actualHash.equals(manifest.data.sha256, ignoreCase = true)) {
                error(
                    "data_hash_mismatch",
                    "data.sha256",
                    "Manifest SHA-256 does not match the supplied payload bytes.",
                )
            }
        }
    }

    private fun validateCategoryInclusion(
        included: Set<PersonalizationCategory>,
        data: PersonalizationDataSet,
        error: (String, String, String) -> Unit,
    ) {
        fun requireIncluded(category: PersonalizationCategory, hasRecords: Boolean, path: String) {
            if (hasRecords && category !in included) {
                error(
                    "excluded_category_contains_records",
                    path,
                    "Data contains records for a category not declared in includedCategories.",
                )
            }
        }

        requireIncluded(PersonalizationCategory.ManualWords, data.manualWords.isNotEmpty(), "manualWords")
        requireIncluded(PersonalizationCategory.LearnedWords, data.learnedWords.isNotEmpty(), "learnedWords")
        requireIncluded(PersonalizationCategory.LearnedNgrams, data.learnedNgrams.isNotEmpty(), "learnedNgrams")
        requireIncluded(PersonalizationCategory.WordRules, data.wordRules.isNotEmpty(), "wordRules")
        requireIncluded(
            PersonalizationCategory.CorrectionRules,
            data.correctionRules.isNotEmpty(),
            "correctionRules",
        )
        requireIncluded(PersonalizationCategory.Tombstones, data.tombstones.isNotEmpty(), "tombstones")
    }

    private fun validateData(
        data: PersonalizationDataSet,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        if (data.formatVersion != PERSONALIZATION_FORMAT_VERSION) {
            error(
                "unsupported_data_version",
                "formatVersion",
                "Unsupported personalization data version '${data.formatVersion}'.",
            )
        }

        val recordIds = mutableMapOf<String, String>()
        fun registerRecord(record: PersonalizationRecord, path: String) {
            validateRecordBase(record, path, error)
            val previous = recordIds.put(record.id.lowercase(Locale.ROOT), path)
            if (previous != null) {
                error(
                    "duplicate_record_id",
                    "$path.id",
                    "Record ID '${record.id}' is already used by $previous.",
                )
            }
        }

        data.manualWords.forEachIndexed { index, record ->
            val path = "manualWords[$index]"
            registerRecord(record, path)
            validateOptionalLocale(record.locale, "$path.locale", error)
            validateWord(record.word, "$path.word", error)
            record.shortcut?.let { validateWord(it, "$path.shortcut", error) }
            if (record.frequency !in 0..255) {
                error("invalid_frequency", "$path.frequency", "Frequency must be between 0 and 255.")
            }
        }

        data.learnedWords.forEachIndexed { index, record ->
            val path = "learnedWords[$index]"
            registerRecord(record, path)
            validateLocale(record.locale, "$path.locale", error)
            validateWord(record.word, "$path.word", error)
            validateLearnedEvidence(
                record.observationCount,
                record.firstSeenAt,
                record.lastSeenAt,
                record.updatedAt,
                record.confidence,
                path,
                error,
            )
        }

        data.learnedNgrams.forEachIndexed { index, record ->
            val path = "learnedNgrams[$index]"
            registerRecord(record, path)
            validateLocale(record.locale, "$path.locale", error)
            if (record.terms.size !in 2..4) {
                error("invalid_ngram_size", "$path.terms", "N-grams must contain two to four terms.")
            }
            record.terms.forEachIndexed { termIndex, term ->
                validateWord(term, "$path.terms[$termIndex]", error)
            }
            validateLearnedEvidence(
                record.observationCount,
                record.firstSeenAt,
                record.lastSeenAt,
                record.updatedAt,
                record.confidence,
                path,
                error,
            )
        }

        data.wordRules.forEachIndexed { index, record ->
            val path = "wordRules[$index]"
            registerRecord(record, path)
            validateOptionalLocale(record.locale, "$path.locale", error)
            validateWord(record.word, "$path.word", error)
        }

        data.correctionRules.forEachIndexed { index, record ->
            val path = "correctionRules[$index]"
            registerRecord(record, path)
            validateOptionalLocale(record.locale, "$path.locale", error)
            validateWord(record.typed, "$path.typed", error)
            validateWord(record.replacement, "$path.replacement", error)
            if (normalizedWord(record.typed) == normalizedWord(record.replacement)) {
                error(
                    "identical_correction_pair",
                    path,
                    "Typed and replacement text must differ.",
                )
            }
            record.appScope?.let { appScope ->
                if (!packageNameRegex.matches(appScope)) {
                    error(
                        "invalid_app_scope",
                        "$path.appScope",
                        "App scope must be an Android-style package name.",
                    )
                }
            }
        }

        val tombstoneTargets = mutableSetOf<String>()
        data.tombstones.forEachIndexed { index, tombstone ->
            val path = "tombstones[$index]"
            validateUuid(tombstone.targetId, "$path.targetId", error)
            tombstone.originDeviceId?.let { validateUuid(it, "$path.originDeviceId", error) }
            if (tombstone.revision < 1L) {
                error("invalid_revision", "$path.revision", "Revision must be at least one.")
            }
            if (tombstone.deletedAt < 0L) {
                error("invalid_deleted_at", "$path.deletedAt", "Deletion time must not be negative.")
            }
            if (!tombstoneTargets.add(tombstone.targetId.lowercase(Locale.ROOT))) {
                error(
                    "duplicate_tombstone",
                    "$path.targetId",
                    "Only one canonical tombstone may exist for a target record.",
                )
            }
            if (tombstone.targetId.lowercase(Locale.ROOT) in recordIds) {
                error(
                    "live_record_with_tombstone",
                    "$path.targetId",
                    "A canonical export must not contain a live record and its tombstone.",
                )
            }
        }

        warnOnDuplicateLogicalRecords(data, warning, error)
    }

    private fun validateCrossRecordSemantics(
        data: PersonalizationDataSet,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        val rulesByWord = data.wordRules.groupBy {
            localeWordKey(it.locale, it.word)
        }
        rulesByWord.forEach { (key, records) ->
            val actions = records.map { it.action }.toSet()
            if (actions.size > 1) {
                error(
                    "conflicting_word_rules",
                    "wordRules",
                    "Conflicting word-learning rules exist for '$key'.",
                )
            }
        }

        val doNotLearnKeys = data.wordRules
            .filter { it.action == WordRuleAction.DoNotLearn }
            .map { localeWordKey(it.locale, it.word) }
            .toSet()
        data.learnedWords.forEachIndexed { index, record ->
            val exactKey = localeWordKey(record.locale, record.word)
            val globalKey = localeWordKey(null, record.word)
            if (exactKey in doNotLearnKeys || globalKey in doNotLearnKeys) {
                if (record.state == LearnedRecordState.Active) {
                    error(
                        "active_word_blocked_from_learning",
                        "learnedWords[$index]",
                        "An active learned word conflicts with a do-not-learn rule.",
                    )
                } else {
                    warning(
                        "suppressed_word_retained",
                        "learnedWords[$index]",
                        "Suppressed evidence is retained despite a do-not-learn rule.",
                    )
                }
            }
        }

        val duplicateCorrectionActions = data.correctionRules.groupBy {
            correctionKey(it.locale, it.appScope, it.typed, it.replacement)
        }
        duplicateCorrectionActions.forEach { (key, records) ->
            if (records.map { it.action }.toSet().size > 1) {
                error(
                    "conflicting_correction_rules",
                    "correctionRules",
                    "Conflicting correction rules exist for '$key'.",
                )
            }
        }
    }

    private fun validateRecordBase(
        record: PersonalizationRecord,
        path: String,
        error: (String, String, String) -> Unit,
    ) {
        validateUuid(record.id, "$path.id", error)
        record.originDeviceId?.let { validateUuid(it, "$path.originDeviceId", error) }
        if (record.revision < 1L) {
            error("invalid_revision", "$path.revision", "Revision must be at least one.")
        }
        if (record.createdAt < 0L) {
            error("invalid_created_at", "$path.createdAt", "Creation time must not be negative.")
        }
        if (record.updatedAt < record.createdAt) {
            error(
                "updated_before_created",
                "$path.updatedAt",
                "updatedAt must not be earlier than createdAt.",
            )
        }
    }

    private fun validateLearnedEvidence(
        observationCount: Long,
        firstSeenAt: Long,
        lastSeenAt: Long,
        updatedAt: Long,
        confidence: Double,
        path: String,
        error: (String, String, String) -> Unit,
    ) {
        if (observationCount < 1L) {
            error(
                "invalid_observation_count",
                "$path.observationCount",
                "Observation count must be at least one.",
            )
        }
        if (firstSeenAt < 0L) {
            error("invalid_first_seen", "$path.firstSeenAt", "firstSeenAt must not be negative.")
        }
        if (lastSeenAt < firstSeenAt) {
            error(
                "last_seen_before_first_seen",
                "$path.lastSeenAt",
                "lastSeenAt must not be earlier than firstSeenAt.",
            )
        }
        if (lastSeenAt > updatedAt) {
            error(
                "last_seen_after_update",
                "$path.lastSeenAt",
                "lastSeenAt must not be later than updatedAt.",
            )
        }
        if (!confidence.isFinite() || confidence !in 0.0..1.0) {
            error(
                "invalid_confidence",
                "$path.confidence",
                "Confidence must be a finite value between zero and one.",
            )
        }
    }

    private fun warnOnDuplicateLogicalRecords(
        data: PersonalizationDataSet,
        warning: (String, String, String) -> Unit,
        error: (String, String, String) -> Unit,
    ) {
        fun reportDuplicates(values: List<Pair<String, String>>, code: String, message: String) {
            values.groupBy { it.first }
                .filterValues { it.size > 1 }
                .forEach { (key, records) ->
                    error(code, records.joinToString { it.second }, "$message '$key'.")
                }
        }

        reportDuplicates(
            data.manualWords.mapIndexed { index, record ->
                manualWordKey(record) to "manualWords[$index]"
            },
            "duplicate_manual_word",
            "Duplicate logical manual word",
        )
        reportDuplicates(
            data.learnedWords.mapIndexed { index, record ->
                localeWordKey(record.locale, record.word) to "learnedWords[$index]"
            },
            "duplicate_learned_word",
            "Duplicate logical learned word",
        )
        reportDuplicates(
            data.learnedNgrams.mapIndexed { index, record ->
                learnedNgramKey(record) to "learnedNgrams[$index]"
            },
            "duplicate_learned_ngram",
            "Duplicate logical learned n-gram",
        )
        reportDuplicates(
            data.wordRules.mapIndexed { index, record ->
                localeWordKey(record.locale, record.word) to "wordRules[$index]"
            },
            "duplicate_word_rule",
            "Duplicate logical word rule",
        )
        reportDuplicates(
            data.correctionRules.mapIndexed { index, record ->
                correctionKey(record.locale, record.appScope, record.typed, record.replacement) to
                    "correctionRules[$index]"
            },
            "duplicate_correction_rule",
            "Duplicate logical correction rule",
        )

        if (data.learnedNgrams.any { it.terms.distinct().size == 1 }) {
            warning(
                "repeated_only_ngram",
                "learnedNgrams",
                "At least one n-gram contains only the same repeated term.",
            )
        }
    }

    private fun validateLocales(
        locales: List<String>,
        path: String,
        error: (String, String, String) -> Unit,
        warning: (String, String, String) -> Unit,
    ) {
        locales.forEachIndexed { index, locale ->
            validateLocale(locale, "$path[$index]", error)
        }
        warnOnDuplicates(
            locales.map { it.lowercase(Locale.ROOT) },
            path,
            "duplicate_locale",
            warning,
        )
    }

    private fun validateOptionalLocale(
        locale: String?,
        path: String,
        error: (String, String, String) -> Unit,
    ) {
        locale?.let { validateLocale(it, path, error) }
    }

    private fun validateLocale(
        locale: String,
        path: String,
        error: (String, String, String) -> Unit,
    ) {
        if (!languageTagRegex.matches(locale)) {
            error("invalid_language_tag", path, "'$locale' is not a valid language tag.")
        }
    }

    private fun validateUuid(
        value: String,
        path: String,
        error: (String, String, String) -> Unit,
    ) {
        if (!uuidRegex.matches(value)) {
            error("invalid_uuid", path, "'$value' is not a supported UUID.")
        }
    }

    private fun validateWord(
        value: String,
        path: String,
        error: (String, String, String) -> Unit,
    ) {
        if (value.isBlank()) {
            error("blank_text", path, "Text must not be blank.")
            return
        }
        if (value.codePointCount(0, value.length) > 256) {
            error("text_too_long", path, "Text must not exceed 256 Unicode code points.")
        }
        if (value.any { it.code < 0x20 || it.code == 0x7f }) {
            error("control_character", path, "Text must not contain control characters.")
        }
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            error("text_not_nfc", path, "Text must use Unicode NFC normalization.")
        }
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

    private fun manualWordKey(record: ManualWordRecord): String {
        return listOf(
            normalizeLocale(record.locale),
            normalizedWord(record.word),
            record.shortcut?.let(::normalizedWord).orEmpty(),
        ).joinToString("\u001f")
    }

    private fun learnedNgramKey(record: LearnedNgramRecord): String {
        return normalizeLocale(record.locale) + "\u001f" +
            record.terms.joinToString("\u001e") { normalizedWord(it) }
    }

    private fun localeWordKey(locale: String?, word: String): String {
        return normalizeLocale(locale) + "\u001f" + normalizedWord(word)
    }

    private fun correctionKey(
        locale: String?,
        appScope: String?,
        typed: String,
        replacement: String,
    ): String {
        return listOf(
            normalizeLocale(locale),
            appScope?.lowercase(Locale.ROOT).orEmpty(),
            normalizedWord(typed),
            normalizedWord(replacement),
        ).joinToString("\u001f")
    }

    private fun normalizeLocale(locale: String?): String {
        return locale?.lowercase(Locale.ROOT).orEmpty()
    }

    private fun normalizedWord(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    private fun ByteArray.toHexString(): String {
        val digits = "0123456789abcdef"
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = digits[value ushr 4]
            output[index * 2 + 1] = digits[value and 0x0f]
        }
        return output.concatToString()
    }
}
