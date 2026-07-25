package org.futo.inputmethod.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.futo.inputmethod.latin.ExpandableBinaryDictionarySnapshotReader.Snapshot;
import org.futo.inputmethod.latin.ExpandableBinaryDictionarySnapshotReader.Status;
import org.futo.inputmethod.latin.common.FileUtils;
import org.futo.inputmethod.latin.makedict.WordProperty;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class ExpandableBinaryDictionarySnapshotReaderTest {
    private static final long WAIT_SECONDS = 10;

    private File mDictionaryFile;
    private TestExpandableDictionary mDictionary;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getTargetContext();
        mDictionaryFile = new File(context.getCacheDir(),
                "personalization-snapshot-" + System.nanoTime());
        mDictionary = new TestExpandableDictionary(context, mDictionaryFile);
    }

    @After
    public void tearDown() throws Exception {
        if (mDictionary != null) {
            mDictionary.close();
        }
        // close() is queued on the same executor; a snapshot acts as a barrier before deletion.
        awaitSnapshot(1, null);
        if (mDictionaryFile != null) {
            FileUtils.deleteRecursively(mDictionaryFile);
        }
    }

    @Test
    public void queuedSnapshotObservesEarlierMutations() throws Exception {
        mDictionary.addUnigramEntry("wahrscheinlich", 210, null, 0,
                false, false, 1000);
        mDictionary.addUnigramEntry("FUTO", 190, null, 0,
                false, false, 1001);

        final Snapshot snapshot = awaitSnapshot(10, null);

        assertEquals(Status.COMPLETE, snapshot.status);
        assertTrue(snapshot.isUsable());
        assertTrue(snapshot.isComplete());
        final ArrayList<String> words = new ArrayList<>();
        for (final WordProperty property : snapshot.wordProperties) {
            words.add(property.mWord);
        }
        assertTrue(words.contains("wahrscheinlich"));
        assertTrue(words.contains("FUTO"));
    }

    @Test
    public void entryLimitProducesExplicitTruncation() throws Exception {
        mDictionary.addUnigramEntry("eins", 180, null, 0,
                false, false, 1000);
        mDictionary.addUnigramEntry("zwei", 180, null, 0,
                false, false, 1001);

        final Snapshot snapshot = awaitSnapshot(1, null);

        assertEquals(Status.TRUNCATED, snapshot.status);
        assertTrue(snapshot.isUsable());
        assertFalse(snapshot.isComplete());
        assertEquals(1, snapshot.wordProperties.size());
        assertNotNull(snapshot.message);
    }

    @Test
    public void cancellationIsReportedWithoutPretendingSnapshotIsComplete() throws Exception {
        mDictionary.addUnigramEntry("eins", 180, null, 0,
                false, false, 1000);
        final AtomicBoolean cancelled = new AtomicBoolean(true);

        final Snapshot snapshot = awaitSnapshot(10, cancelled);

        assertEquals(Status.CANCELLED, snapshot.status);
        assertFalse(snapshot.isUsable());
        assertFalse(snapshot.isComplete());
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroEntryLimitIsRejected() {
        ExpandableBinaryDictionarySnapshotReader.readAsync(
                mDictionary, 0, null, snapshot -> { });
    }

    private Snapshot awaitSnapshot(final int maxEntries,
            final AtomicBoolean cancellation) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Snapshot> result = new AtomicReference<>();
        ExpandableBinaryDictionarySnapshotReader.readAsync(
                mDictionary,
                maxEntries,
                cancellation,
                snapshot -> {
                    result.set(snapshot);
                    latch.countDown();
                });
        assertTrue("Snapshot callback timed out.", latch.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertNotNull(result.get());
        return result.get();
    }

    private static final class TestExpandableDictionary extends ExpandableBinaryDictionary {
        TestExpandableDictionary(final Context context, final File dictionaryFile) {
            super(context, "SnapshotTest", Locale.GERMAN,
                    Dictionary.TYPE_USER_HISTORY, dictionaryFile);
            reloadDictionaryIfRequired();
        }

        @Override
        protected void loadInitialContentsLocked() {
            // Empty by design.
        }

        @Override
        public boolean isValidWord(final String word) {
            return false;
        }
    }
}
