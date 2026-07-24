package org.futo.inputmethod.latin.languagepack

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

private const val STORED_ARCHIVE_FILE_NAME = "package.futolanguage"
private const val STORED_CONTENT_DIRECTORY_NAME = "content"
private const val STORED_ARCHIVE_HASH_FILE_NAME = "archive.sha256"
private const val STORED_ARCHIVE_SIZE_FILE_NAME = "archive.size"

private data class StagedArchive(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
)

data class InstalledLanguagePackage(
    val manifest: LanguagePackageManifest,
    val installationDirectory: File,
    val archiveFile: File,
    val contentDirectory: File,
    val archiveSha256: String,
    val archiveSizeBytes: Long,
)

sealed class LanguagePackageInstallResult {
    data class Installed(val packageInfo: InstalledLanguagePackage) : LanguagePackageInstallResult()
    data class AlreadyInstalled(val packageInfo: InstalledLanguagePackage) : LanguagePackageInstallResult()
    data class Invalid(val inspection: LanguagePackageInspectionResult) : LanguagePackageInstallResult()
    data class Conflict(
        val packageId: String,
        val version: String,
        val message: String,
    ) : LanguagePackageInstallResult()

    data class Failed(val message: String, val cause: Throwable? = null) : LanguagePackageInstallResult()
}

data class LanguagePackageStoreLimits(
    val maxArchiveBytes: Long = 2L * 1024L * 1024L * 1024L,
    val archiveLimits: LanguagePackageArchiveLimits = LanguagePackageArchiveLimits(),
)

/**
 * Installs validated language packages side by side without activating any component.
 *
 * The store first copies the source stream into a private staging directory, validates that exact
 * staged archive, extracts only manifest-declared content, and finally renames the staging
 * directory into place. Installed package directories are treated as immutable.
 */
class LanguagePackageStore(
    private val rootDirectory: File,
    private val limits: LanguagePackageStoreLimits = LanguagePackageStoreLimits(),
) {
    private val stagingDirectory = File(rootDirectory, ".staging")
    private val packagesDirectory = File(rootDirectory, "packages")

    companion object {
        fun forContext(
            context: Context,
            limits: LanguagePackageStoreLimits = LanguagePackageStoreLimits(),
        ): LanguagePackageStore {
            return LanguagePackageStore(
                rootDirectory = File(context.filesDir, "language-packages"),
                limits = limits,
            )
        }
    }

    @Synchronized
    fun install(input: InputStream): LanguagePackageInstallResult {
        if (limits.maxArchiveBytes <= 0L) {
            return LanguagePackageInstallResult.Failed("Maximum archive size must be positive.")
        }

        val staging = File(stagingDirectory, UUID.randomUUID().toString())
        try {
            ensureStoreDirectories()
            if (!staging.mkdir()) {
                return LanguagePackageInstallResult.Failed("Could not create package staging directory.")
            }

            val stagedArchive = copyArchiveToStaging(input, staging)
            val inspection = FileInputStream(stagedArchive.file).use { archiveInput ->
                LanguagePackageArchiveInspector.inspect(
                    input = archiveInput,
                    limits = limits.archiveLimits,
                )
            }
            if (!inspection.isValid) {
                return LanguagePackageInstallResult.Invalid(inspection)
            }

            val manifest = inspection.manifest
                ?: return LanguagePackageInstallResult.Failed("Validated package has no manifest.")
            val destination = destinationFor(
                packageId = manifest.packageInfo.id,
                version = manifest.packageInfo.version,
            )

            if (destination.exists()) {
                val existing = loadInstalled(destination)
                return if (existing != null && existing.archiveSha256.equals(stagedArchive.sha256, ignoreCase = true)) {
                    LanguagePackageInstallResult.AlreadyInstalled(existing)
                } else {
                    LanguagePackageInstallResult.Conflict(
                        packageId = manifest.packageInfo.id,
                        version = manifest.packageInfo.version,
                        message = "A different package with the same ID and version is already installed.",
                    )
                }
            }

            extractValidatedContent(
                stagedArchive = stagedArchive.file,
                stagingDirectory = staging,
                inspection = inspection,
            )
            writeInstallMetadata(staging, stagedArchive)

            val packageParent = destination.parentFile
                ?: return LanguagePackageInstallResult.Failed("Package destination has no parent directory.")
            if (!packageParent.isDirectory && !packageParent.mkdirs()) {
                return LanguagePackageInstallResult.Failed("Could not create package destination directory.")
            }

            if (!staging.renameTo(destination)) {
                if (destination.exists()) {
                    val existing = loadInstalled(destination)
                    return if (existing != null && existing.archiveSha256.equals(stagedArchive.sha256, ignoreCase = true)) {
                        LanguagePackageInstallResult.AlreadyInstalled(existing)
                    } else {
                        LanguagePackageInstallResult.Conflict(
                            packageId = manifest.packageInfo.id,
                            version = manifest.packageInfo.version,
                            message = "Package destination appeared while installation was in progress.",
                        )
                    }
                }
                return LanguagePackageInstallResult.Failed("Could not atomically move the staged package into place.")
            }

            val installed = loadInstalled(destination)
                ?: return LanguagePackageInstallResult.Failed("Installed package could not be read back.")
            return LanguagePackageInstallResult.Installed(installed)
        } catch (exception: ArchiveSizeLimitExceededException) {
            return LanguagePackageInstallResult.Failed(exception.message ?: "Archive is too large.", exception)
        } catch (exception: Exception) {
            return LanguagePackageInstallResult.Failed(
                message = exception.message ?: exception.javaClass.simpleName,
                cause = exception,
            )
        } finally {
            if (staging.exists()) {
                staging.deleteRecursively()
            }
        }
    }

    fun listInstalled(): List<InstalledLanguagePackage> {
        if (!packagesDirectory.isDirectory) return emptyList()

        return packagesDirectory.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .flatMap { packageDirectory ->
                packageDirectory.listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .mapNotNull(::loadInstalled)
            }
            .sortedWith(
                compareBy<InstalledLanguagePackage> { it.manifest.packageInfo.id }
                    .thenBy { it.manifest.packageInfo.version },
            )
    }

    fun getInstalled(packageId: String, version: String): InstalledLanguagePackage? {
        return loadInstalled(destinationFor(packageId, version))
    }

    fun cleanupStaging(): Int {
        if (!stagingDirectory.isDirectory) return 0

        var removed = 0
        stagingDirectory.listFiles().orEmpty().forEach { entry ->
            if (entry.deleteRecursively()) removed += 1
        }
        return removed
    }

    private fun ensureStoreDirectories() {
        if (!rootDirectory.isDirectory && !rootDirectory.mkdirs()) {
            throw IllegalStateException("Could not create language package store directory.")
        }
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
            throw IllegalStateException("Could not create language package staging directory.")
        }
        if (!packagesDirectory.isDirectory && !packagesDirectory.mkdirs()) {
            throw IllegalStateException("Could not create language package destination directory.")
        }
    }

    private fun copyArchiveToStaging(input: InputStream, staging: File): StagedArchive {
        val destination = File(staging, STORED_ARCHIVE_FILE_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0L

        FileOutputStream(destination).use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) continue

                totalBytes += read
                if (totalBytes > limits.maxArchiveBytes) {
                    throw ArchiveSizeLimitExceededException(
                        "Package archive exceeds ${limits.maxArchiveBytes} bytes.",
                    )
                }

                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
            output.fd.sync()
        }

        return StagedArchive(
            file = destination,
            sha256 = digest.digest().toHexString(),
            sizeBytes = totalBytes,
        )
    }

    private fun extractValidatedContent(
        stagedArchive: File,
        stagingDirectory: File,
        inspection: LanguagePackageInspectionResult,
    ) {
        val manifest = inspection.manifest
            ?: throw IllegalStateException("Cannot extract a package without a manifest.")
        val contentDirectory = File(stagingDirectory, STORED_CONTENT_DIRECTORY_NAME)
        if (!contentDirectory.mkdir()) {
            throw IllegalStateException("Could not create staged package content directory.")
        }

        val requiredPaths = linkedSetOf("manifest.json")
        manifest.components.forEach { requiredPaths += it.payload.path }
        manifest.resources.forEach { requiredPaths += it.path }
        inspection.entries.keys
            .filter { it.startsWith("signatures/") }
            .forEach { requiredPaths += it }

        val extractedPaths = mutableSetOf<String>()
        FileInputStream(stagedArchive).use { archiveInput ->
            ZipInputStream(archiveInput).use { zip ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name
                    if (entry.isDirectory || path !in requiredPaths) {
                        zip.closeEntry()
                        continue
                    }

                    val expected = inspection.entries[path]
                        ?: throw IllegalStateException("Validated archive entry '$path' is missing from inspection results.")
                    val target = safeContentFile(contentDirectory, path)
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        throw IllegalStateException("Could not create directory for '$path'.")
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    var extractedBytes = 0L
                    FileOutputStream(target).use { output ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read == -1) break
                            if (read == 0) continue

                            extractedBytes += read
                            if (extractedBytes > expected.sizeBytes) {
                                throw IllegalStateException("Archive entry '$path' changed after validation.")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                    zip.closeEntry()

                    val extractedHash = digest.digest().toHexString()
                    if (extractedBytes != expected.sizeBytes ||
                        !extractedHash.equals(expected.sha256, ignoreCase = true)
                    ) {
                        throw IllegalStateException("Archive entry '$path' changed after validation.")
                    }
                    extractedPaths += path
                }
            }
        }

        val missingPaths = requiredPaths - extractedPaths
        if (missingPaths.isNotEmpty()) {
            throw IllegalStateException(
                "Validated archive entries were not extracted: ${missingPaths.joinToString()}.",
            )
        }
    }

    private fun writeInstallMetadata(staging: File, archive: StagedArchive) {
        writeSyncedText(File(staging, STORED_ARCHIVE_HASH_FILE_NAME), archive.sha256 + "\n")
        writeSyncedText(File(staging, STORED_ARCHIVE_SIZE_FILE_NAME), archive.sizeBytes.toString() + "\n")
    }

    private fun writeSyncedText(file: File, value: String) {
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun loadInstalled(directory: File): InstalledLanguagePackage? {
        if (!directory.isDirectory) return null

        val archive = File(directory, STORED_ARCHIVE_FILE_NAME)
        val content = File(directory, STORED_CONTENT_DIRECTORY_NAME)
        val manifestFile = File(content, "manifest.json")
        val hashFile = File(directory, STORED_ARCHIVE_HASH_FILE_NAME)
        val sizeFile = File(directory, STORED_ARCHIVE_SIZE_FILE_NAME)
        if (!archive.isFile || !content.isDirectory || !manifestFile.isFile ||
            !hashFile.isFile || !sizeFile.isFile
        ) {
            return null
        }

        return try {
            val manifest = LanguagePackageManifestCodec.decode(manifestFile.readText(Charsets.UTF_8))
            val validation = LanguagePackageManifestValidator.validate(manifest)
            if (!validation.isValid) return null

            val expectedDirectory = destinationFor(
                packageId = manifest.packageInfo.id,
                version = manifest.packageInfo.version,
            )
            if (directory.canonicalFile != expectedDirectory.canonicalFile) return null

            val archiveHash = hashFile.readText(Charsets.UTF_8).trim()
            val archiveSize = sizeFile.readText(Charsets.UTF_8).trim().toLong()
            if (!archiveHash.matches(Regex("^[A-Fa-f0-9]{64}$")) || archiveSize < 0L) return null
            if (archive.length() != archiveSize) return null

            InstalledLanguagePackage(
                manifest = manifest,
                installationDirectory = directory,
                archiveFile = archive,
                contentDirectory = content,
                archiveSha256 = archiveHash,
                archiveSizeBytes = archiveSize,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun destinationFor(packageId: String, version: String): File {
        require(packageId.matches(Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)+$"))) {
            "Invalid package ID."
        }
        require(version.matches(Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$",
        ))) {
            "Invalid package version."
        }

        val packageDirectory = safeDirectChild(packagesDirectory, packageId)
        return safeDirectChild(packageDirectory, version)
    }

    private fun safeDirectChild(parent: File, childName: String): File {
        require(childName.isNotBlank() && childName != "." && childName != "..") {
            "Invalid child path."
        }
        require(!childName.contains('/') && !childName.contains('\\')) {
            "Child path must be a single segment."
        }

        val child = File(parent, childName)
        val canonicalParent = parent.canonicalFile
        val canonicalChild = child.canonicalFile
        require(canonicalChild.parentFile == canonicalParent) {
            "Child path escapes its parent directory."
        }
        return child
    }

    private fun safeContentFile(contentDirectory: File, relativePath: String): File {
        val target = File(contentDirectory, relativePath)
        val canonicalRoot = contentDirectory.canonicalFile
        val canonicalTarget = target.canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        require(canonicalTarget.path.startsWith(rootPrefix)) {
            "Archive path escapes the package content directory."
        }
        return canonicalTarget
    }

    private fun ByteArray.toHexString(): String {
        val digits = "0123456789abcdef"
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = digits[value ushr 4]
            chars[index * 2 + 1] = digits[value and 0x0f]
        }
        return chars.concatToString()
    }

    private class ArchiveSizeLimitExceededException(message: String) : Exception(message)
}
