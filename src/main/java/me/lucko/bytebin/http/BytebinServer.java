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
import io.jooby.Jooby;
import io.jooby.MediaType;
import io.jooby.ReactiveSupport;
import io.jooby.RouterOptions;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import io.jooby.handler.AssetHandler;
import io.jooby.handler.AssetSource;
import io.jooby.handler.Cors;
import io.jooby.handler.CorsHandler;
import io.prometheus.client.Counter;
import me.lucko.bytebin.Bytebin;
import me.lucko.bytebin.content.ContentLoader;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.http.admin.BulkDeleteHandler;
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.util.ExceptionHandler;
import me.lucko.bytebin.util.ExpiryHandler;
import me.lucko.bytebin.util.RateLimitHandler;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

public class BytebinServer extends Jooby {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(BytebinServer.class);

    private static final Counter REQUESTS_COUNTER = Counter.build()
            .name("bytebin_requests_total")
            .help("The amount of requests handled")
            .labelNames("method", "useragent")
            .register();

    public BytebinServer(ContentStorageHandler storageHandler, ContentLoader contentLoader, LogHandler logHandler, boolean metrics, RateLimitHandler rateLimitHandler, RateLimiter postRateLimiter, RateLimiter putRateLimiter, RateLimiter readRateLimiter, TokenGenerator contentTokenGenerator, long maxContentLength, ExpiryHandler expiryHandler, Map<String, String> hostAliases, Set<String> adminApiKeys) {
        setRouterOptions(new RouterOptions().setTrustProxy(true));

        use(ReactiveSupport.concurrent());

        // catch all errors & just return some generic error message
        error((ctx, cause, code) -> {
            Throwable rootCause = cause;
            while (rootCause instanceof CompletionException && rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }

            if (rootCause instanceof StatusCodeException) {
                // handle expected errors
                ctx.setResponseCode(((StatusCodeException) rootCause).getStatusCode())
                        .setResponseType(MediaType.TEXT)
                        .send(rootCause.getMessage());
            } else {
                // handle unexpected errors: log stack trace and send a generic response
                LOGGER.error("Error thrown by handler", cause);
                ExceptionHandler.UNCAUGHT_ERROR_COUNTER.labels(cause.getClass().getSimpleName()).inc();
                ctx.setResponseCode(StatusCode.NOT_FOUND)
                        .setResponseType(MediaType.TEXT)
                        .send("Invalid path");
            }
        });

        AssetSource wwwFiles = AssetSource.create(Bytebin.class.getClassLoader(), "/www/");
        AssetSource fourOhFour = path -> { throw new StatusCodeException(StatusCode.NOT_FOUND, "Not found"); };

        // serve index page or favicon, otherwise 404
        assets("/*", new AssetHandler(wwwFiles, fourOhFour).setMaxAge(Duration.ofDays(1)));

        // healthcheck endpoint
        get("/health", ctx -> {
            ctx.setResponseHeader("Cache-Control", "no-cache");
            return "{\"status\":\"ok\"}";
        });

        // metrics endpoint
        if (metrics) {
            get("/metrics", new MetricsHandler());
        }

        // define route handlers
        routes(() -> {
            use(new CorsHandler(new Cors()
                    .setUseCredentials(false)
                    .setMaxAge(Duration.ofDays(1))
                    .setMethods("POST", "PUT")
                    .setHeaders("Content-Type", "Accept", "Origin", "Content-Encoding", "Allow-Modification")));

            PostHandler postHandler = new PostHandler(this, logHandler, postRateLimiter, rateLimitHandler, storageHandler, contentLoader, contentTokenGenerator, maxContentLength, expiryHandler, hostAliases);
            post("/post", postHandler);
            put("/post", postHandler);
        });

        routes(() -> {
            use(new CorsHandler(new Cors()
                    .setUseCredentials(false)
                    .setMaxAge(Duration.ofDays(1))
                    .setMethods("GET", "PUT")
                    .setHeaders("Content-Type", "Accept", "Origin", "Content-Encoding", "Authorization")));

            get("/{id:[a-zA-Z0-9]+}", new GetHandler(this, logHandler, readRateLimiter, rateLimitHandler, contentLoader));
            put("/{id:[a-zA-Z0-9]+}", new UpdateHandler(this, logHandler, putRateLimiter, rateLimitHandler, storageHandler, contentLoader, maxContentLength, expiryHandler));
        });

        routes(() -> {
            post("/admin/bulkdelete", new BulkDeleteHandler(this, storageHandler, contentLoader, adminApiKeys));
        });
    }

    public static String getMetricsLabel(Context ctx) {
        String origin = ctx.header("Origin").valueOrNull();
        if (origin != null) {
            return origin;
        }

        String userAgent = ctx.header("User-Agent").valueOrNull();
        if (userAgent != null) {
            return userAgent;
        }

        return "unknown";
    }

    public static void recordRequest(String method, Context ctx) {
        recordRequest(method, getMetricsLabel(ctx));
    }

    public static void recordRequest(String method, String metricsLabel) {
        REQUESTS_COUNTER.labels(method, metricsLabel).inc();
    }

}
