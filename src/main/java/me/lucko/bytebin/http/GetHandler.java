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

import me.lucko.bytebin.content.ContentCache;
import me.lucko.bytebin.util.Compression;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.Req;
import org.rapidoid.http.ReqHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static me.lucko.bytebin.http.BytebinServer.cors;

public final class GetHandler implements ReqHandler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(GetHandler.class);

    private final BytebinServer server;
    private final RateLimiter rateLimiter;
    private final ContentCache contentCache;

    public GetHandler(BytebinServer server, RateLimiter rateLimiter, ContentCache contentCache) {
        this.server = server;
        this.rateLimiter = rateLimiter;
        this.contentCache = contentCache;
    }

    @Override
    public Object execute(Req req) {
        // get the requested path
        String path = req.path().substring(1);
        if (path.trim().isEmpty() || path.contains(".") || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            return cors(req.response()).code(404).plain("Invalid path");
        }

        String ipAddress = BytebinServer.getIpAddress(req);

        // check rate limits
        if (this.rateLimiter.check(ipAddress)) return cors(req.response()).code(429).plain("Rate limit exceeded");

        // request the file from the cache async
        boolean supportsCompression = Compression.acceptsCompressed(req);

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

            LOGGER.info("[REQUEST]");
            LOGGER.info("    key = " + path);
            LOGGER.info("    user agent = " + req.header("User-Agent", "null"));
            LOGGER.info("    origin = " + ipAddress + (hostname != null ? " (" + hostname + ")" : ""));
            LOGGER.info("    supports compression = " + supportsCompression);
            LOGGER.info("");
        });

        this.contentCache.get(path).whenCompleteAsync((content, throwable) -> {
            if (throwable != null || content == null || content.getKey() == null || content.getContent().length == 0) {
                cors(req.response()).code(404).plain("Invalid path").done();
                return;
            }

            String expiryTime = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(content.getExpiry()).atOffset(ZoneOffset.UTC));

            // will the client accept the content in a compressed form?
            if (supportsCompression) {
                cors(req.response()).code(200)
                        .header("Cache-Control", "public, max-age=86400")
                        .header("Content-Encoding", "gzip")
                        .header("Expires", expiryTime)
                        .body(content.getContent())
                        .contentType(content.getMediaType())
                        .done();
                return;
            }

            // need to uncompress
            byte[] uncompressed;
            try {
                uncompressed = Compression.decompress(content.getContent());
            } catch (IOException e) {
                cors(req.response()).code(404).plain("Unable to uncompress data").done();
                return;
            }

            // return the data
            cors(req.response()).code(200)
                    .header("Cache-Control", "public, max-age=86400")
                    .header("Expires", expiryTime)
                    .body(uncompressed)
                    .contentType(content.getMediaType())
                    .done();
        });

        // mark that we're going to respond later
        return req.async();
    }
}
