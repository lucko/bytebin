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

import io.jooby.Route;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;

public class MetricsFilter implements Route.Filter {

    private static final Histogram REQUEST_DURATION = Histogram.build()
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

    private static final Gauge REQUESTS_ACTIVE = Gauge.build()
            .name("bytebin_requests_active")
            .help("The amount of active in-flight requests")
            .labelNames("method")
            .register();

    private final Histogram.Child durationHistogram;
    private final Gauge.Child activeGauge;

    public MetricsFilter(String method) {
        this.durationHistogram = REQUEST_DURATION.labels(method);
        this.activeGauge = REQUESTS_ACTIVE.labels(method);
    }

    @Override
    public Route.Handler apply(Route.Handler next) {
        return ctx -> {
            this.activeGauge.inc();
            Histogram.Timer timer = this.durationHistogram.startTimer();
            ctx.onComplete(endCtx -> {
                timer.observeDuration();
                this.activeGauge.dec();
            });
            return next.apply(ctx);
        };
    }
}
