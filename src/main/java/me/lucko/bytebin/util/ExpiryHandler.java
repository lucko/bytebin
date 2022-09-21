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

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

public final class ExpiryHandler {

    private final Duration defaultLifetime;
    private final Map<String, Duration> specificLifetimes;

    /**
     * Creates a new expiry handler.
     *
     * @param lifetime the number of minutes before content should expire
     * @param lifetimeSpecific the number of minutes before content from certain sources should expire
     */
    public ExpiryHandler(long lifetime, Map<String, Long> lifetimeSpecific) {
        this.defaultLifetime = toDuration(lifetime);
        this.specificLifetimes = lifetimeSpecific.entrySet().stream().collect(ImmutableMap.toImmutableMap(
                Map.Entry::getKey,
                Functions.compose(ExpiryHandler::toDuration, Map.Entry::getValue))
        );
    }

    /**
     * Gets if content ever expires.
     *
     * @return if this expiry handler has any expiry times configured
     */
    public boolean hasExpiryTimes() {
        return !this.defaultLifetime.isZero() || !this.specificLifetimes.isEmpty();
    }

    /**
     * Gets the expiry time to use for a piece of submitted content with the given parameters.
     *
     * @param userAgent the user agent of the client that posted the content
     * @param origin the origin of the client that posted the content
     * @param host the host that was used when posting the content
     * @return the expiry time, or {@link Instant#MAX} if it should never expire
     */
    public Date getExpiry(String userAgent, String origin, String host) {
        Duration duration = this.specificLifetimes.getOrDefault(userAgent,
                this.specificLifetimes.getOrDefault(origin,
                        this.specificLifetimes.getOrDefault(host,
                                this.defaultLifetime
                        )
                )
        );
        if (duration.isZero()) {
            return null;
        }

        return new Date(Instant.now().plus(duration).toEpochMilli());
    }

    private static Duration toDuration(long minutes) {
        // Duration.ZERO is used as a special case to mean "don't expire"
        // it is assumed that a negative number of minutes implies the same.
        return minutes > 0 ? Duration.ofMinutes(minutes) : Duration.ZERO;
    }

}
