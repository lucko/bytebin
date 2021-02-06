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

import com.google.common.base.Splitter;
import java.util.regex.Pattern;
import me.lucko.bytebin.util.compression.BrotliCompressionStream;
import me.lucko.bytebin.util.compression.GZIPCompressionStream;
import me.lucko.bytebin.util.compression.ZSTDCompressionStream;
import org.rapidoid.http.Req;

import java.io.IOException;

public final class Compression {
    private Compression() {}

    private static final Splitter COMMA_SPLITTER = Splitter.on(Pattern.compile(",\\s*"));
    private static final Pattern RE_SEMICOLON = Pattern.compile(";\\s*");

    public static CompressionType compressionType(Req req) {
        String header = req.header("Accept-Encoding", null);
        if (header != null) {
            for (String typeStr : COMMA_SPLITTER.split(header)) {
                CompressionType type = CompressionType.getCompression(RE_SEMICOLON.split(typeStr)[0]);
                if (type != null) {
                    return type;
                }
            }
        }
        return null;
    }

    private static final GZIPCompressionStream GZIP_STREAM = new GZIPCompressionStream();
    private static final BrotliCompressionStream BROTLI_STREAM = new BrotliCompressionStream();
    private static final ZSTDCompressionStream ZSTD_STREAM = new ZSTDCompressionStream();

    public static byte[] compress(byte[] buf, CompressionType type) {
        if (type == null) {
            return buf;
        }

        try {
            switch (type) {
                case GZIP:
                    return GZIP_STREAM.compress(buf);
                case BROTLI:
                    return BROTLI_STREAM.compress(buf);
                case ZSTD:
                    return ZSTD_STREAM.compress(buf);
                default:
                    throw new RuntimeException("Unknown compression type: " + type);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] decompress(byte[] buf, CompressionType type) throws IOException {
        if (type == null) {
            return buf;
        }

        switch (type) {
            case GZIP:
                return GZIP_STREAM.decompress(buf);
            case BROTLI:
                return BROTLI_STREAM.decompress(buf);
            case ZSTD:
                return ZSTD_STREAM.decompress(buf);
            default:
                throw new IOException("Unknown compression type: " + type);
        }
    }

    public enum CompressionType {
        GZIP("gzip"),
        BROTLI("br"),
        ZSTD("zstd");

        private final String name;
        CompressionType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static CompressionType getCompression(String name) {
            if (name == null || name.isEmpty()) {
                return null;
            }

            for (CompressionType type : values()) {
                if (name.equalsIgnoreCase(type.name)) {
                    return type;
                }
            }
            return null;
        }

    }

}
