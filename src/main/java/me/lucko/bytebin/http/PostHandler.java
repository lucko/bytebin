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

package me.lucko.bytebin.http;

import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentCache;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.util.Compression;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.MediaType;
import org.rapidoid.http.Req;
import org.rapidoid.http.ReqHandler;
import org.rapidoid.http.Resp;
import org.rapidoid.u.U;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static me.lucko.bytebin.http.BytebinServer.cors;

public final class PostHandler implements ReqHandler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(PostHandler.class);

    private final BytebinServer server;
    private final RateLimiter rateLimiter;

    private final ContentStorageHandler contentStorageHandler;
    private final ContentCache contentCache;
    private final TokenGenerator contentTokenGenerator;
    private final TokenGenerator authKeyTokenGenerator;
    private final long maxContentLength;
    private final long lifetimeMillis;

    public PostHandler(BytebinServer server, RateLimiter rateLimiter, ContentStorageHandler contentStorageHandler, ContentCache contentCache, TokenGenerator contentTokenGenerator, long maxContentLength, long lifetimeMillis) {
        this.server = server;
        this.rateLimiter = rateLimiter;
        this.contentStorageHandler = contentStorageHandler;
        this.contentCache = contentCache;
        this.contentTokenGenerator = contentTokenGenerator;
        this.authKeyTokenGenerator = new TokenGenerator(32);
        this.maxContentLength = maxContentLength;
        this.lifetimeMillis = lifetimeMillis;
    }

    @Override
    public Object execute(Req req) {
        AtomicReference<byte[]> content = new AtomicReference<>(req.body());

        String ipAddress = BytebinServer.getIpAddress(req);

        // ensure something was actually posted
        if (content.get().length == 0) return cors(req.response()).code(400).plain("Missing content");
        // check rate limits
        if (this.rateLimiter.check(ipAddress)) return cors(req.response()).code(429).plain("Rate limit exceeded");

        // determine the mediatype
        MediaType mediaType = determineMediaType(req);

        // generate a key
        String key = this.contentTokenGenerator.generate();

        // is the content already compressed?
        boolean compressed = req.header("Content-Encoding", "").equals("gzip");

        // if compression is required at a later stage
        AtomicBoolean requiresCompression = new AtomicBoolean(false);

        // if it's not compressed, consider the effect of compression on the content length
        if (!compressed) {
            // if the max content length would be exceeded - try compressing
            if (content.get().length > this.maxContentLength) {
                content.set(Compression.compress(content.get()));
            } else {
                // compress later
                requiresCompression.set(true);
            }
        }

        long expiry = System.currentTimeMillis() + this.lifetimeMillis;

        // check max content length
        if (content.get().length > this.maxContentLength) return cors(req.response()).code(413).plain("Content too large");

        // check for our custom Allow-Modification header
        boolean allowModifications = Boolean.parseBoolean(req.header("Allow-Modification", "false"));
        String authKey;
        if (allowModifications) {
            authKey = this.authKeyTokenGenerator.generate();
        } else {
            authKey = null;
        }

        this.server.getLoggingExecutor().submit(() -> {
            String hostname = null;
            try {
                InetAddress inetAddress = InetAddress.getByName(ipAddress);
                hostname = inetAddress.getCanonicalHostName();
                if (ipAddress.equals(hostname)) {
                    hostname = null;
                }
            } catch (Exception e) {
                // ignore
            }

            LOGGER.info("[POST]");
            LOGGER.info("    key = " + key);
            LOGGER.info("    type = " + new String(mediaType.getBytes()));
            LOGGER.info("    user agent = " + req.header("User-Agent", "null"));
            LOGGER.info("    origin = " + ipAddress + (hostname != null ? " (" + hostname + ")" : ""));
            LOGGER.info("    content size = " + String.format("%,d", content.get().length / 1024) + " KB");
            LOGGER.info("    compressed = " + !requiresCompression.get());
            LOGGER.info("    allow modification = " + allowModifications);
            LOGGER.info("");
        });

        // record the content in the cache
        CompletableFuture<Content> future = new CompletableFuture<>();
        this.contentCache.put(key, future);

        // save the data to the filesystem
        this.contentStorageHandler.getExecutor().execute(() -> this.contentStorageHandler.save(key, mediaType, content.get(), expiry, authKey, requiresCompression.get(), future));

        // return the url location as plain content
        Resp resp = cors(req.response()).code(201).header("Location", key);

        if (allowModifications) {
            resp.header("Modification-Key", authKey);
        }

        return resp.json(U.map("key", key));
    }

    private static MediaType determineMediaType(Req req) {
        MediaType mt = req.contentType();
        if (mt == null) {
            mt = MediaType.TEXT_PLAIN;
        }
        return mt;
    }

}
