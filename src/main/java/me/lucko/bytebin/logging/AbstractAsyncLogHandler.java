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

package me.lucko.bytebin.logging;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class AbstractAsyncLogHandler implements LogHandler {
    private final Queue<Event> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("bytebin-log-handler-%d").build()
    );

    public AbstractAsyncLogHandler(int flushIntervalSeconds) {
        this.scheduler.scheduleAtFixedRate(this::exportAndFlush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    private void exportAndFlush() {
        List<Event> events = new ArrayList<>();
        for (Event e; (e = this.queue.poll()) != null; ) {
            events.add(e);
        }
        flush(events);
    }

    public abstract void flush(List<Event> events);

    @Override
    public void close() {
        exportAndFlush();
        this.scheduler.shutdown();
    }

    @Override
    public void logAttemptedGet(String key, String userAgent, String origin, String ip) {
        this.queue.offer(new AttemptedGetEvent(key, userAgent, origin, ip));
    }

    @Override
    public void logGet(String key, String userAgent, String origin, String ip, int contentLength, String contentType, Date contentExpiry) {
        this.queue.offer(new GetEvent(key, userAgent, origin, ip, contentLength, contentType, contentExpiry));
    }

    @Override
    public void logPost(String key, String userAgent, String origin, String ip, int contentLength, String contentType, Date contentExpiry) {
        this.queue.offer(new PostEvent(key, userAgent, origin, ip, contentLength, contentType, contentExpiry));
    }

    public static abstract class Event {
        private final String kind;
        private final long timestamp = System.currentTimeMillis();

        public Event(String kind) {
            this.kind = kind;
        }
    }

    public static final class AttemptedGetEvent extends Event {
        private final String key;
        private final String userAgent;
        private final String origin;
        private final String ip;

        public AttemptedGetEvent(String key, String userAgent, String origin, String ip) {
            super("attempted-get");
            this.key = key;
            this.userAgent = userAgent;
            this.origin = origin;
            this.ip = ip;
        }
    }

    public static final class GetEvent extends Event {
        private final String key;
        private final String userAgent;
        private final String origin;
        private final String ip;
        private final int contentLength;
        private final String contentType;
        private final Date contentExpiry;

        public GetEvent(String key, String userAgent, String origin, String ip, int contentLength, String contentType, Date contentExpiry) {
            super("get");
            this.key = key;
            this.userAgent = userAgent;
            this.origin = origin;
            this.ip = ip;
            this.contentLength = contentLength;
            this.contentType = contentType;
            this.contentExpiry = contentExpiry;
        }
    }

    public static final class PostEvent extends Event {
        private final String key;
        private final String userAgent;
        private final String origin;
        private final String ip;
        private final int contentLength;
        private final String contentType;
        private final Date contentExpiry;

        public PostEvent(String key, String userAgent, String origin, String ip, int contentLength, String contentType, Date contentExpiry) {
            super("post");
            this.key = key;
            this.userAgent = userAgent;
            this.origin = origin;
            this.ip = ip;
            this.contentLength = contentLength;
            this.contentType = contentType;
            this.contentExpiry = contentExpiry;
        }
    }

}
