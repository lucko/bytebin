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
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import me.lucko.bytebin.content.ContentLoader;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.ratelimit.RateLimitHandler;
import me.lucko.bytebin.ratelimit.RateLimiter;
import me.lucko.bytebin.util.ContentEncoding;
import me.lucko.bytebin.util.ExpiryHandler;
import me.lucko.bytebin.util.Gzip;
import me.lucko.bytebin.util.Metrics;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class UpdateHandler implements Route.Handler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(UpdateHandler.class);

    private final BytebinServer server;
    private final LogHandler logHandler;
    private final RateLimiter rateLimiter;
    private final RateLimitHandler rateLimitHandler;

    private final ContentStorageHandler storageHandler;
    private final ContentLoader contentLoader;
    private final long maxContentLength;
    private final ExpiryHandler expiryHandler;

    public UpdateHandler(BytebinServer server, LogHandler logHandler, RateLimiter rateLimiter, RateLimitHandler rateLimitHandler, ContentStorageHandler storageHandler, ContentLoader contentLoader, long maxContentLength, ExpiryHandler expiryHandler) {
        this.server = server;
        this.logHandler = logHandler;
        this.rateLimiter = rateLimiter;
        this.rateLimitHandler = rateLimitHandler;
        this.storageHandler = storageHandler;
        this.contentLoader = contentLoader;
        this.maxContentLength = maxContentLength;
        this.expiryHandler = expiryHandler;
    }

    @Override
    public CompletableFuture<Void> apply(@Nonnull Context ctx) {
        // get the requested path
        String path = ctx.path("id").value();
        if (path.trim().isEmpty() || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            Metrics.recordRejectedRequest("PUT", "invalid_path", ctx);
            throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
        }

        byte[] newContent = PostHandler.getBodyAsByteArray(ctx, (int) this.maxContentLength);

        // ensure something was actually posted
        if (newContent.length == 0) {
            Metrics.recordRejectedRequest("PUT", "missing_content", ctx);
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "Missing content");
        }

        // check rate limits
        RateLimitHandler.Result rateLimitResult = this.rateLimitHandler.getIpAddressAndCheckRateLimit(ctx, this.rateLimiter, "PUT");
        String ipAddress = rateLimitResult.ipAddress();

        String authHeader = ctx.header("Authorization").valueOrNull();
        if (authHeader == null) {
            throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Authorization header not present");
        }

        if (!authHeader.startsWith("Bearer ")) {
            throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Invalid Authorization scheme");
        }
        String authKey = authHeader.substring("Bearer ".length());

        // get the user agent & origin headers
        String userAgent = ctx.header("User-Agent").value("null");
        String origin = ctx.header("Origin").value("null");
        String host = ctx.getHostAndPort();
        Map<String, String> headers = ctx.headerMap();

        return this.contentLoader.get(path).handleAsync((oldContent, throwable) -> {
            if (throwable != null || oldContent == null || oldContent.getKey() == null || oldContent.getContent().length == 0) {
                Metrics.recordRejectedRequest("PUT", "not_found", ctx);
                // use a generic response to prevent use of this endpoint to search for valid content
                throw new StatusCodeException(StatusCode.FORBIDDEN, "Incorrect modification key");
            }

            // ok so the old content does exist, check that it is modifiable & that the key matches
            if (!oldContent.isModifiable() || !oldContent.getAuthKey().equals(authKey)) {
                // use a generic response to prevent use of this endpoint to search for valid content
                throw new StatusCodeException(StatusCode.FORBIDDEN, "Incorrect modification key");
            }

            // determine the new content type
            String newContentType = ctx.header("Content-Type").value(oldContent.getContentType());

            // determine new encoding
            List<String> newEncodings = ContentEncoding.getContentEncoding(ctx.header("Content-Encoding").valueOrNull());

            byte[] buf = newContent;

            // compress if necessary
            if (newEncodings.isEmpty()) {
                buf = Gzip.compress(buf);
                newEncodings.add(ContentEncoding.GZIP);
            }

            // check max content length
            if (buf.length > this.maxContentLength) {
                Metrics.recordRejectedRequest("PUT", "content_too_large", ctx);
                throw new StatusCodeException(StatusCode.REQUEST_ENTITY_TOO_LARGE, "Content too large");
            }

            Date newExpiry = this.expiryHandler.getExpiry(userAgent, origin, host);

            LOGGER.info("[PUT]\n" +
                    "    key = " + path + "\n" +
                    "    new type = " + newContentType + "\n" +
                    "    new encoding = " + newEncodings + "\n" +
                    "    user agent = " + userAgent + "\n" +
                    "    ip = " + ipAddress + "\n" +
                    (origin.equals("null") ? "" : "    origin = " + origin + "\n") +
                    "    host = " + host + "\n" +
                    "    old content size = " + String.format("%,d", oldContent.getContent().length / 1024) + " KB" + "\n" +
                    "    new content size = " + String.format("%,d", buf.length / 1024) + " KB" + "\n"
            );

            // metrics
            if (rateLimitResult.isRealUser()) {
                Metrics.recordRequest("PUT", ctx);
                this.logHandler.logPost(
                        path,
                        new LogHandler.User(userAgent, origin, host, ipAddress, headers),
                        new LogHandler.ContentInfo(buf.length, newContentType, newExpiry)
                );
            }

            // update the content instance with the new data
            oldContent.setContentType(newContentType);
            oldContent.setEncoding(String.join(",", newEncodings));
            oldContent.setExpiry(newExpiry);
            oldContent.setLastModified(System.currentTimeMillis());
            oldContent.setContent(buf);

            // make the http response
            ctx.setResponseCode(StatusCode.OK);
            ctx.send();

            // save to disk
            this.storageHandler.save(oldContent);

            return null;
        }, this.storageHandler.getExecutor());
    }

}
