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

import io.jooby.Context;
import io.jooby.MediaType;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import io.prometheus.client.Summary;
import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentLoader;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.util.ContentEncoding;
import me.lucko.bytebin.util.ExpiryHandler;
import me.lucko.bytebin.util.Gzip;
import me.lucko.bytebin.util.RateLimitHandler;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class PostHandler implements Route.Handler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(PostHandler.class);

    public static final Summary CONTENT_SIZE_SUMMARY = Summary.build()
            .name("bytebin_content_size_bytes")
            .help("The size of posted content")
            .labelNames("useragent")
            .register();

    private final BytebinServer server;
    private final LogHandler logHandler;
    private final RateLimiter rateLimiter;
    private final RateLimitHandler rateLimitHandler;

    private final ContentStorageHandler storageHandler;
    private final ContentLoader contentLoader;
    private final TokenGenerator contentTokenGenerator;
    private final TokenGenerator authKeyTokenGenerator;
    private final long maxContentLength;
    private final ExpiryHandler expiryHandler;
    private final Map<String, String> hostAliases;

    public PostHandler(BytebinServer server, LogHandler logHandler, RateLimiter rateLimiter, RateLimitHandler rateLimitHandler, ContentStorageHandler storageHandler, ContentLoader contentLoader, TokenGenerator contentTokenGenerator, long maxContentLength, ExpiryHandler expiryHandler, Map<String, String> hostAliases) {
        this.server = server;
        this.logHandler = logHandler;
        this.rateLimiter = rateLimiter;
        this.rateLimitHandler = rateLimitHandler;
        this.storageHandler = storageHandler;
        this.contentLoader = contentLoader;
        this.contentTokenGenerator = contentTokenGenerator;
        this.authKeyTokenGenerator = new TokenGenerator(32);
        this.maxContentLength = maxContentLength;
        this.expiryHandler = expiryHandler;
        this.hostAliases = hostAliases;
    }

    @Override
    public String apply(@Nonnull Context ctx) {
        byte[] content = ctx.body().bytes();

        // ensure something was actually posted
        if (content == null || content.length == 0) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "Missing content");
        }

        // check rate limits
        RateLimitHandler.Result rateLimitResult = this.rateLimitHandler.getIpAddressAndCheckRateLimit(ctx, this.rateLimiter);
        String ipAddress = rateLimitResult.ipAddress();

        // determine the content type
        String contentType = ctx.header("Content-Type").value("text/plain");

        // generate a key
        String key = this.contentTokenGenerator.generate();

        // get the content encodings
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Encoding
        List<String> encodings = ContentEncoding.getContentEncoding(ctx.header("Content-Encoding").valueOrNull());

        // get the user agent & origin headers
        String userAgent = ctx.header("User-Agent").value("null");
        String origin = ctx.header("Origin").value("null");
        String host = ctx.getHostAndPort();

        Date expiry = this.expiryHandler.getExpiry(userAgent, origin, host);

        // check max content length
        if (content.length > this.maxContentLength) {
            throw new StatusCodeException(StatusCode.REQUEST_ENTITY_TOO_LARGE, "Content too large");
        }

        // check for our custom Allow-Modification header
        boolean allowModifications = ctx.header("Allow-Modification").booleanValue(false);
        String authKey;
        if (allowModifications) {
            authKey = this.authKeyTokenGenerator.generate();
        } else {
            authKey = null;
        }

        LOGGER.info("[POST]\n" +
                "    key = " + key + "\n" +
                "    type = " + contentType + "\n" +
                "    user agent = " + userAgent + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin.equals("null") ? "" : "    origin = " + origin + "\n") +
                "    host = " + host + "\n" +
                "    content size = " + String.format("%,d", content.length / 1024) + " KB\n" +
                "    encoding = " + encodings.toString() + "\n"
        );

        // metrics
        if (rateLimitResult.countMetrics()) {
            String metricsLabel = BytebinServer.getMetricsLabel(ctx);
            BytebinServer.recordRequest("POST", metricsLabel);
            CONTENT_SIZE_SUMMARY.labels(metricsLabel).observe(content.length);

            this.logHandler.logPost(key, userAgent, origin, ipAddress, content.length, contentType, expiry);
        }

        // record the content in the cache
        CompletableFuture<Content> future = new CompletableFuture<>();
        this.contentLoader.put(key, future);

        // check whether the content should be compressed by bytebin before saving
        boolean compressServerSide = encodings.isEmpty();
        if (compressServerSide) {
            encodings.add(ContentEncoding.GZIP);
        }

        String encoding = String.join(",", encodings);
        this.storageHandler.getExecutor().execute(() -> {
            byte[] buf = content;
            if (compressServerSide) {
                buf = Gzip.compress(buf);
            }

            // add directly to the cache
            // it's quite likely that the file will be requested only a few seconds after it is uploaded
            Content c = new Content(key, contentType, expiry, System.currentTimeMillis(), authKey != null, authKey, encoding, buf);
            future.complete(c);

            try {
                this.storageHandler.save(c);
            } finally {
                c.getSaveFuture().complete(null);
            }
        });

        // return the url location as plain content
        ctx.setResponseCode(StatusCode.CREATED);

        if (allowModifications) {
            ctx.setResponseHeader("Modification-Key", authKey);
        }

        if (ctx.getMethod().equals("PUT")) {
            // PUT: return the URL where the content can be accessed
            host = this.hostAliases.getOrDefault(host, host);
            String location = "https://" + host + "/" + key;

            ctx.setResponseHeader("Location", location);
            ctx.setResponseType(MediaType.TEXT);
            return location + "\n";
        } else {
            // POST: return the key
            ctx.setResponseHeader("Location", key);
            ctx.setResponseType(MediaType.JSON);
            return "{\"key\":\"" + key + "\"}";
        }
    }

}
