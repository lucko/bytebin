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
    public void logAttemptedGet(String key, User user) {
        this.queue.offer(new AttemptedGetEvent(key, user));
    }

    @Override
    public void logGet(String key, User user, ContentInfo content) {
        this.queue.offer(new GetEvent(key, user, content));
    }

    @Override
    public void logPost(String key, User user, ContentInfo content) {
        this.queue.offer(new PostEvent(key, user, content));
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
        private final User user;

        public AttemptedGetEvent(String key, User user) {
            super("attempted-get");
            this.key = key;
            this.user = user;
        }
    }

    public static final class GetEvent extends Event {
        private final String key;
        private final User user;
        private final ContentInfo content;

        public GetEvent(String key, User user, ContentInfo content) {
            super("get");
            this.key = key;
            this.user = user;
            this.content = content;
        }
    }

    public static final class PostEvent extends Event {
        private final String key;
        private final User user;
        private final ContentInfo content;

        public PostEvent(String key, User user, ContentInfo content) {
            super("post");
            this.key = key;
            this.user = user;
            this.content = content;
        }
    }

}
