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
import me.lucko.bytebin.content.ContentLoader;
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.util.ContentEncoding;
import me.lucko.bytebin.util.Gzip;
import me.lucko.bytebin.util.RateLimitHandler;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class GetHandler implements Route.Handler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(GetHandler.class);

    private final BytebinServer server;
    private final LogHandler logHandler;
    private final RateLimiter rateLimiter;
    private final RateLimiter notFoundRateLimiter;
    private final RateLimitHandler rateLimitHandler;
    private final ContentLoader contentLoader;

    public GetHandler(BytebinServer server, LogHandler logHandler, RateLimiter rateLimiter, RateLimiter notFoundRateLimiter, RateLimitHandler rateLimitHandler, ContentLoader contentLoader) {
        this.server = server;
        this.logHandler = logHandler;
        this.rateLimiter = rateLimiter;
        this.notFoundRateLimiter = notFoundRateLimiter;
        this.rateLimitHandler = rateLimitHandler;
        this.contentLoader = contentLoader;
    }

    @Override
    public CompletableFuture<byte[]> apply(@Nonnull Context ctx) {
        // get the requested path
        String path = ctx.path("id").value();
        if (path.trim().isEmpty() || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            BytebinServer.recordRejectedRequest("GET", "invalid_path", ctx);
            throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
        }

        // check rate limits
        RateLimitHandler.Result rateLimitResult = this.rateLimitHandler.getIpAddressAndCheckRateLimit(ctx, this.rateLimiter, "GET");
        String ipAddress = rateLimitResult.ipAddress();

        // get the encodings supported by the requester
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Accept-Encoding
        Set<String> acceptedEncoding = ContentEncoding.getAcceptedEncoding(ctx);

        // get the user agent & origin headers
        String userAgent = ctx.header("User-Agent").value("null");
        String origin = ctx.header("Origin").value("null");
        String host = ctx.getHostAndPort();
        Map<String, String> headers = ctx.headerMap();

        LOGGER.info("[REQUEST]\n" +
                "    key = " + path + "\n" +
                "    user agent = " + userAgent + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin.equals("null") ? "" : "    origin = " + origin + "\n") +
                "    host = " + host + "\n"
        );

        if (rateLimitResult.isRealUser()) {
            this.logHandler.logAttemptedGet(path, new LogHandler.User(userAgent, origin, host, ipAddress, headers));

            if (this.notFoundRateLimiter.check(rateLimitResult.ipAddress())) {
                BytebinServer.recordRejectedRequest("GET", "rate_limited_get_not_found", ctx);
                throw new StatusCodeException(StatusCode.TOO_MANY_REQUESTS, "Rate limit exceeded");
            }
        }

        // request the file from the cache async
        return this.contentLoader.get(path).handleAsync((content, throwable) -> {
            if (throwable != null || content == null || content.getKey() == null || content.getContent().length == 0) {
                if (rateLimitResult.isRealUser()) {
                    this.notFoundRateLimiter.increment(rateLimitResult.ipAddress());
                }
                BytebinServer.recordRejectedRequest("GET", "not_found", ctx);
                throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
            }

            if (rateLimitResult.isRealUser()) {
                BytebinServer.recordRequest("GET", ctx);
                this.logHandler.logGet(
                        path,
                        new LogHandler.User(userAgent, origin, host, ipAddress, headers),
                        new LogHandler.ContentInfo(content.getContentLength(), content.getContentType(), content.getExpiry())
                );
            }

            ctx.setResponseHeader("Last-Modified", Instant.ofEpochMilli(content.getLastModified()));

            // Cache-Control: no-transform   instructs caches (e.g. cloudflare) to not transform the content
            //                               Since bytebin will almost always serve the content already compressed,
            //                               there is no reason for the cache to try to transform/uncompress/recompress it.
            //                               (in fact it is likely that this process will decrease loading speed)
            //
            // Cache-Control: immutable      the content will never change, caches can more aggressively cache the content
            //                               without needing to revalidate.

            if (content.isModifiable()) {
                // cache assets in proxy caches but require revalidation by the proxy when served
                ctx.setResponseHeader("Cache-Control", "public, no-cache, proxy-revalidate, no-transform");
            } else {
                // cache effectively forever
                ctx.setResponseHeader("Cache-Control", "public, max-age=604800, no-transform, immutable");
            }

            List<String> contentEncodingStrings = ContentEncoding.getContentEncoding(content.getEncoding());

            // requester supports the used content encoding, just serve as-is
            if (acceptedEncoding.contains("*") || acceptedEncoding.containsAll(contentEncodingStrings)) {
                ctx.setResponseHeader("Content-Encoding", content.getEncoding());
                ctx.setResponseType(MediaType.valueOf(content.getContentType()));
                return content.getContent();
            }

            LOGGER.warn("[REQUEST] Request for 'key = " + path + "' was made with incompatible Accept-Encoding headers! " +
                    "Content-Encoding = " + contentEncodingStrings + ", " +
                    "Accept-Encoding = " + acceptedEncoding);

            // if it's compressed using gzip, we will uncompress on the server side
            if (contentEncodingStrings.size() == 1 && contentEncodingStrings.get(0).equals(ContentEncoding.GZIP)) {
                byte[] uncompressed;
                try {
                    uncompressed = Gzip.decompress(content.getContent());
                } catch (IOException e) {
                    throw new StatusCodeException(StatusCode.NOT_FOUND, "Unable to uncompress data");
                }

                // return the uncompressed data
                ctx.setResponseType(MediaType.valueOf(content.getContentType()));
                return uncompressed;
            }

            // requester doesn't support the content encoding - there's nothing we can do
            // https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/406
            throw new StatusCodeException(StatusCode.NOT_ACCEPTABLE, "Accept-Encoding \"" + ctx.header("Accept-Encoding").value("") + "\" does not contain Content-Encoding \"" + content.getEncoding() + "\"");
        });
    }
}
