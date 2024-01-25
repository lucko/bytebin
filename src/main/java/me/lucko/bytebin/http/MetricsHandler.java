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
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import javax.annotation.Nonnull;
import java.io.OutputStreamWriter;

public final class MetricsHandler implements Route.Handler {

    @Override
    public Context apply(@Nonnull Context ctx) throws Exception {
        // deny requests via the reverse proxy
        if (ctx.header("X-Forwarded-For").isPresent()) {
            throw new StatusCodeException(StatusCode.UNAUTHORIZED);
        }

        String contentType = TextFormat.chooseContentType(ctx.header("Accept").valueOrNull());
        ctx.setResponseHeader("Content-Type", contentType);

        try (OutputStreamWriter writer = new OutputStreamWriter(ctx.responseStream())) {
            TextFormat.writeFormat(contentType, writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        }

        return ctx;
    }

}
