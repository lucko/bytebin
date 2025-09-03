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

package me.lucko.bytebin.util;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Json config wrapper class
 */
public class Configuration {

    public static Configuration load(Path configPath) throws IOException {
        Configuration config;
        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                config = new Configuration(new Gson().fromJson(reader, JsonObject.class));
            }
        } else {
            config = new Configuration(new JsonObject());
        }
        return config;
    }

    private final JsonObject jsonObject;

    public Configuration(JsonObject jsonObject) {
        this.jsonObject = jsonObject;
    }

    private <T> T get(Option option, T def, Function<String, T> parser, Function<JsonElement, T> jsonParser) {
        String value = System.getProperty(option.keySystemProperty);
        if (value != null) {
            return parser.apply(value);
        }

        value = System.getenv(option.keyEnvironmentVariable);
        if (value != null) {
            return parser.apply(value);
        }

        JsonElement e = this.jsonObject.get(option.keyJson);
        if (e != null) {
            return jsonParser.apply(e);
        }

        return def;
    }

    public String getString(Option option, String def) {
        return get(option, def, Function.identity(), JsonElement::getAsString);
    }

    public int getInt(Option option, int def) {
        return get(option, def, Integer::parseInt, JsonElement::getAsInt);
    }

    public double getDouble(Option option, double def) {
        return get(option, def, Double::parseDouble, JsonElement::getAsDouble);
    }

    public long getLong(Option option, long def) {
        return get(option, def, Long::parseLong, JsonElement::getAsLong);
    }

    public boolean getBoolean(Option option, boolean def) {
        return get(option, def, Boolean::parseBoolean, JsonElement::getAsBoolean);
    }

    public Map<String, String> getStringMap(Option option) {
        return get(option, ImmutableMap.of(),
                str -> Splitter.on(',').withKeyValueSeparator('=').split(str).entrySet().stream()
                        .collect(ImmutableMap.toImmutableMap(
                                ent -> ent.getKey().trim(),
                                ent -> ent.getValue().trim()
                        )),
                ele -> ele.getAsJsonObject().entrySet().stream()
                        .collect(ImmutableMap.toImmutableMap(
                                Map.Entry::getKey,
                                ent -> ent.getValue().getAsString()
                        ))
        );
    }

    public Map<String, Long> getLongMap(Option option) {
        return get(option, ImmutableMap.of(),
                str -> Splitter.on(',').withKeyValueSeparator('=').split(str).entrySet().stream()
                        .collect(ImmutableMap.toImmutableMap(
                                ent -> ent.getKey().trim(),
                                ent -> Long.parseLong(ent.getValue())
                        )),
                ele -> ele.getAsJsonObject().entrySet().stream()
                        .collect(ImmutableMap.toImmutableMap(
                                Map.Entry::getKey,
                                ent -> ent.getValue().getAsLong()
                        ))
        );
    }

    public Collection<String> getStringList(Option option) {
        return get(option, ImmutableList.of(),
                str -> Splitter.on(',').splitToStream(str)
                        .map(String::trim)
                        .collect(Collectors.toList()),
                ele -> StreamSupport.stream(ele.getAsJsonArray().spliterator(), false)
                        .map(JsonElement::getAsString)
                        .collect(Collectors.toList())
        );
    }

    public enum Option {

        HOST("host", "bytebin.http.host"),
        PORT("port", "bytebin.http.port"),
        HTTP_HOST_ALIASES("httpHostAliases", "bytebin.http.hostaliases"),
        LOCAL_ASSET_PATH("localAssetPath", "bytebin.http.local.asset.path"),

        METRICS("metricsEnabled", "bytebin.metrics.enabled"),
        AUDIT_ON_STARTUP("startupAudit", "bytebin.startup.audit"),

        LOGGING_HTTP_URI("loggingHttpUri", "bytebin.logging.http.uri"),
        LOGGING_HTTP_FLUSH_PERIOD("loggingHttpFlushPeriodSeconds", "bytebin.logging.http.flush.period"), // seconds

        KEY_LENGTH("keyLength", "bytebin.misc.keylength"),
        EXECUTOR_POOL_SIZE("corePoolSize", "bytebin.misc.corepoolsize"),
        IO_THREADS("ioThreads", "bytebin.misc.iothreads"),

        S3("s3", "bytebin.storage.s3"),
        S3_BUCKET("s3Bucket", "bytebin.storage.s3.bucket"),
        S3_EXPIRY_THRESHOLD("s3ExpiryThreshold", "bytebin.storage.s3.expiry.threshold"), // minutes
        S3_SIZE_THRESHOLD("s3SizeThreshold", "bytebin.storage.s3.size.threshold"), // kb

        MAX_CONTENT_LENGTH("maxContentLengthMb", "bytebin.content.maxsize"), // mb
        MAX_CONTENT_LIFETIME("lifetimeMinutes", "bytebin.content.expiry"), // minutes
        MAX_CONTENT_LIFETIME_USER_AGENTS("lifetimeMinutesByUserAgent", "bytebin.content.expiry.useragents"), // minutes

        CACHE_EXPIRY("cacheExpiryMinutes", "bytebin.cache.expiry"), // minutes
        CACHE_MAX_SIZE("cacheMaxSizeMb", "bytebin.cache.maxsize"), // mb

        RATELIMIT_API_KEYS("apiKeys", "bytebin.ratelimit.apikeys"), // list
        ADMIN_API_KEYS("adminApiKeys", "bytebin.admin.apikeys"), // list

        POST_RATE_LIMIT_PERIOD("postRateLimitPeriodMins", "bytebin.ratelimit.post.period"), // minutes
        POST_RATE_LIMIT("postRateLimit", "bytebin.ratelimit.post.amount"),
        UPDATE_RATE_LIMIT_PERIOD("updateRateLimitPeriodMins", "bytebin.ratelimit.update.period"), // minutes
        UPDATE_RATE_LIMIT("updateRateLimit", "bytebin.ratelimit.update.amount"),
        READ_RATE_LIMIT_PERIOD("readRateLimitPeriodMins", "bytebin.ratelimit.read.period"), // minutes
        READ_RATE_LIMIT("readRateLimit", "bytebin.ratelimit.read.amount"),
        READ_NOTFOUND_RATE_LIMIT_PERIOD("readFailedRateLimitPeriodMins", "bytebin.ratelimit.read.notfound.period"), // minutes
        READ_NOTFOUND_RATE_LIMIT_PERIOD_MULTIPLIER("readFailedRateLimitPeriodMultiplier", "bytebin.ratelimit.read.notfound.period.multiplier"), // minutes
        READ_NOTFOUND_RATE_LIMIT_PERIOD_MAX("readFailedRateLimitPeriodMaxMins", "bytebin.ratelimit.read.notfound.period.max"), // minutes
        READ_NOTFOUND_RATE_LIMIT("readFailedRateLimit", "bytebin.ratelimit.read.notfound.amount");

        final String keyJson;
        final String keySystemProperty;
        final String keyEnvironmentVariable;

        Option(String keyJson, String keySystemProperty) {
            this.keyJson = keyJson;
            this.keySystemProperty = keySystemProperty;
            this.keyEnvironmentVariable = keySystemProperty.toUpperCase(Locale.ROOT).replace('.', '_');
        }

    }
}
