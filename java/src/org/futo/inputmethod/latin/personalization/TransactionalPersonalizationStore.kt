package org.futo.inputmethod.latin.personalization

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PERSONALIZATION_STORE_VERSION = "0.1"
private const val STORE_DATA_FILE_NAME = "data.json"
private const val STORE_COMMIT_FILE_NAME = "commit.json"
private const val STORE_LOCK_FILE_NAME = ".lock"
private val GENERATION_DIRECTORY_REGEX = Regex("^([0-9]{20})-([a-f0-9]{12})$")
private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")

data class PersonalizationStoreLimits(
    val maxDataBytes: Long = 64L * 1024L * 1024L,
    val maxCommitMetadataBytes: Long = 8L * 1024L * 1024L,
    val maxJournalChanges: Int = 100_000,
)

@Serializable
enum class PersonalizationStoreCommitReason {
    @SerialName("initialization") Initialization,
    @SerialName("user-edit") UserEdit,
    @SerialName("import") Import,
    @SerialName("migration") Migration,
    @SerialName("rollback") Rollback,
}

@Serializable
data class PersonalizationStoreJournalChange(
    val kind: String,
    val recordKind: String,
    val recordId: String,
    val message: String,
)

@Serializable
data class PersonalizationStoreGeneration(
    val storeVersion: String,
    val generation: Long,
    val generationId: String,
    val parentGenerationId: String? = null,
    val committedAt: Long,
    val reason: PersonalizationStoreCommitReason,
    val sourceGenerationId: String? = null,
    val dataSha256: String,
    val dataSizeBytes: Long,
    val changes: List<PersonalizationStoreJournalChange> = emptyList(),
)

data class PersonalizationStoreRecoveryIssue(
    val generationDirectory: String,
    val message: String,
)

data class PersonalizationStoreSnapshot(
    val generation: PersonalizationStoreGeneration,
    val data: PersonalizationDataSet,
    val generationDirectory: File,
    val recoveryIssues: List<PersonalizationStoreRecoveryIssue> = emptyList(),
)

sealed class PersonalizationStoreReadResult {
    data class Ready(val snapshot: PersonalizationStoreSnapshot) : PersonalizationStoreReadResult()
    data object Empty : PersonalizationStoreReadResult()
    data class Failed(val message: String, val cause: Throwable? = null) : PersonalizationStoreReadResult()
}

sealed class PersonalizationStoreInitializeResult {
    data class Initialized(val snapshot: PersonalizationStoreSnapshot) : PersonalizationStoreInitializeResult()
    data class AlreadyInitialized(val snapshot: PersonalizationStoreSnapshot) : PersonalizationStoreInitializeResult()
    data class Invalid(val validation: PersonalizationValidationResult) : PersonalizationStoreInitializeResult()
    data class Failed(val message: String, val cause: Throwable? = null) : PersonalizationStoreInitializeResult()
}

sealed class PersonalizationStoreCommitResult {
    data class Committed(val snapshot: PersonalizationStoreSnapshot) : PersonalizationStoreCommitResult()
    data class Unchanged(val snapshot: PersonalizationStoreSnapshot) : PersonalizationStoreCommitResult()
    data class Conflict(
        val expectedGenerationId: String,
        val actualGenerationId: String,
    ) : PersonalizationStoreCommitResult()
    data class Invalid(val validation: PersonalizationValidationResult) : PersonalizationStoreCommitResult()
    data class Failed(val message: String, val cause: Throwable? = null) : PersonalizationStoreCommitResult()
}

/**
 * Crash-safe editable personalization source store.
 *
 * Every successful transaction creates a complete immutable generation directory. A generation
 * becomes visible only when its fully synced staging directory is renamed into `generations/`.
 * There is deliberately no mutable current-pointer file: the highest valid generation is current.
 * Rollback therefore creates a new generation rather than rewriting history.
 *
 * Methods perform blocking file I/O and must be called from an I/O dispatcher outside tests.
 */
class TransactionalPersonalizationStore(
    private val rootDirectory: File,
    private val limits: PersonalizationStoreLimits = PersonalizationStoreLimits(),
) {
    private val stagingDirectory = File(rootDirectory, ".staging")
    private val generationsDirectory = File(rootDirectory, "generations")
    private val lockFile = File(rootDirectory, STORE_LOCK_FILE_NAME)

    companion object {
        private val processLocks = ConcurrentHashMap<String, Any>()

        fun forContext(
            context: Context,
            limits: PersonalizationStoreLimits = PersonalizationStoreLimits(),
        ): TransactionalPersonalizationStore {
            return TransactionalPersonalizationStore(
                rootDirectory = File(context.filesDir, "personalization-source"),
                limits = limits,
            )
        }

        fun emptyDataSet(): PersonalizationDataSet = PersonalizationDataSet(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
        )
    }

    fun initialize(
        initialData: PersonalizationDataSet = emptyDataSet(),
        committedAt: Long,
        reason: PersonalizationStoreCommitReason = PersonalizationStoreCommitReason.Initialization,
    ): PersonalizationStoreInitializeResult {
        if (committedAt < 0L) {
            return PersonalizationStoreInitializeResult.Failed("Commit time must not be negative.")
        }
        if (reason != PersonalizationStoreCommitReason.Initialization &&
            reason != PersonalizationStoreCommitReason.Migration
        ) {
            return PersonalizationStoreInitializeResult.Failed(
                "Initialization accepts only initialization or migration reasons.",
            )
        }
        val validation = PersonalizationDataValidator.validateData(initialData)
        if (!validation.isValid) return PersonalizationStoreInitializeResult.Invalid(validation)

        return try {
            withExclusiveStoreLock {
                ensureDirectories()
                cleanupStagingLocked()
                val directories = generationDirectoriesDescending()
                val current = loadCurrentLocked(directories)
                when {
                    current != null -> PersonalizationStoreInitializeResult.AlreadyInitialized(current)
                    directories.isNotEmpty() -> PersonalizationStoreInitializeResult.Failed(
                        "Personalization store contains generations, but none can be validated. Refusing to overwrite recoverable data.",
                    )
                    else -> PersonalizationStoreInitializeResult.Initialized(
                        writeGenerationLocked(
                            generationNumber = 1L,
                            parentGenerationId = null,
                            committedAt = committedAt,
                            reason = reason,
                            sourceGenerationId = null,
                            changes = emptyList(),
                            data = initialData,
                        ),
                    )
                }
            }
        } catch (exception: Exception) {
            PersonalizationStoreInitializeResult.Failed(
                exception.message ?: exception.javaClass.simpleName,
                exception,
            )
        }
    }

    fun readCurrent(): PersonalizationStoreReadResult {
        return try {
            withExclusiveStoreLock {
                ensureDirectories()
                cleanupStagingLocked()
                loadCurrentLocked()?.let(PersonalizationStoreReadResult::Ready)
                    ?: PersonalizationStoreReadResult.Empty
            }
        } catch (exception: Exception) {
            PersonalizationStoreReadResult.Failed(
                exception.message ?: exception.javaClass.simpleName,
                exception,
            )
        }
    }

    fun commitEdit(
        expectedGenerationId: String,
        edit: PersonalizationEditResult,
        committedAt: Long,
    ): PersonalizationStoreCommitResult {
        if (committedAt < 0L) {
            return PersonalizationStoreCommitResult.Failed("Commit time must not be negative.")
        }
        val validation = PersonalizationDataValidator.validateData(edit.data)
        if (!validation.isValid) return PersonalizationStoreCommitResult.Invalid(validation)

        return try {
            withExclusiveStoreLock {
                ensureDirectories()
                cleanupStagingLocked()
                val current = loadCurrentLocked()
                    ?: return@withExclusiveStoreLock PersonalizationStoreCommitResult.Failed(
                        "Personalization store has not been initialized.",
                    )
                if (current.generation.generationId != expectedGenerationId) {
                    return@withExclusiveStoreLock PersonalizationStoreCommitResult.Conflict(
                        expectedGenerationId,
                        current.generation.generationId,
                    )
                }
                if (!edit.changed) {
                    return@withExclusiveStoreLock if (edit.data == current.data) {
                        PersonalizationStoreCommitResult.Unchanged(current)
                    } else {
                        PersonalizationStoreCommitResult.Failed(
                            "Edit changed data without producing journal changes.",
                        )
                    }
                }
                if (edit.data == current.data) {
                    return@withExclusiveStoreLock PersonalizationStoreCommitResult.Failed(
                        "Edit produced journal changes without changing data.",
                    )
                }

                val changes = edit.changes.map { change ->
                    PersonalizationStoreJournalChange(
                        kind = change.kind.name,
                        recordKind = change.recordKind.name,
                        recordId = change.recordId,
                        message = change.message,
                    )
                }
                PersonalizationStoreCommitResult.Committed(
                    writeGenerationLocked(
                        generationNumber = nextGenerationNumberLocked(),
                        parentGenerationId = current.generation.generationId,
                        committedAt = committedAt,
                        reason = PersonalizationStoreCommitReason.UserEdit,
                        sourceGenerationId = null,
                        changes = changes,
                        data = edit.data,
                    ),
                )
            }
        } catch (exception: Exception) {
            PersonalizationStoreCommitResult.Failed(
                exception.message ?: exception.javaClass.simpleName,
                exception,
            )
        }
    }

    fun replaceData(
        expectedGenerationId: String,
        data: PersonalizationDataSet,
        committedAt: Long,
        reason: PersonalizationStoreCommitReason,
        sourceGenerationId: String? = null,
        journalMessage: String,
    ): PersonalizationStoreCommitResult {
        require(reason == PersonalizationStoreCommitReason.Import ||
            reason == PersonalizationStoreCommitReason.Migration
        ) { "replaceData only accepts import or migration reasons." }
        require(journalMessage.isNotBlank()) { "journalMessage must not be blank." }
        if (committedAt < 0L) {
            return PersonalizationStoreCommitResult.Failed("Commit time must not be negative.")
        }
        val validation = PersonalizationDataValidator.validateData(data)
        if (!validation.isValid) return PersonalizationStoreCommitResult.Invalid(validation)

        return try {
            withExclusiveStoreLock {
                ensureDirectories()
                cleanupStagingLocked()
                val current = loadCurrentLocked()
                    ?: return@withExclusiveStoreLock PersonalizationStoreCommitResult.Failed(
                        "Personalization store has not been initialized.",
                    )
                if (current.generation.generationId != expectedGenerationId) {
                    return@withExclusiveStoreLock PersonalizationStoreCommitResult.Conflict(
                        expectedGenerationId,
                        current.generation.generationId,
                    )
                }
                if (data == current.data) {
                    return@withExclusiveStoreLock PersonalizationStoreCommitResult.Unchanged(current)
                }

                PersonalizationStoreCommitResult.Committed(
                    writeGenerationLocked(
                        generationNumber = nextGenerationNumberLocked(),
                        parentGenerationId = current.generation.generationId,
                        committedAt = committedAt,
                        reason = reason,
                        sourceGenerationId = sourceGenerationId,
                        changes = listOf(
                            PersonalizationStoreJournalChange(
                                kind = "DataReplaced",
                                recordKind = "DataSet",
                                recordId = sourceGenerationId ?: "external",
                                message = journalMessage,
                            ),
                        ),
                        data = data,
                    ),
                )
            }
        } catch (exception: Exception) {
            PersonalizationStoreCommitResult.Failed(
                exception.message ?: exception.javaClass.simpleName,
                exception,
            )
        }
    }

    fun rollback(
        expectedCurrentGenerationId: String,
        targetGenerationId: String,
        committedAt: Long,
    ): PersonalizationStoreCommitResult {
        if (committedAt < 0L) {
            return PersonalizationStoreCommitResult.Failed("Commit time must not be negative.")
        }
        return try {
            withExclusiveStoreLock {
                ensureDirectories()
                cleanupStagingLocked()
                val current = loadCurrentLocked()
                    ?: return@withExclusiveStoreLock PersonalizationStoreCommitResult.Failed(
                        "Personalization store has not been initialized.",
                    )
                if (current.generation.generationId != expectedCurrentGenerationId) {
                    return@withExclusiveStoreLock PersonalizationStoreCommitResult.Conflict(
                        expectedCurrentGenerationId,
                        current.generation.generationId,
                    )
                }
                val target = loadGenerationByIdLocked(targetGenerationId)
                    ?: return@withExclusiveStoreLock PersonalizationStoreCommitResult.Failed(
                        "Rollback target '$targetGenerationId' is missing or invalid.",
                    )
                if (target.data == current.data) {
                    return@withExclusiveStoreLock PersonalizationStoreCommitResult.Unchanged(current)
                }

                PersonalizationStoreCommitResult.Committed(
                    writeGenerationLocked(
                        generationNumber = nextGenerationNumberLocked(),
                        parentGenerationId = current.generation.generationId,
                        committedAt = committedAt,
                        reason = PersonalizationStoreCommitReason.Rollback,
                        sourceGenerationId = target.generation.generationId,
                        changes = listOf(
                            PersonalizationStoreJournalChange(
                                kind = "Rollback",
                                recordKind = "DataSet",
                                recordId = target.generation.generationId,
                                message = "Restored personalization data from generation ${target.generation.generationId}.",
                            ),
                        ),
                        data = target.data,
                    ),
                )
            }
        } catch (exception: Exception) {
            PersonalizationStoreCommitResult.Failed(
                exception.message ?: exception.javaClass.simpleName,
                exception,
            )
        }
    }

    fun listHistory(limit: Int = 100): List<PersonalizationStoreGeneration> {
        require(limit > 0) { "limit must be positive." }
        return withExclusiveStoreLock {
            ensureDirectories()
            cleanupStagingLocked()
            immutableList(
                generationDirectoriesDescending()
                    .mapNotNull { loadGenerationLocked(it)?.generation }
                    .take(limit),
            )
        }
    }

    fun cleanupStaging(): Int = withExclusiveStoreLock {
        ensureDirectories()
        cleanupStagingLocked()
    }

    private fun loadCurrentLocked(
        directories: List<File> = generationDirectoriesDescending(),
    ): PersonalizationStoreSnapshot? {
        val recoveryIssues = mutableListOf<PersonalizationStoreRecoveryIssue>()
        directories.forEach { directory ->
            val loaded = loadGenerationLocked(directory)
            if (loaded != null) {
                return loaded.copy(recoveryIssues = immutableList(recoveryIssues))
            }
            recoveryIssues += PersonalizationStoreRecoveryIssue(
                generationDirectory = directory.name,
                message = "Generation is incomplete, corrupt, too large, or semantically invalid and was skipped.",
            )
        }
        return null
    }

    private fun loadGenerationByIdLocked(generationId: String): PersonalizationStoreSnapshot? {
        if (!GENERATION_DIRECTORY_REGEX.matches(generationId)) return null
        return loadGenerationLocked(File(generationsDirectory, generationId))
    }

    private fun loadGenerationLocked(directory: File): PersonalizationStoreSnapshot? {
        if (!directory.isDirectory || !GENERATION_DIRECTORY_REGEX.matches(directory.name)) return null
        val dataFile = File(directory, STORE_DATA_FILE_NAME)
        val commitFile = File(directory, STORE_COMMIT_FILE_NAME)
        if (!dataFile.isFile || !commitFile.isFile) return null
        if (dataFile.length() > limits.maxDataBytes ||
            commitFile.length() > limits.maxCommitMetadataBytes
        ) return null

        return try {
            val metadata = STORE_JSON.decodeFromString<PersonalizationStoreGeneration>(
                commitFile.readText(Charsets.UTF_8),
            )
            val nameMatch = GENERATION_DIRECTORY_REGEX.matchEntire(directory.name) ?: return null
            val directoryGeneration = nameMatch.groupValues[1].toLongOrNull() ?: return null
            if (metadata.storeVersion != PERSONALIZATION_STORE_VERSION) return null
            if (metadata.generationId != directory.name) return null
            if (metadata.generation != directoryGeneration || metadata.generation < 1L) return null
            if (metadata.committedAt < 0L || metadata.dataSizeBytes < 0L) return null
            if (metadata.dataSizeBytes > limits.maxDataBytes) return null
            if (metadata.changes.size > limits.maxJournalChanges) return null
            if (!SHA256_REGEX.matches(metadata.dataSha256)) return null
            if (metadata.parentGenerationId != null &&
                !GENERATION_DIRECTORY_REGEX.matches(metadata.parentGenerationId)
            ) return null

            val dataBytes = dataFile.readBytes()
            if (dataBytes.size.toLong() != metadata.dataSizeBytes) return null
            if (sha256(dataBytes) != metadata.dataSha256) return null
            if (!directory.name.endsWith(metadata.dataSha256.take(12))) return null

            val data = PersonalizationDataCodec.decodeData(
                dataBytes.toString(Charsets.UTF_8),
            ).deepImmutableCopy()
            if (!PersonalizationDataValidator.validateData(data).isValid) return null
            PersonalizationStoreSnapshot(
                generation = metadata.deepImmutableCopy(),
                data = data,
                generationDirectory = directory,
                recoveryIssues = emptyList(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeGenerationLocked(
        generationNumber: Long,
        parentGenerationId: String?,
        committedAt: Long,
        reason: PersonalizationStoreCommitReason,
        sourceGenerationId: String?,
        changes: List<PersonalizationStoreJournalChange>,
        data: PersonalizationDataSet,
    ): PersonalizationStoreSnapshot {
        require(generationNumber > 0L) { "Generation number must be positive." }
        require(changes.size <= limits.maxJournalChanges) {
            "Journal contains more than ${limits.maxJournalChanges} changes."
        }
        val validation = PersonalizationDataValidator.validateData(data)
        require(validation.isValid) {
            validation.errors.joinToString(separator = "; ") { "${it.path}: ${it.message}" }
        }

        val dataBytes = PersonalizationDataCodec.encodeData(data).toByteArray(Charsets.UTF_8)
        require(dataBytes.size.toLong() <= limits.maxDataBytes) {
            "Personalization data exceeds ${limits.maxDataBytes} bytes."
        }
        val dataHash = sha256(dataBytes)
        val generationId = "%020d-%s".format(Locale.ROOT, generationNumber, dataHash.take(12))
        val metadata = PersonalizationStoreGeneration(
            storeVersion = PERSONALIZATION_STORE_VERSION,
            generation = generationNumber,
            generationId = generationId,
            parentGenerationId = parentGenerationId,
            committedAt = committedAt,
            reason = reason,
            sourceGenerationId = sourceGenerationId,
            dataSha256 = dataHash,
            dataSizeBytes = dataBytes.size.toLong(),
            changes = changes,
        )
        val metadataBytes = STORE_JSON.encodeToString(metadata).toByteArray(Charsets.UTF_8)
        require(metadataBytes.size.toLong() <= limits.maxCommitMetadataBytes) {
            "Personalization commit metadata exceeds ${limits.maxCommitMetadataBytes} bytes."
        }

        val staging = File(stagingDirectory, UUID.randomUUID().toString())
        val destination = File(generationsDirectory, generationId)
        if (!staging.mkdir()) {
            throw IllegalStateException("Could not create personalization staging directory.")
        }
        try {
            writeSynced(File(staging, STORE_DATA_FILE_NAME), dataBytes)
            writeSynced(File(staging, STORE_COMMIT_FILE_NAME), metadataBytes)
            if (destination.exists()) {
                val existing = loadGenerationLocked(destination)
                if (existing != null && existing.generation.dataSha256 == dataHash) return existing
                throw IllegalStateException("Personalization generation '$generationId' already exists with different content.")
            }
            if (!staging.renameTo(destination)) {
                throw IllegalStateException("Could not atomically commit personalization generation '$generationId'.")
            }
            return loadGenerationLocked(destination)
                ?: throw IllegalStateException("Committed personalization generation could not be read back.")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun nextGenerationNumberLocked(): Long {
        val parsed = generationDirectoriesDescending().map { directory ->
            generationNumberFromDirectory(directory)
                ?: throw IllegalStateException(
                    "Generation directory '${directory.name}' cannot be represented safely.",
                )
        }
        val highest = parsed.maxOrNull() ?: 0L
        if (highest == Long.MAX_VALUE) {
            throw IllegalStateException("Personalization generation counter is exhausted.")
        }
        return highest + 1L
    }

    private fun generationDirectoriesDescending(): List<File> {
        if (!generationsDirectory.isDirectory) return emptyList()
        return generationsDirectory.listFiles()
            .orEmpty()
            .filter { it.isDirectory && GENERATION_DIRECTORY_REGEX.matches(it.name) }
            .sortedWith(
                compareByDescending<File> { generationNumberFromDirectory(it) ?: Long.MIN_VALUE }
                    .thenByDescending { it.name },
            )
    }

    private fun generationNumberFromDirectory(directory: File): Long? {
        return GENERATION_DIRECTORY_REGEX.matchEntire(directory.name)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    }

    private fun ensureDirectories() {
        require(limits.maxDataBytes > 0L) { "maxDataBytes must be positive." }
        require(limits.maxCommitMetadataBytes > 0L) {
            "maxCommitMetadataBytes must be positive."
        }
        require(limits.maxJournalChanges > 0) { "maxJournalChanges must be positive." }
        if (!rootDirectory.isDirectory && !rootDirectory.mkdirs()) {
            throw IllegalStateException("Could not create personalization store directory.")
        }
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
            throw IllegalStateException("Could not create personalization staging directory.")
        }
        if (!generationsDirectory.isDirectory && !generationsDirectory.mkdirs()) {
            throw IllegalStateException("Could not create personalization generations directory.")
        }
        if (!lockFile.exists() && !lockFile.createNewFile() && !lockFile.isFile) {
            throw IllegalStateException("Could not create personalization store lock file.")
        }
    }

    private fun cleanupStagingLocked(): Int {
        if (!stagingDirectory.isDirectory) return 0
        var removed = 0
        stagingDirectory.listFiles().orEmpty().forEach { entry ->
            if (entry.deleteRecursively()) removed += 1
        }
        return removed
    }

    private fun <T> withExclusiveStoreLock(block: () -> T): T {
        val canonicalRoot = rootDirectory.canonicalFile
        val processLock = processLocks.computeIfAbsent(canonicalRoot.path) { Any() }
        return synchronized(processLock) {
            if (!canonicalRoot.isDirectory && !canonicalRoot.mkdirs()) {
                throw IllegalStateException("Could not create personalization store directory.")
            }
            val canonicalLockFile = File(canonicalRoot, STORE_LOCK_FILE_NAME)
            RandomAccessFile(canonicalLockFile, "rw").use { randomAccessFile ->
                randomAccessFile.channel.use { channel ->
                    channel.lock().use { block() }
                }
            }
        }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }
}

private val STORE_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    encodeDefaults = true
}

private fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
