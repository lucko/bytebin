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

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import me.lucko.bytebin.content.storage.StorageBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * Coordinates the storage of content in a storage backend.
 */
public class ContentStorageHandler implements CacheLoader<String, Content> {

    private static final Logger LOGGER = LogManager.getLogger(ContentStorageHandler.class);

    private static final Counter READ_FROM_BACKEND_COUNTER = Counter.build()
            .name("bytebin_backend_read_total")
            .labelNames("backend")
            .help("Counts the number of cache-misses when loading content")
            .register();

    private static final Counter WRITE_TO_BACKEND_COUNTER = Counter.build()
            .name("bytebin_backend_write_total")
            .labelNames("backend")
            .help("Counts the number of times content was written to the backend")
            .register();

    private static final Counter DELETE_FROM_BACKEND_COUNTER = Counter.build()
            .name("bytebin_backend_delete_total")
            .labelNames("backend")
            .help("Counts the number of times content was deleted from the backend")
            .register();

    private static final Histogram READ_FROM_BACKEND_HISTOGRAM = Histogram.build()
            .name("bytebin_backend_read_duration_seconds")
            .buckets(
                    0.001, // 1 ms
                    0.002, // 2 ms
                    0.005, // 5 ms
                    0.01,  // 10 ms
                    0.025, // 25 ms
                    0.05,  // 50 ms
                    0.1,   // 100 ms
                    0.25,  // 250 ms
                    0.5,   // 500 ms
                    1,     // 1 s
                    2,     // 2 s
                    5,     // 5 s
                    10     // 10 s
            )
            .help("The duration to read from the backend")
            .labelNames("backend")
            .register();

    private static final Histogram WRITE_TO_BACKEND_HISTOGRAM = Histogram.build()
            .name("bytebin_backend_write_duration_seconds")
            .buckets(
                    0.001, // 1 ms
                    0.002, // 2 ms
                    0.005, // 5 ms
                    0.01,  // 10 ms
                    0.025, // 25 ms
                    0.05,  // 50 ms
                    0.1,   // 100 ms
                    0.25,  // 250 ms
                    0.5,   // 500 ms
                    1,     // 1 s
                    2,     // 2 s
                    5,     // 5 s
                    10     // 10 s
            )
            .help("The duration to write to the backend")
            .labelNames("backend")
            .register();

    private static final Histogram DELETE_FROM_BACKEND_HISTOGRAM = Histogram.build()
            .name("bytebin_backend_delete_duration_seconds")
            .buckets(
                    0.001, // 1 ms
                    0.002, // 2 ms
                    0.005, // 5 ms
                    0.01,  // 10 ms
                    0.025, // 25 ms
                    0.05,  // 50 ms
                    0.1,   // 100 ms
                    0.25,  // 250 ms
                    0.5,   // 500 ms
                    1,     // 1 s
                    2,     // 2 s
                    5,     // 5 s
                    10     // 10 s
            )
            .help("The duration to delete from the backend")
            .labelNames("backend")
            .register();

    private static final Counter BACKEND_ERROR_COUNTER = Counter.build()
            .name("bytebin_backend_error_total")
            .labelNames("backend", "operation")
            .help("Counts the number of errors that have occurred when interacting with the backend")
            .register();

    /** An index of all stored content */
    private final ContentIndexDatabase index;

    /** The backends in use for content storage */
    private final Map<String, StorageBackend> backends;

    /** The function used to select which backend to use for content storage */
    private final StorageBackendSelector backendSelector;

    /** The executor to use for i/o */
    private final ScheduledExecutorService executor;

    public ContentStorageHandler(ContentIndexDatabase contentIndex, Collection<StorageBackend> backends, StorageBackendSelector backendSelector, ScheduledExecutorService executor) {
        this.index = contentIndex;
        this.backends = backends.stream().collect(ImmutableMap.toImmutableMap(
                StorageBackend::getBackendId, Function.identity()
        ));
        this.backendSelector = backendSelector;
        this.executor = executor;
    }

    /**
     * Load content.
     *
     * @param key the key to load
     * @return the loaded content
     */
    @Override
    public Content load(String key) {
        // query the index to see if content with this key is stored
        Content metadata = this.index.get(key);
        if (metadata == null) {
            return Content.EMPTY_CONTENT;
        }

        // find the backend that the content is stored in
        String backendId = metadata.getBackendId();

        StorageBackend backend = this.backends.get(backendId);
        if (backend == null) {
            LOGGER.error("Unable to load " + key + " - no such backend '" + backendId + "'");
            BACKEND_ERROR_COUNTER.labels(backendId, "load").inc();
            return Content.EMPTY_CONTENT;
        }

        // increment the read counter
        READ_FROM_BACKEND_COUNTER.labels(backendId).inc();
        LOGGER.info("[STORAGE] Loading '" + key + "' from the '" + backendId + "' backend");

        // load the content from the backend
        try (Histogram.Timer ignored = READ_FROM_BACKEND_HISTOGRAM.labels(backendId).startTimer()) {
            Content content = backend.load(key);
            if (content != null) {
                return content;
            }
        } catch (Exception e) {
            LOGGER.warn("[STORAGE] Unable to load '" + key + "' from the '" + backendId + "' backend", e);
            BACKEND_ERROR_COUNTER.labels(backendId, "load").inc();
        }

        return Content.EMPTY_CONTENT;
    }

    /**
     * Save content.
     *
     * @param content the content to save
     */
    public void save(Content content) {
        // select a backend to store the content in
        StorageBackend backend = this.backendSelector.select(content);
        String backendId = backend.getBackendId();

        // record which backend the content is going to be stored in, and write to the index
        content.setBackendId(backend.getBackendId());
        this.index.put(content);

        // increment the write counter
        WRITE_TO_BACKEND_COUNTER.labels(backendId).inc();

        // save to the backend
        try (Histogram.Timer ignored = WRITE_TO_BACKEND_HISTOGRAM.labels(backendId).startTimer()) {
            backend.save(content);
        } catch (Exception e) {
            LOGGER.warn("[STORAGE] Unable to save '" + content.getKey() + "' to the '" + backendId + "' backend", e);
            BACKEND_ERROR_COUNTER.labels(backendId, "save").inc();
        }
    }

    /**
     * Delete content.
     *
     * @param content the content to delete
     */
    public void delete(Content content) {
        String key = content.getKey();

        // find the backend that the content is stored in
        String backendId = content.getBackendId();
        StorageBackend backend = this.backends.get(backendId);
        if (backend == null) {
            LOGGER.error("[STORAGE] Unable to delete " + key + " - no such backend '" + backendId + "'");
            BACKEND_ERROR_COUNTER.labels(backendId, "delete").inc();
            return;
        }

        DELETE_FROM_BACKEND_COUNTER.labels(backendId).inc();

        // delete the data from the backend
        try (Histogram.Timer ignored = DELETE_FROM_BACKEND_HISTOGRAM.labels(backendId).startTimer()) {
            backend.delete(key);
        } catch (Exception e) {
            LOGGER.warn("[STORAGE] Unable to delete '" + key + "' from the '" + backend.getBackendId() + "' backend", e);
            BACKEND_ERROR_COUNTER.labels(backendId, "delete").inc();
        }

        // remove the entry from the index
        this.index.remove(key);

        LOGGER.info("[STORAGE] Deleted '" + key + "' from the '" + backendId + "' backend");
    }

    /**
     * Invalidates/deletes any expired content and updates the metrics gauges
     */
    public void runInvalidationAndRecordMetrics() {
        // query the index for content which has expired
        Collection<Content> expired = this.index.getExpired();

        for (Content metadata : expired) {
            delete(metadata);
        }

        // update metrics
        this.index.recordMetrics();
    }

    /**
     * Bulk deletes the provided keys
     *
     * @param keys the keys to delete
     * @param force whether to sill attempt deletion if the content is not in the index
     * @return how many entries were actually deleted
     */
    public int bulkDelete(List<String> keys, boolean force) {
        int count = 0;
        for (String key : keys) {
            Content content = this.index.get(key);
            if (content == null) {
                if (force) {
                    for (StorageBackend backend : this.backends.values()) {
                        try {
                            backend.delete(key);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
                continue;
            }

            delete(content);
            count++;
        }

        // update metrics
        this.index.recordMetrics();

        return count;
    }

    public Executor getExecutor() {
        return this.executor;
    }
}
