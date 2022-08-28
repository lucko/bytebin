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

package me.lucko.bytebin.content.storage;

import com.github.benmanes.caffeine.cache.CacheLoader;

import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.util.Gzip;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface StorageBackend extends CacheLoader<String, Content> {

    @Override
    @NonNull Content load(String key) throws Exception;

    @NonNull Content loadMeta(String key) throws Exception;

    default void save(String key, String contentType, byte[] content, Instant expiry, String authKey, boolean requiresCompression, String encoding, CompletableFuture<Content> future) {
        if (requiresCompression) {
            content = Gzip.compress(content);
        }

        // add directly to the cache
        // it's quite likely that the file will be requested only a few seconds after it is uploaded
        Content c = new Content(key, contentType, expiry, System.currentTimeMillis(), authKey != null, authKey, encoding, content);
        future.complete(c);

        try {
            save(c);
        } finally {
            c.getSaveFuture().complete(null);
        }
    }

    void save(Content content);

    void runInvalidationAndRecordMetrics();

    Executor getExecutor();
}
