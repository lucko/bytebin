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

package me.lucko.bytebin.content;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.dao.GenericRawResults;
import com.j256.ormlite.field.DataType;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;

import me.lucko.bytebin.content.storage.StorageBackend;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.prometheus.client.Gauge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The content index is a database storing metadata about the content stored in bytebin.
 *
 * It is merely an index, and can be regenerated at any time from the raw data (stored in the backend).
 * The primary use is to track content expiry times, and to determine which backend bytebin should
 * read from if the content isn't already cached in memory. It is also used for metrics.
 */
public class ContentIndexDatabase implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(ContentIndexDatabase.class);

    private static final Gauge STORED_CONTENT_AMOUNT_GAUGE = Gauge.build()
            .name("bytebin_content")
            .help("The amount of stored content")
            .labelNames("type", "backend")
            .register();

    private static final Gauge STORED_CONTENT_SIZE_GAUGE = Gauge.build()
            .name("bytebin_content_size")
            .help("The size of stored content")
            .labelNames("type", "backend")
            .register();

    public static ContentIndexDatabase initialise(Collection<StorageBackend> backends) throws SQLException {
        // ensure the db directory exists, sqlite won't create it
        try {
            Files.createDirectories(Paths.get("db"));
        } catch (IOException e) {
            // ignore
        }

        boolean exists = Files.exists(Paths.get("db/bytebin.db"));
        ContentIndexDatabase database = new ContentIndexDatabase();
        if (!exists) {
            LOGGER.info("[INDEX DB] Rebuilding index, this may take a while...");
            for (StorageBackend backend : backends) {
                try {
                    List<Content> metadata = backend.list().collect(Collectors.toList());
                    database.putAll(metadata);
                } catch (Exception e) {
                    LOGGER.error("[INDEX DB] Error rebuilding index for " + backend.getBackendId() + " backend", e);
                }
            }
        }
        return database;
    }

    private final ConnectionSource connectionSource;
    private final Dao<Content, String> dao;

    public ContentIndexDatabase() throws SQLException {
        this.connectionSource = new JdbcConnectionSource("jdbc:sqlite:db/bytebin.db");
        TableUtils.createTableIfNotExists(this.connectionSource, Content.class);
        this.dao = DaoManager.createDao(this.connectionSource, Content.class);
    }

    public void put(Content content) {
        try {
            this.dao.createOrUpdate(content);
        } catch (SQLException e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
        }
    }

    public Content get(String key) {
        try {
            return this.dao.queryForId(key);
        } catch (SQLException e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
            return null;
        }
    }

    public void putAll(Collection<Content> content) {
        try {
            this.dao.create(content);
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
        }
    }

    public void remove(String key) {
        try {
            this.dao.deleteById(key);
        } catch (SQLException e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
        }
    }

    public Collection<Content> getExpired() {
        try {
            return this.dao.queryBuilder().where()
                    .isNotNull("expiry")
                    .and()
                    .lt("expiry", new Date(System.currentTimeMillis()))
                    .query();
        } catch (SQLException e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
            return Collections.emptyList();
        }
    }

    record ContentStorageKeys(String contentType, String backend) { }

    private Map<ContentStorageKeys, Long> queryStringToIntMap(String returnExpr) {
        try {
            Map<ContentStorageKeys, Long> map = new HashMap<>();
            try (GenericRawResults<Object[]> results = this.dao.queryRaw(
                    "SELECT content_type, backend_id, " + returnExpr + " FROM content GROUP BY content_type, backend_id",
                    new DataType[]{DataType.STRING, DataType.STRING, DataType.LONG}
            )) {
                for (Object[] result : results) {
                    String contentType = (String) result[0];
                    String backend = (String) result[1];
                    long value = (long) result[2];
                    map.put(new ContentStorageKeys(contentType, backend), value);
                }
            }
            return map;
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
            return Collections.emptyMap();
        }
    }

    public void recordMetrics() {
        Map<ContentStorageKeys, Long> contentTypeToCount = queryStringToIntMap("count(*)");
        contentTypeToCount.forEach((keys, count) -> STORED_CONTENT_AMOUNT_GAUGE.labels(keys.contentType(), keys.backend()).set(count));

        Map<ContentStorageKeys, Long> contentTypeToSize = queryStringToIntMap("sum(content_length)");
        contentTypeToSize.forEach((keys, size) -> STORED_CONTENT_SIZE_GAUGE.labels(keys.contentType(), keys.backend()).set(size));
    }

    @Override
    public void close() throws Exception {
        this.connectionSource.close();
    }
}
