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

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Weigher;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.rapidoid.http.MediaType;
import org.rapidoid.http.Req;
import org.rapidoid.http.Resp;
import org.rapidoid.setup.My;
import org.rapidoid.setup.On;
import org.rapidoid.u.U;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stupidly simple "pastebin" service.
 */
public class ByteBin {

    // Bootstrap
    public static void main(String[] args) {
        try {
            new ByteBin();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Empty byte array */
    private static final byte[] EMPTY_BYTES = new byte[0];

    /** Empty content instance */
    private static final Content EMPTY_CONTENT = new Content(null, MediaType.TEXT_PLAIN, Long.MAX_VALUE, EMPTY_BYTES);

    /** Number of bytes in a megabyte */
    private static final long MEGABYTE_LENGTH = 1024L * 1024L;

    /** Standard response function. (always add the CORS header)*/
    private static final Function<Resp, Resp> STANDARD_RESPONSE = resp -> resp.header("Access-Control-Allow-Origin", "*");

    /** Logger instance */
    private final Logger logger;

    /** Executor service for performing file based i/o */
    private final ScheduledExecutorService executor;

    /** Content cache - caches the raw byte data for the last x requested files */
    private final AsyncLoadingCache<String, Content> contentCache;

    /** Post rate limiter cache */
    private final RateLimiter postRateLimiter;

    /** Read rate limiter */
    private final RateLimiter readRateLimiter;

    /** The max content length in mb */
    private final long maxContentLength;

    /** Instance responsible for loading data from the filesystem */
    private final ContentLoader loader;

    /** Token generator */
    private final TokenGenerator tokenGenerator;

    // the path to store the content in
    private final Path contentPath;
    // the lifetime of content in milliseconds
    private final long lifetimeMillis;
    // web server host
    private final String host;
    // web server port
    private final int port;

    public ByteBin() throws Exception {
        // setup simple logger
        this.logger = setupLogger();
        this.logger.info("loading bytebin...");

        // load config
        Path configPath = Paths.get("config.json");
        Configuration config;

        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                config = new Configuration(new Gson().fromJson(reader, JsonObject.class));
            }
        } else {
            config = new Configuration(new JsonObject());
        }

        // setup executor
        this.executor = Executors.newScheduledThreadPool(
                config.getInt("corePoolSize", 16),
                new ThreadFactoryBuilder().setNameFormat("bytebin-io-%d").build()
        );

        // setup loader
        this.loader = new ContentLoader();

        // how many minutes to cache content for
        int cacheTimeMins = config.getInt("cacheExpiryMinutes", 10);

        // build content cache
        this.contentCache = Caffeine.newBuilder()
                .executor(this.executor)
                .expireAfterAccess(cacheTimeMins, TimeUnit.MINUTES)
                .maximumWeight(config.getInt("cacheMaxSizeMb", 200) * MEGABYTE_LENGTH)
                .weigher((Weigher<String, Content>) (path, content) -> content.content.length)
                .buildAsync(this.loader);

        // make a new token generator
        this.tokenGenerator = new TokenGenerator(config.getInt("keyLength", 7));

        // read other config settings
        this.contentPath = Paths.get("content");
        this.lifetimeMillis = TimeUnit.MINUTES.toMillis(config.getLong("lifetimeMinutes", TimeUnit.DAYS.toMinutes(1)));
        this.host = System.getProperty("server.host", config.getString("host", "127.0.0.1"));
        this.port = Integer.getInteger("server.port", config.getInt("port", 8080));
        this.maxContentLength = MEGABYTE_LENGTH * config.getInt("maxContentLengthMb", 10);

        // build rate limit caches
        this.postRateLimiter = new RateLimiter(
                config.getInt("postRateLimitPeriodMins", 10),
                config.getInt("postRateLimit", 30)
        );
        this.readRateLimiter = new RateLimiter(
                config.getInt("readRateLimitPeriodMins", 10),
                config.getInt("readRateLimit", 100)
        );

        // make directories
        Files.createDirectories(this.contentPath);

        // setup the web server
        defineRoutes();

        // schedule invalidation task
        this.executor.scheduleAtFixedRate(new InvalidationRunnable(), 1, cacheTimeMins, TimeUnit.MINUTES);
    }

    private void defineRoutes() {
        // define bind host & port
        On.address(this.host).port(this.port);

        // catch all errors & just return some generic error message
        My.errorHandler((req, resp, error) -> STANDARD_RESPONSE.apply(resp).code(404).plain("Invalid path"));

        // define option route handlers
        defineOptionsRoute("/post", "POST");
        defineOptionsRoute("/*", "GET");

        // define upload path
        On.post("/post").managed(false).serve(req -> {
            AtomicReference<byte[]> content = new AtomicReference<>(req.body());

            // ensure something was actually posted
            if (content.get().length == 0) return STANDARD_RESPONSE.apply(req.response()).code(400).plain("Missing content");
            // check rate limits
            if (this.postRateLimiter.check(req)) return STANDARD_RESPONSE.apply(req.response()).code(429).plain("Rate limit exceeded");

            // determine the mediatype
            MediaType mediaType = determineMediaType(req);

            // generate a key
            String key = this.tokenGenerator.generate();

            // is the content already compressed?
            boolean compressed = req.header("Content-Encoding", "").equals("gzip");

            // if compression is required at a later stage
            AtomicBoolean requiresCompression = new AtomicBoolean(false);

            // if it's not compressed, consider the effect of compression on the content length
            if (!compressed) {
                // if the max content length would be exceeded - try compressing
                if (content.get().length > this.maxContentLength) {
                    content.set(gzip(content.get()));
                } else {
                    // compress later
                    requiresCompression.set(true);
                }
            }

            // check max content length
            if (content.get().length > this.maxContentLength) return STANDARD_RESPONSE.apply(req.response()).code(413).plain("Content too large");

            // save the data to the filesystem
            this.executor.execute(() -> this.loader.save(key, mediaType, content.get(), requiresCompression.get()));

            // return the url location as plain content
            return STANDARD_RESPONSE.apply(req.response()).code(200).json(U.map("key", key));
        });

        // serve content
        On.get("/*").managed(false).cacheCapacity(0).serve(req -> {
            // get the requested path
            String path = req.path().substring(1);
            if (path.trim().isEmpty() || path.contains(".") || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
                return STANDARD_RESPONSE.apply(req.response()).code(404).plain("Invalid path");
            }

            // check rate limits
            if (this.readRateLimiter.check(req)) return STANDARD_RESPONSE.apply(req.response()).code(429).plain("Rate limit exceeded");

            // request the file from the cache async
            this.logger.info("Requesting: " + path);
            this.contentCache.get(path).whenComplete((content, throwable) -> {
                if (throwable != null || content == null || content.key == null || content.content.length == 0) {
                    STANDARD_RESPONSE.apply(req.response()).code(404).plain("Invalid path").done();
                    return;
                }

                // will the client accept the content in a compressed form?
                if (acceptsCompressed(req)) {
                    STANDARD_RESPONSE.apply(req.response()).code(200)
                            .header("Cache-Control", "public, max-age=86400")
                            .header("Content-Encoding", "gzip")
                            .body(content.content)
                            .contentType(content.mediaType)
                            .done();
                    return;
                }

                // need to uncompress
                byte[] uncompressed;
                try {
                    uncompressed = gunzip(content.content);
                } catch (IOException e) {
                    STANDARD_RESPONSE.apply(req.response()).code(404).plain("Unable to uncompress data").done();
                    return;
                }

                // return the data
                STANDARD_RESPONSE.apply(req.response()).code(200)
                        .header("Cache-Control", "public, max-age=86400")
                        .body(uncompressed)
                        .contentType(content.mediaType)
                        .done();
            });

            // mark that we're going to respond later
            return req.async();
        });
    }

    private static Logger setupLogger() {
        Logger logger = Logger.getLogger("bytebin");
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new Formatter() {
            private final DateFormat dateFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM);

            @Override
            public String format(LogRecord record) {
                return String.format(
                        "%s [%s] %s\n",
                        this.dateFormat.format(new Date(record.getMillis())),
                        record.getLevel().getName(),
                        record.getMessage()
                );
            }
        });
        logger.addHandler(consoleHandler);
        return logger;
    }

    private static void defineOptionsRoute(String path, String allowedMethod) {
        On.options(path).serve(req -> STANDARD_RESPONSE.apply(req.response())
                .header("Access-Control-Allow-Methods", allowedMethod)
                .header("Access-Control-Max-Age", "86400")
                .header("Access-Control-Allow-Headers", "Content-Type")
                .code(200)
                .body(new byte[0])
        );
    }

    private static MediaType determineMediaType(Req req) {
        MediaType mt = req.contentType();
        if (mt == null) {
            mt = MediaType.TEXT_PLAIN;
        }
        return mt;
    }

    private static boolean acceptsCompressed(Req req) {
        boolean acceptCompressed = false;
        String header = req.header("Accept-Encoding", null);
        if (header != null && Arrays.stream(header.split(", ")).anyMatch(s -> s.equals("gzip"))) {
            acceptCompressed = true;
        }
        return acceptCompressed;
    }

    private static byte[] gzip(byte[] buf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
            gzipOut.write(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] buf) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(buf);
        try (GZIPInputStream gzipIn = new GZIPInputStream(in)) {
            return ByteStreams.toByteArray(gzipIn);
        }
    }

    /**
     * Manages content i/o with the filesystem, including encoding an instance of {@link Content} into
     * a single array of bytes
     */
    private final class ContentLoader implements CacheLoader<String, Content> {

        @Override
        public Content load(String path) throws IOException {
            ByteBin.this.logger.info("Loading: " + path);

            // resolve the path within the content dir
            Path resolved = ByteBin.this.contentPath.resolve(path);
            return load(resolved);
        }

        public Content load(Path resolved) throws IOException {
            if (!Files.exists(resolved)) {
                return EMPTY_CONTENT;
            }

            // read the file into memory
            byte[] bytes = Files.readAllBytes(resolved);
            if (bytes.length == 0) {
                return EMPTY_CONTENT;
            }

            // create a byte array input stream for the file
            // we need to decode the content from the storage format
            ByteArrayDataInput in = ByteStreams.newDataInput(bytes);

            // read key
            String key = in.readUTF();

            // read content type
            byte[] contentType = new byte[in.readInt()];
            in.readFully(contentType);
            MediaType mediaType = MediaType.of(new String(contentType));

            // read expiry
            long expiry = in.readLong();

            // read content
            byte[] content = new byte[in.readInt()];
            in.readFully(content);

            return new Content(key, mediaType, expiry, content);
        }

        public void save(String key, MediaType mediaType, byte[] content, boolean requiresCompression) {
            if (requiresCompression) {
                content = gzip(content);
            }

            // create a byte array output stream for the content
            // we encode the content & its attributes into the same file
            ByteArrayDataOutput out = ByteStreams.newDataOutput();

            // write name
            out.writeUTF(key);

            // write content type
            byte[] contextType = mediaType.getBytes();
            out.writeInt(contextType.length);
            out.write(contextType);

            // write expiry time
            long expiry = System.currentTimeMillis() + ByteBin.this.lifetimeMillis;
            out.writeLong(expiry);

            // write content
            out.writeInt(content.length);
            out.write(content);

            // form overall byte array
            byte[] bytes = out.toByteArray();

            // resolve the path to save at
            Path path = ByteBin.this.contentPath.resolve(key);
            ByteBin.this.logger.info("Writing '" + key + "' of type '" + new String(mediaType.getBytes()) + "'");

            // write to file
            try {
                Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
            } catch (IOException e) {
                if (e instanceof FileAlreadyExistsException) {
                    ByteBin.this.logger.info("File '" + key + "' already exists.");
                    return;
                }
                e.printStackTrace();
            }

            // add directly to the cache
            // it's quite likely that the file will be requested only a few seconds after it is uploaded
            Content c = new Content(key, mediaType, expiry, content);
            ByteBin.this.contentCache.put(key, CompletableFuture.completedFuture(c));
        }
    }

    /**
     * Handles a rate limit
     */
    private static final class RateLimiter {
        /** Rate limiter cache - allow x "actions" every x minutes */
        private final LoadingCache<String, AtomicInteger> rateLimiter;
        /** The number of actions allowed in each period  */
        private final int actionsPerCycle;

        public RateLimiter(int periodMins, int actionsPerCycle) {
            this.rateLimiter = Caffeine.newBuilder()
                    .expireAfterWrite(periodMins, TimeUnit.MINUTES)
                    .build(key -> new AtomicInteger(0));
            this.actionsPerCycle = actionsPerCycle;
        }

        public boolean check(Req req) {
            String ipAddress = req.header("x-real-ip", null);
            if (ipAddress == null) {
                ipAddress = req.clientIpAddress();
            }

            //noinspection ConstantConditions
            return this.rateLimiter.get(ipAddress).incrementAndGet() > this.actionsPerCycle;
        }
    }

    /**
     * Encapsulates content within the service
     */
    private static final class Content {
        private final String key;
        private final MediaType mediaType;
        private final long expiry;
        private final byte[] content;

        private Content(String key, MediaType mediaType, long expiry, byte[] content) {
            this.key = key;
            this.mediaType = mediaType;
            this.expiry = expiry;
            this.content = content;
        }

        private boolean shouldExpire() {
            return this.expiry < System.currentTimeMillis();
        }
    }

    /**
     * Task to delete expired content
     */
    private final class InvalidationRunnable implements Runnable {
        @Override
        public void run() {
            try (Stream<Path> stream = Files.list(ByteBin.this.contentPath)) {
                stream.filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                Content content = ByteBin.this.loader.load(path);
                                if (content.shouldExpire()) {
                                    ByteBin.this.logger.info("Expired: " + path.getFileName().toString());
                                    Files.delete(path);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Json config wrapper class
     */
    private static final class Configuration {
        private final JsonObject jsonObject;

        public Configuration(JsonObject jsonObject) {
            this.jsonObject = jsonObject;
        }

        public String getString(String path, String def) {
            JsonElement e = this.jsonObject.get(path);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                return def;
            }
            return e.getAsString();
        }

        public int getInt(String path, int def) {
            JsonElement e = this.jsonObject.get(path);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                return def;
            }
            return e.getAsInt();
        }

        public long getLong(String path, long def) {
            JsonElement e = this.jsonObject.get(path);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                return def;
            }
            return e.getAsLong();
        }
    }

    /**
     * Randomly generates tokens for new content uploads
     */
    private static final class TokenGenerator {
        /** Pattern to match invalid tokens */
        public static final Pattern INVALID_TOKEN_PATTERN = Pattern.compile("[^a-zA-Z0-9]");

        /** Characters to include in a token */
        private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

        private final int length;
        private final SecureRandom random = new SecureRandom();

        public TokenGenerator(int length) {
            Preconditions.checkArgument(length > 1);
            this.length = length;
        }

        public String generate() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < this.length; i++) {
                sb.append(CHARACTERS.charAt(this.random.nextInt(CHARACTERS.length())));
            }
            return sb.toString();
        }
    }
}
