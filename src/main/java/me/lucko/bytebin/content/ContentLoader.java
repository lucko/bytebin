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

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Responsible for loading content, optionally with caching.
 */
public interface ContentLoader {

    static ContentLoader create(ContentStorageHandler storageHandler, int cacheTimeMins, int cacheMaxSizeMb) {
        if (cacheTimeMins > 0 && cacheMaxSizeMb > 0) {
            return new CachedContentLoader(storageHandler, cacheTimeMins, cacheMaxSizeMb);
        } else {
            return new DirectContentLoader(storageHandler);
        }
    }

    /**
     * Adds a newly submitted entry to the loader cache.
     *
     * @param key the key
     * @param future a future encapsulating the content
     */
    void put(String key, CompletableFuture<Content> future);

    /**
     * Gets an entry from the loader.
     *
     * @param key the key
     * @return a future encapsulating the content
     */
    CompletableFuture<? extends Content> get(String key);

    /**
     * Invalidates any cache for the given keys.
     *
     * @param keys the keys
     */
    void invalidate(List<String> keys);

    /**
     * A {@link ContentLoader} backed by a cache.
     */
    final class CachedContentLoader implements ContentLoader {
        private final AsyncLoadingCache<String, Content> cache;

        CachedContentLoader(ContentStorageHandler storageHandler, int cacheTimeMins, int cacheMaxSizeMb) {
            this.cache = Caffeine.newBuilder()
                    .executor(storageHandler.getExecutor())
                    .expireAfterAccess(cacheTimeMins, TimeUnit.MINUTES)
                    .maximumWeight(cacheMaxSizeMb * Content.MEGABYTE_LENGTH)
                    .weigher((Weigher<String, Content>) (path, content) -> content.getContent().length)
                    .buildAsync(storageHandler);
        }

        @Override
        public void put(String key, CompletableFuture<Content> future) {
            this.cache.put(key, future);
        }

        @Override
        public CompletableFuture<Content> get(String key) {
            return this.cache.get(key);
        }

        @Override
        public void invalidate(List<String> keys) {
            this.cache.synchronous().invalidateAll(keys);
        }
    }

    /**
     * A {@link ContentLoader} that makes requests directly to the storage handler with no caching.
     */
    final class DirectContentLoader implements ContentLoader {
        private final ContentStorageHandler storageHandler;
        private final Map<String, CompletableFuture<Content>> saveInProgress = new ConcurrentHashMap<>();

        DirectContentLoader(ContentStorageHandler storageHandler) {
            this.storageHandler = storageHandler;
        }

        @Override
        public void put(String key, CompletableFuture<Content> future) {
            if (future.isDone() && future.join().getSaveFuture().isDone()) {
                return;
            }

            // record in map while the save is in progress, then immediately remove
            this.saveInProgress.put(key, future);
            future.thenCompose(Content::getSaveFuture).thenRun(() -> this.saveInProgress.remove(key));
        }

        @Override
        public CompletableFuture<? extends Content> get(String key) {
            CompletableFuture<Content> saveInProgressFuture = this.saveInProgress.get(key);
            if (saveInProgressFuture != null) {
                return saveInProgressFuture;
            }

            try {
                return this.storageHandler.asyncLoad(key, this.storageHandler.getExecutor());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void invalidate(List<String> keys) {
            for (String key : keys) {
                this.saveInProgress.remove(key);
            }
        }
    }

}
