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

import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentCache;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.http.BytebinServer;
import me.lucko.bytebin.util.Configuration;
import me.lucko.bytebin.util.RateLimiter;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.io.IoBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stupidly simple "pastebin" service.
 */
public final class Bytebin implements AutoCloseable {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(Bytebin.class);

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
                config.getInt("corePoolSize", 16),
                new ThreadFactoryBuilder().setNameFormat("bytebin-io-%d").build()
        );

        // setup loader
        ContentStorageHandler contentStorageHandler = new ContentStorageHandler(
                this.executor,
                Paths.get("content")
        );

        // build content cache
        ContentCache contentCache = new ContentCache(
                contentStorageHandler,
                config.getInt("cacheExpiryMinutes", 10),
                config.getInt("cacheMaxSizeMb", 200)
        );

        // load index page
        byte[] indexPage;
        try (InputStreamReader in = new InputStreamReader(Bytebin.class.getResourceAsStream("/index.html"), StandardCharsets.UTF_8)) {
            indexPage = CharStreams.toString(in).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // setup the web server
        this.server = new BytebinServer(
                contentStorageHandler,
                contentCache,
                System.getProperty("server.host", config.getString("host", "127.0.0.1")),
                Integer.getInteger("server.port", config.getInt("port", 8080)),
                new RateLimiter(
                        // by default, allow posts at a rate of 3 times per min (every 20s)
                        config.getInt("postRateLimitPeriodMins", 10),
                        config.getInt("postRateLimit", 30)
                ),
                new RateLimiter(
                        // by default, allow updates at a rate of 15 times per min (every 4s)
                        config.getInt("updateRateLimitPeriodMins", 2),
                        config.getInt("updateRateLimit", 26)
                ),
                new RateLimiter(
                        // by default, allow reads at a rate of 15 times per min (every 4s)
                        config.getInt("readRateLimitPeriodMins", 2),
                        config.getInt("readRateLimit", 30)
                ),
                indexPage,
                new TokenGenerator(config.getInt("keyLength", 7)),
                (Content.MEGABYTE_LENGTH * config.getInt("maxContentLengthMb", 10)),
                TimeUnit.MINUTES.toMillis(config.getLong("lifetimeMinutes", TimeUnit.DAYS.toMinutes(1))),
                config.getLongMap("lifetimeMinutesByUserAgent").entrySet().stream().collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, e -> TimeUnit.MINUTES.toMillis(e.getValue())))
        );
        this.server.start();

        // schedule invalidation task
        this.executor.scheduleWithFixedDelay(contentStorageHandler::runInvalidation, 1, contentCache.getCacheTimeMins(), TimeUnit.MINUTES);
    }

    @Override
    public void close() {
        this.server.halt();
        this.executor.shutdown();
        try {
            this.executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Exception whilst shutting down executor", e);
        }
    }

}
