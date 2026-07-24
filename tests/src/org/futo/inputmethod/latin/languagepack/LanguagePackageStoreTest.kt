package org.futo.inputmethod.latin.languagepack

import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LanguagePackageStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(
            InstrumentationRegistry.getTargetContext().cacheDir,
            "language-package-store-${UUID.randomUUID()}",
        )
        assertTrue(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun validPackageIsInstalledAndCanBeListed() {
        val payload = "German dictionary payload".toByteArray()
        val archive = buildArchive(manifestForPayload(payload), payload)
        val store = LanguagePackageStore(root)

        val result = store.install(ByteArrayInputStream(archive))

        assertTrue(result is LanguagePackageInstallResult.Installed)
        val installed = (result as LanguagePackageInstallResult.Installed).packageInfo
        assertEquals("org.futo.test.german.dictionary", installed.manifest.packageInfo.id)
        assertEquals("1.0.0", installed.manifest.packageInfo.version)
        assertTrue(installed.archiveFile.isFile)
        assertTrue(installed.contentDirectory.isDirectory)
        assertArrayEquals(
            payload,
            File(installed.contentDirectory, "components/dictionary.dict").readBytes(),
        )

        val listed = store.listInstalled()
        assertEquals(1, listed.size)
        assertEquals(installed.installationDirectory.canonicalFile, listed.single().installationDirectory.canonicalFile)
    }

    @Test
    fun installingIdenticalArchiveAgainIsIdempotent() {
        val payload = "German dictionary payload".toByteArray()
        val archive = buildArchive(manifestForPayload(payload), payload)
        val store = LanguagePackageStore(root)

        assertTrue(store.install(ByteArrayInputStream(archive)) is LanguagePackageInstallResult.Installed)
        val second = store.install(ByteArrayInputStream(archive))

        assertTrue(second is LanguagePackageInstallResult.AlreadyInstalled)
        assertEquals(1, store.listInstalled().size)
    }

    @Test
    fun samePackageVersionWithDifferentArchiveIsConflict() {
        val firstPayload = "First payload".toByteArray()
        val secondPayload = "Second payload".toByteArray()
        val store = LanguagePackageStore(root)

        val firstArchive = buildArchive(manifestForPayload(firstPayload), firstPayload)
        val secondArchive = buildArchive(manifestForPayload(secondPayload), secondPayload)

        assertTrue(store.install(ByteArrayInputStream(firstArchive)) is LanguagePackageInstallResult.Installed)
        val second = store.install(ByteArrayInputStream(secondArchive))

        assertTrue(second is LanguagePackageInstallResult.Conflict)
        assertEquals(1, store.listInstalled().size)
        assertArrayEquals(
            firstPayload,
            File(store.listInstalled().single().contentDirectory, "components/dictionary.dict").readBytes(),
        )
    }

    @Test
    fun invalidPackageIsNotInstalled() {
        val payload = "German dictionary payload".toByteArray()
        val validManifest = manifestForPayload(payload)
        val invalidManifest = validManifest.copy(
            components = listOf(
                validManifest.components.single().copy(
                    payload = validManifest.components.single().payload.copy(
                        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                    ),
                ),
            ),
        )
        val store = LanguagePackageStore(root)

        val result = store.install(
            ByteArrayInputStream(buildArchive(invalidManifest, payload)),
        )

        assertTrue(result is LanguagePackageInstallResult.Invalid)
        assertTrue((result as LanguagePackageInstallResult.Invalid).inspection.issues.any {
            it.code == "payload_hash_mismatch"
        })
        assertTrue(store.listInstalled().isEmpty())
        assertFalse(File(root, ".staging").listFiles().orEmpty().any())
    }

    @Test
    fun compressedArchiveSizeLimitIsAppliedBeforeValidation() {
        val payload = ByteArray(2048) { 1 }
        val archive = buildArchive(manifestForPayload(payload), payload)
        val store = LanguagePackageStore(
            rootDirectory = root,
            limits = LanguagePackageStoreLimits(maxArchiveBytes = 32),
        )

        val result = store.install(ByteArrayInputStream(archive))

        assertTrue(result is LanguagePackageInstallResult.Failed)
        assertTrue(store.listInstalled().isEmpty())
        assertFalse(File(root, ".staging").listFiles().orEmpty().any())
    }

    private fun manifestForPayload(payload: ByteArray): LanguagePackageManifest {
        return LanguagePackageManifest(
            formatVersion = CURRENT_LANGUAGE_PACKAGE_FORMAT_VERSION,
            packageInfo = LanguagePackageInfo(
                id = "org.futo.test.german.dictionary",
                name = "German Dictionary",
                version = "1.0.0",
                kind = LanguagePackageKind.Component,
                authors = listOf(LanguagePackageAuthor(name = "FUTO Test")),
                license = "Apache-2.0",
                languages = listOf("de"),
            ),
            components = listOf(
                LanguagePackageComponent(
                    id = "dictionary",
                    name = "German Dictionary",
                    version = "1.0.0",
                    kind = LanguagePackageComponentKind.Dictionary,
                    activation = LanguagePackageActivation.Stackable,
                    languages = listOf("de"),
                    tasks = listOf("dictionary-lookup-v1"),
                    payload = LanguagePackagePayload(
                        path = "components/dictionary.dict",
                        mediaType = "application/vnd.futo.keyboard.dictionary",
                        sha256 = sha256(payload),
                        sizeBytes = payload.size.toLong(),
                    ),
                ),
            ),
        )
    }

    private fun buildArchive(
        manifest: LanguagePackageManifest,
        payload: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(LanguagePackageManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("components/dictionary.dict"))
            zip.write(payload)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
