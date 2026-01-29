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

import io.jooby.Context;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;

public final class Metrics {
    private Metrics() {}

    /* HTTP handlers */

    public static final Histogram HTTP_REQUEST_DURATION_HISTOGRAM = Histogram.build()
            .name("bytebin_request_duration_seconds")
            .buckets(
                    0.001, // 1 ms
                    0.002, // 2 ms
                    0.005, // 5 ms
                    0.01,  // 10 ms
                    0.025, // 25 ms
                    0.05,  // 50 ms
                    0.1,   // 100 ms
                    0.25,  // 250 ms
                    0.5,   // 500 ms
                    1,     // 1 s
                    2,     // 2 s
                    5,     // 5 s
                    10,    // 10 s
                    15,    // 15 s
                    20,    // 20 s
                    30     // 30 s
            )
            .help("The duration to handle requests")
            .labelNames("method")
            .register();

    public static final Gauge HTTP_REQUESTS_ACTIVE_GAUGE = Gauge.build()
            .name("bytebin_requests_active")
            .help("The amount of active in-flight requests")
            .labelNames("method")
            .register();

    public static final Counter HTTP_REQUESTS_COUNTER = Counter.build()
            .name("bytebin_requests_total")
            .help("The amount of requests handled")
            .labelNames("method", "useragent")
            .register();

    public static final Counter HTTP_REJECTED_REQUESTS_COUNTER = Counter.build()
            .name("bytebin_rejected_requests_total")
            .help("The amount of rejected requests")
            .labelNames("method", "reason", "useragent")
            .register();

    // POST handler
    public static final Histogram HTTP_POST_CONTENT_SIZE_HISTOGRAM = Histogram.build()
            .name("bytebin_content_size_bytes")
            .buckets(
                    1000, // 1 KB
                    2500, // 2.5 KB
                    5000, // 5 KB
                    7500, // 7.5 KB
                    10000, // 10 KB
                    25000, // 25 KB
                    50000, // 50 KB
                    75000, // 75 KB
                    100000, // 100 KB
                    250000, // 250 KB
                    500000, // 500 KB
                    750000, // 750 KB
                    1000000, // 1 MB
                    2500000, // 2.5 MB
                    5000000, // 5 MB
                    7500000, // 7.5 MB
                    10000000 // 10 MB
            )
            .help("The size of posted content")
            .labelNames("useragent")
            .register();

    /* Content index database */

    public static final Gauge STORED_CONTENT_COUNT_GAUGE = Gauge.build()
            .name("bytebin_content")
            .help("The number of stored content items")
            .labelNames("type", "backend")
            .register();

    public static final Gauge STORED_CONTENT_SIZE_GAUGE = Gauge.build()
            .name("bytebin_content_size")
            .help("The size (bytes) of stored content")
            .labelNames("type", "backend")
            .register();

    /* Database transactions */

    public static final Histogram DB_TRANSACTION_DURATION_HISTOGRAM = Histogram.build()
            .name("bytebin_db_transaction_duration_seconds")
            .buckets(
                    0.001, // 1 ms
                    0.002, // 2 ms
                    0.005, // 5 ms
                    0.01,  // 10 ms
                    0.025, // 25 ms
                    0.05,  // 50 ms
                    0.1,   // 100 ms
                    0.25,  // 250 ms
                    0.5,   // 500 ms
                    1,     // 1 s
                    2,     // 2 s
                    5,     // 5 s
                    10     // 10 s
            )
            .help("The duration to query the db")
            .labelNames("operation")
            .register();

    public static final Counter DB_ERROR_COUNTER = Counter.build()
            .name("bytebin_db_error_total")
            .labelNames("operation")
            .help("Counts the number of errors that have occurred when interacting with the index database")
            .register();

    /* Storage backends */

    public static final Counter BACKEND_READ_COUNTER = Counter.build()
            .name("bytebin_backend_read_total")
            .labelNames("backend")
            .help("Counts the number of cache-misses when loading content")
            .register();

    public static final Counter BACKEND_WRITE_COUNTER = Counter.build()
            .name("bytebin_backend_write_total")
            .labelNames("backend")
            .help("Counts the number of times content was written to the backend")
            .register();

    public static final Counter BACKEND_DELETE_COUNTER = Counter.build()
            .name("bytebin_backend_delete_total")
            .labelNames("backend")
            .help("Counts the number of times content was deleted from the backend")
            .register();

    public static final Histogram BACKEND_READ_DURATION_HISTOGRAM = Histogram.build()
            .name("bytebin_backend_read_duration_seconds")
            .buckets(
                    0.001, // 1 ms
                    0.002, // 2 ms
                    0.005, // 5 ms
                    0.01,  // 10 ms
                    0.025, // 25 ms
                    0.05,  // 50 ms
                    0.1,   // 100 ms
                    0.25,  // 250 ms
                    0.5,   // 500 ms
                    1,     // 1 s
                    2,     // 2 s
                    5,     // 5 s
                    10     // 10 s
            )
            .help("The duration to read from the backend")
            .labelNames("backend")
            .register();

    public static final Histogram BACKEND_WRITE_DURATION_HISTOGRAM = Histogram.build()
            .name("bytebin_backend_write_duration_seconds")
            .buckets(
                    0.001, // 1 ms
                    0.002, // 2 ms
                    0.005, // 5 ms
                    0.01,  // 10 ms
                    0.025, // 25 ms
                    0.05,  // 50 ms
                    0.1,   // 100 ms
                    0.25,  // 250 ms
                    0.5,   // 500 ms
                    1,     // 1 s
                    2,     // 2 s
                    5,     // 5 s
                    10     // 10 s
            )
            .help("The duration to write to the backend")
            .labelNames("backend")
            .register();

    public static final Histogram BACKEND_DELETE_DURATION_HISTOGRAM = Histogram.build()
            .name("bytebin_backend_delete_duration_seconds")
            .buckets(
                    0.001, // 1 ms
                    0.002, // 2 ms
                    0.005, // 5 ms
                    0.01,  // 10 ms
                    0.025, // 25 ms
                    0.05,  // 50 ms
                    0.1,   // 100 ms
                    0.25,  // 250 ms
                    0.5,   // 500 ms
                    1,     // 1 s
                    2,     // 2 s
                    5,     // 5 s
                    10     // 10 s
            )
            .help("The duration to delete from the backend")
            .labelNames("backend")
            .register();

    public static final Counter BACKEND_ERROR_COUNTER = Counter.build()
            .name("bytebin_backend_error_total")
            .labelNames("backend", "operation")
            .help("Counts the number of errors that have occurred when interacting with the backend")
            .register();

    /* General errors */

    public static final Counter UNCAUGHT_ERROR_COUNTER = Counter.build()
            .name("bytebin_uncaught_error_total")
            .labelNames("type")
            .help("Counts the number of uncaught errors that have occurred")
            .register();

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
        HTTP_REQUESTS_COUNTER.labels(method, metricsLabel).inc();
    }

    public static void recordRejectedRequest(String method, String reason, Context ctx) {
        recordRejectedRequest(method, reason, getMetricsLabel(ctx));
    }

    public static void recordRejectedRequest(String method, String reason, String metricsLabel) {
        HTTP_REJECTED_REQUESTS_COUNTER.labels(method, reason, metricsLabel).inc();
    }
}
