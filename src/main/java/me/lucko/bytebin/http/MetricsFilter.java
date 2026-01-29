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
import me.lucko.bytebin.util.Metrics;

public class MetricsFilter implements Route.Filter {
    private final Histogram.Child durationHistogram;
    private final Gauge.Child activeGauge;

    public MetricsFilter(String method) {
        this.durationHistogram = Metrics.HTTP_REQUEST_DURATION_HISTOGRAM.labels(method);
        this.activeGauge = Metrics.HTTP_REQUESTS_ACTIVE_GAUGE.labels(method);
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
