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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ContentCache {

    private final int cacheTimeMins;

    /** Content cache - caches the raw byte data for the last x requested files */
    private final AsyncLoadingCache<String, Content> contentCache;

    public ContentCache(ContentStorageHandler loader, int cacheTimeMins, int cacheMaxSizeMb) {
        this.cacheTimeMins = cacheTimeMins;
        this.contentCache = Caffeine.newBuilder()
                .executor(loader.getExecutor())
                .expireAfterAccess(cacheTimeMins, TimeUnit.MINUTES)
                .maximumWeight(cacheMaxSizeMb * Content.MEGABYTE_LENGTH)
                .weigher((Weigher<String, Content>) (path, content) -> content.getContent().length)
                .buildAsync(loader);
    }

    public int getCacheTimeMins() {
        return this.cacheTimeMins;
    }

    public void put(String key, CompletableFuture<Content> future) {
        this.contentCache.put(key, future);
    }

    public CompletableFuture<Content> get(String key) {
        return this.contentCache.get(key);
    }

}
