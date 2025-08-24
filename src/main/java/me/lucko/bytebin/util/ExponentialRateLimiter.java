
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

/**
 * Handles an exponential rate limit
 */
public class ExponentialRateLimiter implements RateLimiter {

    /** Rate limiter cache - allow x "actions" every x minutes, where x gets larger each time */
    private final LoadingCache<String, Counter> cache;

    /** The base period in milliseconds */
    private final long basePeriodMillis;
    /** The max period in milliseconds */
    private final long maxPeriodMillis;
    /** The multiplier to apply each period */
    private final double multiplier;
    /** The number of actions allowed in each period  */
    private final int actionsPerCycle;

    public ExponentialRateLimiter(int actionsPerCycle, int periodMins, double multiplier, int resetPeriodMins) {
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(resetPeriodMins, TimeUnit.MINUTES)
                .build(key -> new Counter());
        this.basePeriodMillis = TimeUnit.MINUTES.toMillis(periodMins);
        this.maxPeriodMillis = TimeUnit.MINUTES.toMillis(resetPeriodMins);
        this.multiplier = multiplier;
        this.actionsPerCycle = actionsPerCycle;
    }

    private final class Counter {
        private int count = 0;
        private long nextPeriodMillis = ExponentialRateLimiter.this.basePeriodMillis;
        private long periodEndMillis = 0;

        public synchronized boolean check() {
            return this.periodEndMillis != 0 && System.currentTimeMillis() < this.periodEndMillis;
        }

        public synchronized boolean checkAndIncrement() {
            boolean limited = check();
            if (!limited) {
                increment();
            }
            return limited;
        }

        public synchronized void increment() {
            this.count++;
            if (this.count >= ExponentialRateLimiter.this.actionsPerCycle) {
                this.count = 0;
                this.periodEndMillis = System.currentTimeMillis() + this.nextPeriodMillis;
                this.nextPeriodMillis = Math.min((long) (this.nextPeriodMillis * ExponentialRateLimiter.this.multiplier), ExponentialRateLimiter.this.maxPeriodMillis);
            }
        }
    }

    @Override
    public boolean check(String ipAddress) {
        //noinspection ConstantConditions
        return this.cache.get(ipAddress).check();
    }

    @Override
    public boolean checkAndIncrement(String ipAddress) {
        //noinspection ConstantConditions
        return this.cache.get(ipAddress).checkAndIncrement();
    }

    @Override
    public void increment(String ipAddress) {
        //noinspection ConstantConditions
        this.cache.get(ipAddress).increment();
    }
}
