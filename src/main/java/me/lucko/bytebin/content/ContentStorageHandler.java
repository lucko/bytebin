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

import me.lucko.bytebin.util.ContentEncoding;
import me.lucko.bytebin.util.Gzip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Manages content i/o with the filesystem, including encoding an instance of {@link Content} into
 * a single array of bytes
 */
public class ContentStorageHandler implements CacheLoader<String, Content> {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(ContentStorageHandler.class);

    public static final Gauge STORED_CONTENT_GAUGE = Gauge.build()
            .name("bytebin_content")
            .help("The amount of stored content")
            .labelNames("type")
            .register();

    private static final Counter DISK_READS_COUNTER = Counter.build()
            .name("bytebin_disk_reads_total")
            .help("The amount of disk i/o reads")
            .register();

    private static final Set<String> SEEN_CONTENT_TYPES = ConcurrentHashMap.newKeySet();

    /** Executor service for performing file based i/o */
    private final ScheduledExecutorService executor;

    // the path to store the content in
    private final Path contentPath;

    public ContentStorageHandler(ScheduledExecutorService executor, Path contentPath) throws IOException {
        this.executor = executor;
        this.contentPath = contentPath;

        // make directories
        Files.createDirectories(this.contentPath);
    }

    public ScheduledExecutorService getExecutor() {
        return this.executor;
    }

    @Override
    public Content load(String path) throws Exception {
        LOGGER.info("[I/O] Loading " + path + " from disk");
        DISK_READS_COUNTER.inc();

        // resolve the path within the content dir
        try {
            Path resolved = this.contentPath.resolve(path);
            return load(resolved, false);
        } catch (Exception e) {
            LOGGER.error("Exception occurred loading '" + path + "'", e);
            throw e; // rethrow
        }
    }

    private Content load(Path resolved, boolean skipContent) throws IOException {
        if (!Files.exists(resolved)) {
            return Content.EMPTY_CONTENT;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(resolved)))) {
            // read version
            int version = in.readInt();

            // read key
            String key = in.readUTF();

            // read content type
            byte[] contentTypeBytes = new byte[in.readInt()];
            in.readFully(contentTypeBytes);
            String contentType = new String(contentTypeBytes);

            // read expiry
            long expiry = in.readLong();
            Instant expiryInstant = expiry == -1 ? Instant.MAX : Instant.ofEpochMilli(expiry);

            // read last modified time
            long lastModified = in.readLong();

            // read modifiable state data
            boolean modifiable = in.readBoolean();
            String authKey = null;
            if (modifiable) {
                authKey = in.readUTF();
            }

            // read encoding
            String encoding;
            if (version == 1) {
                encoding = ContentEncoding.GZIP;
            } else {
                byte[] encodingBytes = new byte[in.readInt()];
                in.readFully(encodingBytes);
                encoding = new String(encodingBytes);
            }

            // read content
            byte[] content;
            if (skipContent) {
                content = Content.EMPTY_BYTES;
            } else {
                content = new byte[in.readInt()];
                in.readFully(content);
            }

            return new Content(key, contentType, expiryInstant, lastModified, modifiable, authKey, encoding, content);
        }
    }

    public Content loadMeta(Path resolved) throws IOException {
        return load(resolved, true);
    }

    public void save(String key, String contentType, byte[] content, Instant expiry, String authKey, boolean requiresCompression, String encoding, CompletableFuture<Content> future) {
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

    public void save(Content c) {
        // resolve the path to save at
        Path path = this.contentPath.resolve(c.getKey());

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            // write version
            out.writeInt(2);

            // write name
            out.writeUTF(c.getKey());

            // write content type
            byte[] contextType = c.getContentType().getBytes();
            out.writeInt(contextType.length);
            out.write(contextType);

            // write expiry time
            out.writeLong(c.getExpiry() == Instant.MAX ? -1 : c.getExpiry().toEpochMilli());

            // write last modified
            out.writeLong(c.getLastModified());

            // write modifiable state data
            out.writeBoolean(c.isModifiable());
            if (c.isModifiable()) {
                out.writeUTF(c.getAuthKey());
            }

            // write encoding
            byte[] encoding = c.getEncoding().getBytes();
            out.writeInt(encoding.length);
            out.write(encoding);

            // write content
            out.writeInt(c.getContent().length);
            out.write(c.getContent());
        } catch (IOException e) {
            LOGGER.error("Exception occurred saving '" + path + "'", e);
        }
    }

    public void runInvalidationAndRecordMetrics() {
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        for (String contentType : SEEN_CONTENT_TYPES) {
            counts.put(contentType, new AtomicInteger());
        }

        try (Stream<Path> stream = Files.list(this.contentPath)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Content content = loadMeta(path);
                            if (content.shouldExpire()) {
                                LOGGER.info("Expired: " + path.getFileName().toString());
                                Files.delete(path);
                            } else {
                                counts.computeIfAbsent(content.getContentType(), x -> new AtomicInteger()).incrementAndGet();
                            }
                        } catch (EOFException e) {
                            LOGGER.info("Corrupted: " + path.getFileName().toString());
                            try {
                                Files.delete(path);
                            } catch (IOException e2) {
                                // ignore
                            }
                        } catch (Exception e) {
                            LOGGER.error("Exception occurred loading meta for '" + path.getFileName().toString() + "'", e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Exception thrown whilst invalidating", e);
        }

        // keep track of seen content types so that they get set back to 0 if
        // all instances of a given type are deleted
        SEEN_CONTENT_TYPES.addAll(counts.keySet());

        counts.forEach((contentType, count) -> STORED_CONTENT_GAUGE.labels(contentType).set(count.get()));
    }
}
