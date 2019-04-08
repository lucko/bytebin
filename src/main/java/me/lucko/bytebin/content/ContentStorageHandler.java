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

import me.lucko.bytebin.util.Compression;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.MediaType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

/**
 * Manages content i/o with the filesystem, including encoding an instance of {@link Content} into
 * a single array of bytes
 */
public class ContentStorageHandler implements CacheLoader<String, Content> {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(ContentStorageHandler.class);

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
    public Content load(String path) throws IOException {
        LOGGER.info("[I/O] Loading " + path + " from disk");

        // resolve the path within the content dir
        Path resolved = this.contentPath.resolve(path);
        return load(resolved);
    }

    public Content load(Path resolved) throws IOException {
        if (!Files.exists(resolved)) {
            return Content.EMPTY_CONTENT;
        }

        try (DataInputStream in = new DataInputStream(Files.newInputStream(resolved))) {
            // read key
            String key = in.readUTF();

            // read content type
            byte[] contentType = new byte[in.readInt()];
            in.readFully(contentType);
            MediaType mediaType = MediaType.of(new String(contentType));

            // read expiry
            long expiry = in.readLong();

            // read content
            byte[] content = new byte[in.readInt()];
            in.readFully(content);

            return new Content(key, mediaType, expiry, content);
        }
    }

    public Content loadMeta(Path resolved) throws IOException {
        if (!Files.exists(resolved)) {
            return Content.EMPTY_CONTENT;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(resolved)))) {
            // read key
            String key = in.readUTF();

            // read content type
            byte[] contentType = new byte[in.readInt()];
            in.readFully(contentType);
            MediaType mediaType = MediaType.of(new String(contentType));

            // read expiry
            long expiry = in.readLong();

            return new Content(key, mediaType, expiry, Content.EMPTY_BYTES);
        }
    }

    public void save(String key, MediaType mediaType, byte[] content, long expiry, boolean requiresCompression, CompletableFuture<Content> future) {
        if (requiresCompression) {
            content = Compression.compress(content);
        }

        // add directly to the cache
        // it's quite likely that the file will be requested only a few seconds after it is uploaded
        Content c = new Content(key, mediaType, expiry, content);
        future.complete(c);

        // resolve the path to save at
        Path path = this.contentPath.resolve(key);

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)))) {
            // write name
            out.writeUTF(key);

            // write content type
            byte[] contextType = mediaType.getBytes();
            out.writeInt(contextType.length);
            out.write(contextType);

            // write expiry time
            out.writeLong(expiry);

            // write content
            out.writeInt(content.length);
            out.write(content);
        } catch (FileAlreadyExistsException e) {
            LOGGER.info("File '" + key + "' already exists.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void runInvalidation() {
        try (Stream<Path> stream = Files.list(this.contentPath)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Content content = loadMeta(path);
                            if (content.shouldExpire()) {
                                LOGGER.info("Expired: " + path.getFileName().toString());
                                Files.delete(path);
                            }
                        } catch (EOFException e) {
                            LOGGER.info("Corrupted: " + path.getFileName().toString());
                            try {
                                Files.delete(path);
                            } catch (IOException e2) {
                                // ignore
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
