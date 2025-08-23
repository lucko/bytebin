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

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles a rate limit
 */
public class RateLimiter {
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

    public boolean check(String ipAddress) {
        //noinspection ConstantConditions
        return this.rateLimiter.get(ipAddress).get() > this.actionsPerCycle;
    }

    public boolean incrementAndCheck(String ipAddress) {
        //noinspection ConstantConditions
        return this.rateLimiter.get(ipAddress).incrementAndGet() > this.actionsPerCycle;
    }

    public void increment(String ipAddress) {
        //noinspection ConstantConditions
        this.rateLimiter.get(ipAddress).incrementAndGet();
    }
}
