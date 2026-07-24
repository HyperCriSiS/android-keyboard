package org.futo.inputmethod.latin.languagepack

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LanguagePackageManifestCodecTest {
    @Test
    fun extensionFieldsAreIgnoredAtEveryLevel() {
        val source = """
            {
              "formatVersion": "0.1",
              "x-root": { "future": true },
              "package": {
                "id": "org.futo.community.german.profile",
                "name": "German Profile",
                "version": "1.0.0",
                "kind": "profile",
                "authors": [
                  {
                    "name": "FUTO Community",
                    "x-author": "future metadata"
                  }
                ],
                "license": "Apache-2.0",
                "languages": ["de"],
                "x-package": 42
              },
              "components": [],
              "profiles": [
                {
                  "id": "standard",
                  "name": "Standard",
                  "selections": [],
                  "x-profile": false
                }
              ]
            }
        """.trimIndent()

        val manifest = LanguagePackageManifestCodec.decode(source)

        assertEquals("org.futo.community.german.profile", manifest.packageInfo.id)
        assertEquals("standard", manifest.profiles.single().id)
    }

    @Test
    fun unknownNonExtensionFieldIsRejected() {
        val source = """
            {
              "formatVersion": "0.1",
              "package": {
                "id": "org.futo.community.german.profile",
                "name": "German Profile",
                "version": "1.0.0",
                "kind": "profile",
                "authors": [{ "name": "FUTO Community" }],
                "license": "Apache-2.0",
                "languages": ["de"],
                "unexpected": true
              },
              "components": [],
              "profiles": []
            }
        """.trimIndent()

        try {
            LanguagePackageManifestCodec.decode(source)
            fail("Unknown non-extension field should have failed strict decoding")
        } catch (_: SerializationException) {
            // Expected.
        }
    }
}
