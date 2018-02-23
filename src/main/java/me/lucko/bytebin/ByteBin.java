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
import com.github.benmanes.caffeine.cache.Weigher;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.rapidoid.http.MediaType;
import org.rapidoid.http.Resp;
import org.rapidoid.setup.My;
import org.rapidoid.setup.On;
import org.rapidoid.u.U;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * stupidly simple "pastebin" service.
 */
public class ByteBin {

    // Bootstrap
    public static void main(String[] args) {
        try {
            new ByteBin().setup();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final Content NULL_CONTENT = new Content(null, MediaType.TEXT_PLAIN, Long.MAX_VALUE, EMPTY_BYTES);

    // matches invalid tokens
    private static final Pattern INVALID_TOKEN_PATTERN = Pattern.compile("[^a-zA-Z0-9]");

    // number of bytes in a megabyte
    private static final long MEGABYTE_LENGTH = 1024L * 1024L;

    // always add the CORS header
    private static final Function<Resp, Resp> STANDARD_RESPONSE = resp -> resp.header("Access-Control-Allow-Origin", "*");

    // bytebin logger instance
    private final Logger logger;

    // executor service for performing file based i/o
    private final ScheduledExecutorService executor;
    // content cache - caches the raw byte data for the last x requested files
    private final AsyncLoadingCache<String, Content> contentCache;
    // instance responsible for loading data from the filesystem
    private final Loader loader;

    // generates content tokens
    private final TokenGenerator tokenGenerator;

    // the path to store the content in
    private final Path contentPath;
    // the lifetime of content in milliseconds
    private final long lifetime;
    // web server host
    private final String host;
    // web server port
    private final int port;

    public ByteBin() throws Exception {
        // setup simple logger
        this.logger = Logger.getLogger("bytebin");
        this.logger.setLevel(Level.ALL);
        this.logger.setUseParentHandlers(false);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new Formatter() {
            private final DateFormat dateFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM);

            @Override
            public String format(LogRecord record) {
                return String.format(
                        "%s [%s] %s\n",
                        dateFormat.format(new Date(record.getMillis())),
                        record.getLevel().getName(),
                        record.getMessage()
                );
            }
        });
        this.logger.addHandler(consoleHandler);

        this.logger.info("loading bytebin...");

        // setup cache & executor
        this.executor = Executors.newScheduledThreadPool(16, new ThreadFactoryBuilder().setNameFormat("bytebin-io-%d").build());
        this.loader = new Loader();
        this.contentCache = Caffeine.newBuilder()
                .executor(this.executor)
                // cache for 10 mins
                .expireAfterAccess(10, TimeUnit.MINUTES)
                // up to a max size of 5mb
                .maximumWeight(5 * MEGABYTE_LENGTH)
                .weigher((Weigher<String, Content>) (path, content) -> content.content.length)
                .buildAsync(this.loader);

        // load config
        Path configPath = Paths.get("config.json");
        JsonObject config;
        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                config = new Gson().fromJson(reader, JsonObject.class);
            }
        } else {
            config = new JsonObject();
            config.addProperty("host", "127.0.0.1");
            config.addProperty("port", 8080);
            config.addProperty("keyLength", 7);
            config.addProperty("lifetime", TimeUnit.DAYS.toMillis(1));
            // save
            try (BufferedWriter writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(config, writer);
            }
        }

        // make a new token generator
        this.tokenGenerator = new TokenGenerator(config.get("keyLength").getAsInt());

        this.contentPath = Paths.get("content");
        this.lifetime = config.get("lifetime").getAsLong();
        this.host = System.getProperty("server.host", config.get("host").getAsString());
        this.port = Integer.getInteger("server.port", config.get("port").getAsInt());
    }

    public void setup() throws Exception {
        Files.createDirectories(this.contentPath);

        // define bind host & port
        On.address(this.host).port(this.port);

        // catch all errors & just return some generic error message
        My.errorHandler((req, resp, error) -> STANDARD_RESPONSE.apply(resp).code(404).plain("Invalid path"));

        // define upload path
        On.options("/post").serve(req -> req.response()
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "POST")
                .header("Access-Control-Max-Age", "86400")
                .header("Access-Control-Allow-Headers", "Content-Type")
                .code(200)
                .body(new byte[0])
        );
        On.post("/post").serve(req -> {
            byte[] content = req.body();
            if (content.length == 0) {
                return STANDARD_RESPONSE.apply(req.response()).code(400).plain("Missing file");
            }

            // determine the mediatype
            MediaType mediaType;
            {
                MediaType mt = req.contentType();
                if (mt == null) {
                    mt = MediaType.TEXT_PLAIN;
                }
                mediaType = mt;
            }

            // generate a key
            String key = this.tokenGenerator.generate();

            // save the data to the filesystem
            this.executor.execute(() -> this.loader.save(key, mediaType, content));

            // return the url location as plain content
            return STANDARD_RESPONSE.apply(req.response()).code(200).json(U.map("key", key));
        });

        // catch all
        On.options("/*").serve(req -> req.response()
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET")
                .header("Access-Control-Max-Age", "86400")
                .header("Access-Control-Allow-Headers", "Content-Type")
                .code(200)
                .body(new byte[0])
        );
        On.get("/*").cacheCapacity(0).serve(req -> {
            // get the requested path
            String path = req.path().substring(1);
            if (path.trim().isEmpty() || path.contains(".") || INVALID_TOKEN_PATTERN.matcher(path).find()) {
                return STANDARD_RESPONSE.apply(req.response()).code(404).plain("Invalid path");
            }

            this.logger.info("Requesting: " + path);

            // request the file from the cache async
            this.contentCache.get(path).whenComplete((content, throwable) -> {
                if (throwable != null || content == null || content.key == null || content.content.length == 0) {
                    STANDARD_RESPONSE.apply(req.response()).code(404).plain("Invalid path").done();
                    return;
                }

                // return the data
                STANDARD_RESPONSE.apply(req.response()).code(200).body(content.content).contentType(content.mediaType).done();
            });

            // mark that we're going to respond later
            return req.async();
        });

        // clear cache
        this.executor.scheduleAtFixedRate(() -> {
            try (Stream<Path> stream = Files.list(this.contentPath)) {
                stream.filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                Content content = this.loader.load(path);
                                if (content.shouldExpire()) {
                                    this.logger.info("Expired: " + path.getFileName().toString());
                                    Files.delete(path);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, 10, 120, TimeUnit.MINUTES);
    }

    private final class Loader implements CacheLoader<String, Content> {

        @Override
        public Content load(String path) throws IOException {
            ByteBin.this.logger.info("Loading: " + path);

            // resolve the path within the content dir
            Path resolved = ByteBin.this.contentPath.resolve(path);
            return load(resolved);
        }

        public Content load(Path resolved) throws IOException {
            if (!Files.exists(resolved)) {
                return NULL_CONTENT;
            }

            // read the full bytes
            byte[] bytes = Files.readAllBytes(resolved);
            if (bytes.length == 0) {
                return NULL_CONTENT;
            }

            ByteArrayDataInput in = ByteStreams.newDataInput(bytes);

            // read key
            String key = in.readUTF();

            // read content type
            int contentTypeLength = in.readInt();
            byte[] contentType = new byte[contentTypeLength];
            in.readFully(contentType);
            MediaType mediaType = MediaType.of(new String(contentType));

            // read expiry
            long expiry = in.readLong();

            // read content length
            int contentLength = in.readInt();
            byte[] content = new byte[contentLength];
            in.readFully(content);

            return new Content(key, mediaType, expiry, content);
        }

        public void save(String key, MediaType mediaType, byte[] content) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();

            // write name
            out.writeUTF(key);

            // write content type
            byte[] contextType = mediaType.getBytes();
            out.writeInt(contextType.length);
            out.write(contextType);

            // write expiry time
            out.writeLong(System.currentTimeMillis() + ByteBin.this.lifetime);

            // write content
            out.writeInt(content.length);
            out.write(content);

            // form overall byte array
            byte[] bytes = out.toByteArray();

            Path path = ByteBin.this.contentPath.resolve(key);
            ByteBin.this.logger.info("Writing '" + key + "' of type '" + new String(mediaType.getBytes()) + "'");

            try {
                Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
            } catch (IOException e) {
                if (e instanceof FileAlreadyExistsException) {
                    ByteBin.this.logger.info("File '" + key + "' already exists.");
                    return;
                }
                e.printStackTrace();
            }
        }
    }

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

        public boolean shouldExpire() {
            return this.expiry < System.currentTimeMillis();
        }
    }

    private static class TokenGenerator {
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
