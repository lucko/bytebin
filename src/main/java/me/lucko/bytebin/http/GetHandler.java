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
import me.lucko.bytebin.util.ContentEncoding;
import me.lucko.bytebin.util.Gzip;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.jooby.Context;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

public final class GetHandler implements Route.Handler {

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
    public CompletableFuture<byte[]> apply(@Nonnull Context ctx) {
        // get the requested path
        String path = ctx.path("id").value();
        if (path.trim().isEmpty() || path.contains(".") || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
        }

        String ipAddress = BytebinServer.getIpAddress(ctx);

        // check rate limits
        if (this.rateLimiter.check(ipAddress)) {
            throw new StatusCodeException(StatusCode.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }

        // get the encodings supported by the requester
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Accept-Encoding
        Set<String> supportedEncodings = ContentEncoding.getAcceptedEncoding(ctx);

        String origin = ctx.header("Origin").valueOrNull();
        LOGGER.info("[REQUEST]\n" +
                "    key = " + path + "\n" +
                "    user agent = " + ctx.header("User-Agent").value("null") + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin == null ? "" : "    origin = " + origin + "\n")
        );

        // request the file from the cache async
        return this.contentCache.get(path).handleAsync((content, throwable) -> {
            if (throwable != null || content == null || content.getKey() == null || content.getContent().length == 0) {
                throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
            }

            ctx.setResponseHeader("Last-Modified", Instant.ofEpochMilli(content.getLastModified()));

            long expires = Duration.between(Instant.now(), content.getExpiry()).getSeconds();
            if (content.isModifiable() || expires <= 0L) {
                ctx.setResponseHeader("Cache-Control", "no-cache");
            } else {
                ctx.setResponseHeader("Cache-Control", "public, max-age=" + expires);
            }

            List<String> contentEncodingStrings = ContentEncoding.getContentEncoding(content.getEncoding());

            // requester supports the used content encoding, just serve as-is
            if (supportedEncodings.contains("*") || supportedEncodings.containsAll(contentEncodingStrings)) {
                ctx.setResponseHeader("Content-Encoding", content.getEncoding());
                ctx.setResponseType(io.jooby.MediaType.valueOf(content.getContentType()));
                return content.getContent();
            }

            // if it's compressed using gzip, we will uncompress on the server side
            if (contentEncodingStrings.size() == 1 && contentEncodingStrings.get(0).equals(ContentEncoding.GZIP)) {
                byte[] uncompressed;
                try {
                    uncompressed = Gzip.decompress(content.getContent());
                } catch (IOException e) {
                    throw new StatusCodeException(StatusCode.NOT_FOUND, "Unable to uncompress data");
                }

                // return the uncompressed data
                ctx.setResponseType(io.jooby.MediaType.valueOf(content.getContentType()));
                return uncompressed;
            }

            // requester doesn't support the content encoding - there's nothing we can do
            // https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/406
            throw new StatusCodeException(StatusCode.NOT_ACCEPTABLE, "Accept-Encoding \"" + ctx.header("Accept-Encoding").value("") + "\" does not contain Content-Encoding \"" + content.getEncoding() + "\"");
        });
    }
}
