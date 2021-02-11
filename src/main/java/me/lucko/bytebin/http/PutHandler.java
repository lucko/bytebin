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

import java.util.List;
import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentCache;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.util.Compression;
import me.lucko.bytebin.util.ContentEncoding;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.Req;
import org.rapidoid.http.ReqHandler;

import java.util.concurrent.atomic.AtomicReference;

import static me.lucko.bytebin.http.BytebinServer.*;

public final class PutHandler implements ReqHandler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(PutHandler.class);

    private final BytebinServer server;
    private final RateLimiter rateLimiter;

    private final ContentStorageHandler contentStorageHandler;
    private final ContentCache contentCache;
    private final long maxContentLength;
    private final long lifetimeMillis;

    public PutHandler(BytebinServer server, RateLimiter rateLimiter, ContentStorageHandler contentStorageHandler, ContentCache contentCache, long maxContentLength, long lifetimeMillis) {
        this.server = server;
        this.rateLimiter = rateLimiter;
        this.contentStorageHandler = contentStorageHandler;
        this.contentCache = contentCache;
        this.maxContentLength = maxContentLength;
        this.lifetimeMillis = lifetimeMillis;
    }

    @Override
    public Object execute(Req req) {
        // get the path
        String path = req.path().substring(1);
        if (path.trim().isEmpty() || path.contains(".") || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            return cors(req.response()).code(404).plain("Invalid path");
        }

        AtomicReference<byte[]> newContent = new AtomicReference<>(req.body());

        String ipAddress = BytebinServer.getIpAddress(req);

        // ensure something was actually posted
        if (newContent.get().length == 0) return cors(req.response()).code(400).plain("Missing content");
        // check rate limits
        if (this.rateLimiter.check(ipAddress)) return cors(req.response()).code(429).plain("Rate limit exceeded");

        String authKey = req.header("Modification-Key", null);
        if (authKey == null) return cors(req.response()).code(403).plain("Modification-Key header not present");

        this.contentCache.get(path).whenCompleteAsync((oldContent, throwable) -> {
            if (throwable != null || oldContent == null || oldContent.getKey() == null || oldContent.getContent().length == 0) {
                // use a generic response to prevent use of this endpoint to search for valid content
                cors(req.response()).plain("Incorrect modification key").done();
                return;
            }

            // ok so the old content does exist, check that it is modifiable
            if (!oldContent.isModifiable()) {
                // use a generic response to prevent use of this endpoint to search for valid content
                cors(req.response()).code(403).plain("Incorrect modification key").done();
                return;
            }

            // check the auth key matches
            if (!oldContent.getAuthKey().equals(authKey)) {
                cors(req.response()).code(403).plain("Incorrect modification key").done();
                return;
            }

            // determine the new content type
            String newContentType = req.header("Content-Type", oldContent.getContentType());

            // determine new encoding
            String newEncoding = req.header("Content-Encoding", oldContent.getEncoding());

            // compress if necessary
            List<ContentEncoding> encodings = ContentEncoding.getEncoding(Compression.getProvidedEncoding(newEncoding));
            boolean compressed = encodings.size() == 2 && encodings.get(0) == ContentEncoding.GZIP;
            if (!compressed) {
                newContent.set(Compression.compress(newContent.get()));
            }

            // check max content length
            if (newContent.get().length > this.maxContentLength) {
                cors(req.response()).code(413).plain("Content too large").done();
                return;
            }

            long newExpiry = System.currentTimeMillis() + this.lifetimeMillis;

            /*this.server.getLoggingExecutor().submit(() -> {
                String hostname = null;
                try {
                    InetAddress inetAddress = InetAddress.getByName(ipAddress);
                    hostname = inetAddress.getCanonicalHostName();
                    if (ipAddress.equals(hostname)) {
                        hostname = null;
                    }
                } catch (Exception e) {
                    // ignore
                }*/

                String origin = req.header("Origin", null);
                LOGGER.info("[PUT]\n" +
                        "    key = " + path + "\n" +
                        "    new type = " + new String(newContentType.getBytes()) + "\n" +
                        "    new encoding = " + new String(newEncoding.getBytes()) + "\n" +
                        "    user agent = " + req.header("User-Agent", "null") + "\n" +
                        //"    origin = " + ipAddress + (hostname != null ? " (" + hostname + ")" : "") + "\n" +
                        "    ip = " + ipAddress + "\n" +
                        (origin == null ? "" : "    origin = " + origin + "\n") +
                        "    old content size = " + String.format("%,d", oldContent.getContent().length / 1024) + " KB" + "\n" +
                        "    new content size = " + String.format("%,d", newContent.get().length / 1024) + " KB" + "\n");
            //});

            // update the content instance with the new data
            oldContent.setContentType(newContentType);
            oldContent.setEncoding(newEncoding);
            oldContent.setExpiry(newExpiry);
            oldContent.setLastModified(System.currentTimeMillis());
            oldContent.setContent(newContent.get());

            // make the http response
            cors(req.response()).code(200)
                    .body(Content.EMPTY_BYTES)
                    .done();

            // save to disk
            this.contentStorageHandler.save(oldContent);
        }, this.contentStorageHandler.getExecutor());

        // mark that we're going to respond later
        return req.async();
    }

}
