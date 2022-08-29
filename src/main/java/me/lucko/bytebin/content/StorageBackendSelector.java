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

package me.lucko.bytebin.content;

import me.lucko.bytebin.content.storage.StorageBackend;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Selects the backend to store content in.
 */
public interface StorageBackendSelector {

    /**
     * Select which backend to store {@code content} in.
     *
     * @param content the content
     * @return the selected backend
     */
    StorageBackend select(Content content);

    final class Static implements StorageBackendSelector {
        private final StorageBackend backend;

        public Static(StorageBackend backend) {
            this.backend = backend;
        }

        @Override
        public StorageBackend select(Content content) {
            return this.backend;
        }
    }

    abstract class Dynamic implements StorageBackendSelector {
        private final StorageBackendSelector next;
        private final StorageBackend backend;

        protected Dynamic(StorageBackend backend, StorageBackendSelector next) {
            this.next = next;
            this.backend = backend;
        }

        @Override
        public StorageBackend select(Content content) {
            if (test(content)) {
                return this.backend;
            }
            return this.next.select(content);
        }

        protected abstract boolean test(Content content);
    }

    final class IfSizeGt extends Dynamic {
        private final long threshold; // bytes

        public IfSizeGt(long threshold, StorageBackend backend, StorageBackendSelector next) {
            super(backend, next);
            this.threshold = threshold;
        }

        @Override
        protected boolean test(Content content) {
            return content.getContentLength() > this.threshold;
        }
    }

    final class IfExpiryGt extends Dynamic {
        private final int threshold; // minutes

        public IfExpiryGt(int threshold, StorageBackend backend, StorageBackendSelector next) {
            super(backend, next);
            this.threshold = threshold;
        }

        @Override
        protected boolean test(Content content) {
            Date expiry = content.getExpiry();
            if (expiry == null) {
                return true;
            }

            long timeToExpiry = Duration.between(Instant.now(), expiry.toInstant()).getSeconds() / 60;
            return timeToExpiry > this.threshold;
        }
    }

}
