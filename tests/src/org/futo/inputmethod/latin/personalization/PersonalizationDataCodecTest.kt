package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PersonalizationDataCodecTest {
    @Test
    fun extensionFieldsAreIgnoredRecursively() {
        val source = """
            {
              "formatVersion": "0.1",
              "x-root": { "future": true },
              "manualWords": [
                {
                  "id": "11111111-1111-4111-8111-111111111111",
                  "revision": 1,
                  "createdAt": 1000,
                  "updatedAt": 1000,
                  "locale": "de-DE",
                  "word": "FUTO",
                  "frequency": 250,
                  "source": "manual",
                  "x-record": "future metadata"
                }
              ],
              "learnedWords": [],
              "learnedNgrams": [],
              "wordRules": [],
              "correctionRules": [],
              "tombstones": []
            }
        """.trimIndent()

        val data = PersonalizationDataCodec.decodeData(source)

        assertEquals("FUTO", data.manualWords.single().word)
    }

    @Test
    fun unknownNonExtensionFieldIsRejected() {
        val source = """
            {
              "formatVersion": "0.1",
              "manualWords": [],
              "learnedWords": [],
              "learnedNgrams": [],
              "wordRules": [],
              "correctionRules": [],
              "tombstones": [],
              "unexpected": true
            }
        """.trimIndent()

        try {
            PersonalizationDataCodec.decodeData(source)
            fail("Unknown non-extension field should have failed strict decoding")
        } catch (_: SerializationException) {
            // Expected.
        }
    }

    @Test
    fun manifestAndDataRoundTrip() {
        val data = PersonalizationTestFixtures.validData()
        val bytes = PersonalizationDataCodec.encodeData(data).toByteArray(Charsets.UTF_8)
        val manifest = PersonalizationTestFixtures.validManifest(data, bytes)

        val decodedData = PersonalizationDataCodec.decodeData(
            PersonalizationDataCodec.encodeData(data),
        )
        val decodedManifest = PersonalizationDataCodec.decodeManifest(
            PersonalizationDataCodec.encodeManifest(manifest),
        )

        assertEquals(data, decodedData)
        assertEquals(manifest, decodedManifest)
    }

    @Test
    fun encoderWritesFieldsRequiredByNormativeSchemas() {
        val data = PersonalizationDataSet(formatVersion = PERSONALIZATION_FORMAT_VERSION)
        val dataBytes = PersonalizationDataCodec.encodeData(data).toByteArray(Charsets.UTF_8)
        val manifest = PersonalizationTestFixtures.validManifest(data, dataBytes)
        val encodedData = dataBytes.toString(Charsets.UTF_8)
        val encodedManifest = PersonalizationDataCodec.encodeManifest(manifest)

        listOf(
            "\"manualWords\": []",
            "\"learnedWords\": []",
            "\"learnedNgrams\": []",
            "\"wordRules\": []",
            "\"correctionRules\": []",
            "\"tombstones\": []",
        ).forEach { requiredField ->
            assertTrue("Missing required data field $requiredField", encodedData.contains(requiredField))
        }
        assertTrue(encodedManifest.contains("\"path\": \"data.json\""))
        assertTrue(encodedManifest.contains("\"mediaType\": \"$PERSONALIZATION_DATA_MEDIA_TYPE\""))
        assertTrue(encodedManifest.contains("\"containsSentenceText\": false"))
    }
}
