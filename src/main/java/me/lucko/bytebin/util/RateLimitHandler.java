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

import com.google.common.collect.ImmutableSet;
import io.jooby.Context;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import java.util.Collection;
import java.util.Set;

/**
 * Handles rate limit checking for the application.
 *
 * Trusted server-side applications making requests to bytebin on
 * behalf of other clients can authenticate using an API key and provide
 * the client's IP address using an HTTP header. In this case, the client IP
 * address will be used for rate limiting purposes instead.
 */
public final class RateLimitHandler {
    private static final String HEADER_FORWARDED_IP = "Bytebin-Forwarded-For";
    private static final String HEADER_API_KEY = "Bytebin-Api-Key";

    private final Set<String> apiKeys;

    public RateLimitHandler(Collection<String> apiKeys) {
        this.apiKeys = ImmutableSet.copyOf(apiKeys);
    }

    public String getIpAddressAndCheckRateLimit(Context ctx, RateLimiter limiter) {
        // get the connection IP address according to cloudflare, fallback to
        // the remote address
        String ipAddress = ctx.header("x-real-ip").valueOrNull();
        if (ipAddress == null) {
            ipAddress = ctx.getRemoteAddress();
        }

        // if an API key has been specified, ensure it is valid, then replace
        // the IP address with the one specified by the forwarded-for header.
        String apiKey = ctx.header(HEADER_API_KEY).value("");
        if (!apiKey.isEmpty()) {
            if (!this.apiKeys.contains(apiKey)) {
                throw new StatusCodeException(StatusCode.UNAUTHORIZED, "API key is invalid");
            }

            ipAddress = ctx.header(HEADER_FORWARDED_IP).value(ipAddress);
        }

        // check rate limits
        if (limiter.check(ipAddress)) {
            throw new StatusCodeException(StatusCode.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }

        return ipAddress;
    }

}
