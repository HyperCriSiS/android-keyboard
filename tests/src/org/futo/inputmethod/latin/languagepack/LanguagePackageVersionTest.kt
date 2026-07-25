package org.futo.inputmethod.latin.languagepack

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LanguagePackageVersionTest {
    @Test
    fun semanticVersionPrecedenceFollowsSemver() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
        ).map { requireNotNull(LanguagePackageSemanticVersion.parse(it)) }

        ordered.zipWithNext().forEach { (lower, higher) ->
            assertTrue("Expected $lower < $higher", lower < higher)
        }
    }

    @Test
    fun buildMetadataDoesNotChangePrecedence() {
        val first = requireNotNull(LanguagePackageSemanticVersion.parse("1.2.3+build.1"))
        val second = requireNotNull(LanguagePackageSemanticVersion.parse("1.2.3+build.9"))

        assertTrue(first.compareTo(second) == 0)
    }

    @Test
    fun comparatorRangeMatchesExpectedVersions() {
        val range = requireNotNull(LanguagePackageVersionRange.parse(">=1.2.0 <2.0.0"))

        assertFalse(range.contains(version("1.1.9")))
        assertTrue(range.contains(version("1.2.0")))
        assertTrue(range.contains(version("1.9.9")))
        assertFalse(range.contains(version("2.0.0")))
    }

    @Test
    fun caretAndTildeRangesAreSupported() {
        val caret = requireNotNull(LanguagePackageVersionRange.parse("^1.2.3"))
        assertTrue(caret.contains(version("1.9.0")))
        assertFalse(caret.contains(version("2.0.0")))

        val zeroCaret = requireNotNull(LanguagePackageVersionRange.parse("^0.2.3"))
        assertTrue(zeroCaret.contains(version("0.2.9")))
        assertFalse(zeroCaret.contains(version("0.3.0")))

        val tilde = requireNotNull(LanguagePackageVersionRange.parse("~1.2.3"))
        assertTrue(tilde.contains(version("1.2.99")))
        assertFalse(tilde.contains(version("1.3.0")))
    }

    @Test
    fun unsupportedOrRangeAndInvalidPrereleaseAreRejected() {
        assertNull(LanguagePackageVersionRange.parse(">=1.0.0 || <2.0.0"))
        assertNull(LanguagePackageSemanticVersion.parse("1.0.0-alpha.01"))
    }

    private fun version(value: String): LanguagePackageSemanticVersion {
        return requireNotNull(LanguagePackageSemanticVersion.parse(value))
    }
}
