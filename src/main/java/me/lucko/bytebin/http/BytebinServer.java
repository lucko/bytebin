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
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rapidoid.http.Req;
import org.rapidoid.http.Resp;
import org.rapidoid.setup.Setup;

import java.util.Map;

public class BytebinServer {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(BytebinServer.class);

    private final Setup server;

    public BytebinServer(ContentStorageHandler contentStorageHandler, ContentCache contentCache, String host, int port, RateLimiter postRateLimiter, RateLimiter putRateLimiter, RateLimiter readRateLimiter, byte[] indexPage, TokenGenerator contentTokenGenerator, long maxContentLength, long lifetimeMillis, Map<String, Long> lifetimeMillisByUserAgent) {
        this.server = Setup.create("bytebin");
        this.server.address(host).port(port);

        // catch all errors & just return some generic error message
        this.server.custom().errorHandler((req, resp, error) -> {
            LOGGER.error("Error thrown by handler", error);
            return cors(resp).code(404).plain("Invalid path");
        });

        // define route handlers
        defineOptionsRoute(this.server, "/post", "POST", "Content-Type, Content-Encoding, Allow-Modification");
        defineOptionsRoute(this.server, "/*", "GET, PUT", "Content-Type, Content-Encoding, Modification-Key");
        this.server.page("/").html(indexPage);
        this.server.post("/post").managed(false).serve(new PostHandler(this, postRateLimiter, contentStorageHandler, contentCache, contentTokenGenerator, maxContentLength, lifetimeMillis, lifetimeMillisByUserAgent));
        this.server.get("/*").managed(false).cacheCapacity(0).serve(new GetHandler(this, readRateLimiter, contentCache));
        this.server.put("/*").managed(false).cacheCapacity(0).serve(new PutHandler(this, putRateLimiter, contentStorageHandler, contentCache, maxContentLength, lifetimeMillis));
    }

    public void start() {
        this.server.activate();
    }

    public void halt() {
        this.server.halt();
    }

    private static void defineOptionsRoute(Setup setup, String path, String allowedMethod, String allowedHeaders) {
        setup.options(path).serve(req -> cors(req.response())
                .header("Access-Control-Allow-Methods", allowedMethod)
                .header("Access-Control-Max-Age", "86400")
                .header("Access-Control-Allow-Headers", allowedHeaders)
                .code(200)
                .body(Content.EMPTY_BYTES)
        );
    }

    /** Standard response function. (always add the CORS header)*/
    static Resp cors(Resp resp) {
        return resp.header("Access-Control-Allow-Origin", "*");
    }

    static String getIpAddress(Req req) {
        String ipAddress = req.header("x-real-ip", null);
        if (ipAddress == null) {
            ipAddress = req.clientIpAddress();
        }
        return ipAddress;
    }

}
