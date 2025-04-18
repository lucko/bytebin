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

import java.util.Date;
import java.util.Map;

public interface LogHandler extends AutoCloseable {

    void logAttemptedGet(String key, User user);

    void logGet(String key, User user, ContentInfo contentInfo);

    void logPost(String key, User user, ContentInfo contentInfo);

    @Override
    void close();

    class Stub implements LogHandler {
        @Override
        public void logAttemptedGet(String key, User user) {

        }

        @Override
        public void logGet(String key, User user, ContentInfo contentInfo) {

        }

        @Override
        public void logPost(String key, User user, ContentInfo contentInfo) {

        }

        @Override
        public void close() {

        }
    }

    record User(String userAgent, String origin, String host, String ip, Map<String, String> headers) {}
    record ContentInfo(int contentLength, String contentType, Date contentExpiry) {}

}
