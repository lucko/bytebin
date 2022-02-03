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

package me.lucko.bytebin;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentLoader;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.http.BytebinServer;
import me.lucko.bytebin.util.Configuration;
import me.lucko.bytebin.util.Configuration.Option;
import me.lucko.bytebin.util.EnvVars;
import me.lucko.bytebin.util.ExpiryHandler;
import me.lucko.bytebin.util.RateLimitHandler;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.io.IoBuilder;

import io.jooby.ExecutionMode;
import io.jooby.Jooby;
import io.prometheus.client.hotspot.DefaultExports;

import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stupidly simple "pastebin" service.
 */
public final class Bytebin implements AutoCloseable {

    /** Logger instance */
    private static final Logger LOGGER;

    static {
        EnvVars.read();
        LOGGER = LogManager.getLogger(Bytebin.class);
    }

    // Bootstrap
    public static void main(String[] args) throws Exception {
        // setup logging
        System.setOut(IoBuilder.forLogger(LOGGER).setLevel(Level.INFO).buildPrintStream());
        System.setErr(IoBuilder.forLogger(LOGGER).setLevel(Level.ERROR).buildPrintStream());

        // setup a new bytebin instance
        Configuration config = Configuration.load(Paths.get("config.json"));
        Bytebin bytebin = new Bytebin(config);
        Runtime.getRuntime().addShutdownHook(new Thread(bytebin::close, "Bytebin Shutdown Thread"));
    }

    /** Executor service for performing file based i/o */
    private final ScheduledExecutorService executor;

    /** The web server instance */
    private final BytebinServer server;

    public Bytebin(Configuration config) throws Exception {
        // setup simple logger
        LOGGER.info("loading bytebin...");

        // setup executor
        this.executor = Executors.newScheduledThreadPool(
                config.getInt(Option.EXECUTOR_POOL_SIZE, 16),
                new ThreadFactoryBuilder().setNameFormat("bytebin-io-%d").build()
        );

        // setup loader
        ContentStorageHandler contentStorageHandler = new ContentStorageHandler(
                this.executor,
                Paths.get("content")
        );

        // build content loader
        ContentLoader contentLoader = ContentLoader.create(
                contentStorageHandler,
                config.getInt(Option.CACHE_EXPIRY, 10),
                config.getInt(Option.CACHE_MAX_SIZE, 200)
        );

        ExpiryHandler expiryHandler = new ExpiryHandler(
                config.getLong(Option.MAX_CONTENT_LIFETIME, -1), // never expire by default
                config.getLongMap(Option.MAX_CONTENT_LIFETIME_USER_AGENTS)
        );

        boolean metrics = config.getBoolean(Option.METRICS, false);
        if (metrics) {
            DefaultExports.initialize();
        }

        // setup the web server
        this.server = (BytebinServer) Jooby.createApp(new String[0], ExecutionMode.EVENT_LOOP, () -> new BytebinServer(
                contentStorageHandler,
                contentLoader,
                config.getString(Option.HOST, "0.0.0.0"),
                config.getInt(Option.PORT, 8080),
                metrics,
                new RateLimitHandler(config.getStringList(Option.API_KEYS)),
                new RateLimiter(
                        // by default, allow posts at a rate of 30 times every 10 minutes (every 20s)
                        config.getInt(Option.POST_RATE_LIMIT_PERIOD, 10),
                        config.getInt(Option.POST_RATE_LIMIT, 30)
                ),
                new RateLimiter(
                        // by default, allow updates at a rate of 20 times every 2 minutes (every 6s)
                        config.getInt(Option.UPDATE_RATE_LIMIT_PERIOD, 2),
                        config.getInt(Option.UPDATE_RATE_LIMIT, 20)
                ),
                new RateLimiter(
                        // by default, allow reads at a rate of 30 times every 2 minutes (every 4s)
                        config.getInt(Option.READ_RATE_LIMIT_PERIOD, 2),
                        config.getInt(Option.READ_RATE_LIMIT, 30)
                ),
                new TokenGenerator(config.getInt(Option.KEY_LENGTH, 7)),
                (Content.MEGABYTE_LENGTH * config.getInt(Option.MAX_CONTENT_LENGTH, 10)),
                expiryHandler
        ));
        this.server.start();

        // schedule invalidation task
        if (expiryHandler.hasExpiryTimes() || metrics) {
            this.executor.scheduleWithFixedDelay(contentStorageHandler::runInvalidationAndRecordMetrics, 1, 15, TimeUnit.MINUTES);
        }
    }

    @Override
    public void close() {
        this.server.stop();
        this.executor.shutdown();
        try {
            this.executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Exception whilst shutting down executor", e);
        }
    }

}
