package org.futo.inputmethod.latin.languagepack.benchmark

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CorrectionBenchmarkCodecTest {
    @Test
    fun extensionFieldsAreIgnoredRecursively() {
        val source = """
            {
              "formatVersion": "0.1",
              "id": "org.futo.test.benchmark",
              "name": "Test",
              "languageTags": ["de"],
              "license": "CC0-1.0",
              "x-suite": {"future": true},
              "cases": [
                {
                  "id": "keep-word",
                  "languageTag": "de",
                  "layout": "qwertz",
                  "leftContext": "Ein",
                  "typedText": " Wort",
                  "expectation": {
                    "action": "keep",
                    "acceptableTexts": [" Wort"],
                    "x-expectation": 1
                  },
                  "candidates": [
                    {
                      "id": "typed",
                      "text": " Wort",
                      "isTypedText": true,
                      "generatorConfidence": 1.0,
                      "editRisk": 0.0,
                      "x-candidate": "future"
                    }
                  ],
                  "x-case": false
                }
              ]
            }
        """.trimIndent()

        val suite = CorrectionBenchmarkCodec.decode(source)

        assertEquals("org.futo.test.benchmark", suite.id)
        assertEquals("keep-word", suite.cases.single().id)
    }

    @Test
    fun unknownNonExtensionFieldIsRejected() {
        val source = """
            {
              "formatVersion": "0.1",
              "id": "org.futo.test.benchmark",
              "name": "Test",
              "languageTags": ["de"],
              "license": "CC0-1.0",
              "unknown": true,
              "cases": []
            }
        """.trimIndent()

        try {
            CorrectionBenchmarkCodec.decode(source)
            fail("Unknown non-extension field should fail strict decoding")
        } catch (_: SerializationException) {
            // Expected.
        }
    }
}
