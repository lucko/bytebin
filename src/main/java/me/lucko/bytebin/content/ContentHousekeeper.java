/*
 * This file is part of bytebin, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.bytebin.content;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import io.prometheus.client.Gauge;

import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Housekeeper for content expiry and metrics collection
 */
final class ContentHousekeeper {

    /** The number of slices to use when performing housekeeping/metrics collection. */
    public static final int SLICE_AMOUNT = 30;

    private static final Gauge STORED_CONTENT_GAUGE = Gauge.build()
            .name("bytebin_content")
            .help("The amount of stored content")
            .labelNames("type")
            .register();

    /**
     * Split the content storage directory into "slices".
     * Process metrics separately on each slice to spread the load.
     */
    private final Slice[] slices;

    /**
     * A counter holding the index of the next slice to be processed.
     */
    private final AtomicInteger nextSlice = new AtomicInteger(0);

    /**
     * The content types that have been seen by bytebin since the process started.
     * Used to ensure that metrics are zeroed when content is deleted.
     */
    private final Set<String> seenContentTypes = ConcurrentHashMap.newKeySet();

    /**
     * Flag to hold if the first run has yet to occur.
     */
    private boolean firstRun = true;

    ContentHousekeeper() {
        this.slices = new Slice[SLICE_AMOUNT];
        for (int i = 0; i < this.slices.length; i++) {
            this.slices[i] = new Slice(i);
        }
    }

    /**
     * Get the slices that should be processed.
     *
     * @return the slices to process
     */
    public Iterable<Slice> getSlicesToProcess() {
        if (this.firstRun) {
            // on the first run, process all slices
            // this is so that the metrics gauge is accurate from the start
            this.firstRun = false;
            return Collections.unmodifiableList(Arrays.asList(this.slices));
        }

        // otherwise, just return a single slice based on the nextSlice counter
        int idx = this.nextSlice.updateAndGet(i -> (i + 1) % SLICE_AMOUNT);
        Slice slice = this.slices[idx];
        return Collections.singleton(slice);
    }

    /**
     * Update the metrics gauge based on all slices
     */
    public void updateMetrics() {
        Map<String, Integer> sum = new HashMap<>();
        for (Slice slice : this.slices) {
            slice.counts.forEach((type, count) -> sum.merge(type, count.get(), Integer::sum));
        }
        sum.forEach((contentType, count) -> STORED_CONTENT_GAUGE.labels(contentType).set(count));
    }

    /**
     * Represents a slice of the stored content.
     */
    public final class Slice implements DirectoryStream.Filter<Path> {
        private final int idx;
        private Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        private Slice(int idx) {
            this.idx = idx;
        }

        /**
         * Resets the counts map ready to be recalculated.
         */
        public void begin() {
            this.counts = new ConcurrentHashMap<>();
            for (String contentType : ContentHousekeeper.this.seenContentTypes) {
                this.counts.put(contentType, new AtomicInteger());
            }
        }

        /**
         * Record an entry for a given content type.
         *
         * @param contentType the content type
         */
        public void record(String contentType) {
            this.counts.computeIfAbsent(contentType, x -> new AtomicInteger()).incrementAndGet();
        }

        /**
         * Record the seen content types in the global set
         */
        public void done() {
            ContentHousekeeper.this.seenContentTypes.addAll(this.counts.keySet());
        }

        @SuppressWarnings("UnstableApiUsage")
        @Override
        public boolean accept(Path entry) {
            // batch files in the directory based on the hashcode of their name/id.
            // this means that a *roughly* equal number of files should be processed on each slice
            int hash = Hashing.murmur3_32_fixed().hashUnencodedChars(entry.getFileName().toString()).asInt();
            int mask = SLICE_AMOUNT - 1;
            return (hash & mask) == this.idx;
        }

        @Override
        public String toString() {
            return "slice " + this.idx;
        }
    }
}
