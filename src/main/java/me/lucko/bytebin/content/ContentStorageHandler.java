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

import me.lucko.bytebin.content.storage.StorageBackend;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.NonNull;

import io.prometheus.client.Counter;

import java.util.Collection;
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

    /** An index of all stored content */
    private final ContentIndexDatabase index;

    /** The backends in use for content storage */
    private final Map<String, StorageBackend> backends;

    /** The executor to use for i/o */
    private final ScheduledExecutorService executor;

    public ContentStorageHandler(ContentIndexDatabase contentIndex, Collection<StorageBackend> backends, ScheduledExecutorService executor) {
        this.index = contentIndex;
        this.backends = backends.stream().collect(ImmutableMap.toImmutableMap(
                StorageBackend::getBackendId, Function.identity()
        ));
        this.executor = executor;
    }

    /**
     * Load content.
     *
     * @param key the key to load
     * @return the loaded content
     */
    @Override
    public @NonNull Content load(String key) {
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
            return Content.EMPTY_CONTENT;
        }

        // increment the read counter
        READ_FROM_BACKEND_COUNTER.labels(backendId).inc();
        LOGGER.info("[STORAGE] Loading '" + key + "' from the '" + backendId + "' backend");

        // load the content from the backend
        try {
            Content content = backend.load(key);
            if (content != null) {
                return content;
            }
        } catch (Exception e) {
            LOGGER.warn("[STORAGE] Unable to load '" + key + "' from the '" + backendId + "' backend", e);
        }

        return Content.EMPTY_CONTENT;
    }

    /**
     * Select the backend to store the given content in.
     *
     * @param content the content to store
     * @return the selected backend
     */
    public StorageBackend selectBackend(Content content) {
        StorageBackend backend = this.backends.get("local");
        return backend;
    }

    /**
     * Save content.
     *
     * @param content the content to save
     */
    public void save(Content content) {
        // select a backend to store the content in
        StorageBackend backend = selectBackend(content);
        String backendId = backend.getBackendId();

        // record which backend the content is going to be stored in, and write to the index
        content.setBackendId(backend.getBackendId());
        this.index.put(content);

        // increment the write counter
        WRITE_TO_BACKEND_COUNTER.labels(backendId).inc();

        // save to the backend
        try {
            backend.save(content);
        } catch (Exception e) {
            LOGGER.warn("[STORAGE] Unable to save '" + content.getKey() + "' to the '" + backendId + "' backend", e);
        }
    }

    /**
     * Invalidates/deletes any expired content and updates the metrics gauges
     */
    public void runInvalidationAndRecordMetrics() {
        // query the index for content which has expired
        Collection<Content> expired = this.index.getExpired();

        for (Content metadata : expired) {
            String key = metadata.getKey();

            // find the backend that the content is stored in
            String backendId = metadata.getBackendId();
            StorageBackend backend = this.backends.get(backendId);
            if (backend == null) {
                LOGGER.error("[HOUSEKEEPING] Unable to delete " + key + " - no such backend '" + backendId + "'");
                continue;
            }

            // delete the data from the backend
            try {
                backend.delete(key);
            } catch (Exception e) {
                LOGGER.warn("[STORAGE] Unable to delete '" + key + "' from the '" + backend.getBackendId() + "' backend", e);
            }

            // remove the entry from the index
            this.index.remove(key);

            LOGGER.info("[HOUSEKEEPING] Deleted '" + key + "' from the '" + backendId + "' backend");
        }

        // update metrics
        this.index.recordMetrics();
    }

    public Executor getExecutor() {
        return this.executor;
    }
}
