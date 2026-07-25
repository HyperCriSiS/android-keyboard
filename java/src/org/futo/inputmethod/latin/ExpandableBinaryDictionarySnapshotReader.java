/*
 * Copyright (C) 2026 FUTO
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.futo.inputmethod.latin;

import org.futo.inputmethod.latin.makedict.WordProperty;
import org.futo.inputmethod.latin.utils.ExecutorUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Creates a bounded read-only snapshot of an {@link ExpandableBinaryDictionary}.
 *
 * <p>All mutable dictionary operations are queued on the single-threaded
 * {@link ExecutorUtils#KEYBOARD} executor. This reader uses the same queue, so a traversal runs
 * after previously queued mutations and before later mutations. It never exposes the native
 * dictionary handle and does not modify or flush the dictionary.</p>
 *
 * <p>The returned {@link WordProperty} objects are detached Java values produced by the existing
 * native traversal API. Mapping or rendering them should happen after the callback returns rather
 * than by retaining a reference to the live dictionary.</p>
 */
public final class ExpandableBinaryDictionarySnapshotReader {
    private static final int MAX_ALLOWED_ENTRIES = 1_000_000;

    private ExpandableBinaryDictionarySnapshotReader() {}

    public enum Status {
        COMPLETE,
        TRUNCATED,
        CANCELLED,
        UNAVAILABLE,
        CORRUPTED,
        FAILED
    }

    public static final class Snapshot {
        @Nonnull public final Status status;
        @Nonnull public final ArrayList<WordProperty> wordProperties;
        @Nullable public final String message;
        public final int traversedEntries;

        private Snapshot(@Nonnull final Status status,
                @Nonnull final ArrayList<WordProperty> wordProperties,
                @Nullable final String message, final int traversedEntries) {
            this.status = status;
            this.wordProperties = wordProperties;
            this.message = message;
            this.traversedEntries = traversedEntries;
        }

        public boolean isComplete() {
            return status == Status.COMPLETE;
        }

        public boolean isUsable() {
            return status == Status.COMPLETE || status == Status.TRUNCATED;
        }
    }

    public interface Callback {
        void onSnapshotReady(@Nonnull Snapshot snapshot);
    }

    /**
     * Queues a dictionary snapshot.
     *
     * @param dictionary dictionary to inspect
     * @param maxEntries maximum number of valid word properties returned
     * @param cancellation optional cancellation flag checked between traversal steps
     * @param callback invoked once on the keyboard executor
     */
    public static void readAsync(@Nonnull final ExpandableBinaryDictionary dictionary,
            final int maxEntries, @Nullable final AtomicBoolean cancellation,
            @Nonnull final Callback callback) {
        if (maxEntries <= 0 || maxEntries > MAX_ALLOWED_ENTRIES) {
            throw new IllegalArgumentException(
                    "maxEntries must be between 1 and " + MAX_ALLOWED_ENTRIES + ".");
        }

        // If loading is required, its task is queued before our snapshot task on the same executor.
        dictionary.reloadDictionaryIfRequired();
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute(new Runnable() {
            @Override
            public void run() {
                callback.onSnapshotReady(readQueued(dictionary, maxEntries, cancellation));
            }
        });
    }

    @Nonnull
    private static Snapshot readQueued(@Nonnull final ExpandableBinaryDictionary dictionary,
            final int maxEntries, @Nullable final AtomicBoolean cancellation) {
        final BinaryDictionary binaryDictionary = dictionary.getBinaryDictionary();
        if (binaryDictionary == null || !binaryDictionary.isValidDictionary()) {
            return new Snapshot(Status.UNAVAILABLE, new ArrayList<WordProperty>(),
                    "Dictionary is not currently available.", 0);
        }

        final ArrayList<WordProperty> properties = new ArrayList<>();
        final Set<Integer> visitedTokens = new HashSet<>();
        int token = 0;
        int traversedEntries = 0;

        try {
            do {
                if (cancellation != null && cancellation.get()) {
                    return new Snapshot(Status.CANCELLED, properties,
                            "Snapshot was cancelled.", traversedEntries);
                }
                if (!visitedTokens.add(token)) {
                    return new Snapshot(Status.CORRUPTED, properties,
                            "Dictionary traversal repeated a token.", traversedEntries);
                }

                final BinaryDictionary.GetNextWordPropertyResult result =
                        binaryDictionary.getNextWordProperty(token);
                if (result == null || result.mWordProperty == null) {
                    return new Snapshot(Status.CORRUPTED, properties,
                            "Dictionary traversal returned an invalid word property.",
                            traversedEntries);
                }

                traversedEntries++;
                final WordProperty property = result.mWordProperty;
                if (property.isValid() && !property.mWord.isEmpty()
                        && !property.mIsBeginningOfSentence) {
                    if (properties.size() >= maxEntries) {
                        return new Snapshot(Status.TRUNCATED, properties,
                                "Snapshot reached the configured entry limit.", traversedEntries);
                    }
                    properties.add(property);
                }

                token = result.mNextToken;
            } while (token != 0);

            return new Snapshot(Status.COMPLETE, properties, null, traversedEntries);
        } catch (final RuntimeException exception) {
            return new Snapshot(Status.FAILED, properties,
                    exception.getMessage() != null
                            ? exception.getMessage()
                            : exception.getClass().getSimpleName(),
                    traversedEntries);
        }
    }
}
