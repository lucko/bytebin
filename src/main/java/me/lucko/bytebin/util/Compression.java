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
import com.google.common.io.ByteStreams;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.rapidoid.http.Req;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class Compression {
    private Compression() {}

    private static final Splitter COMMA_SPLITTER = Splitter.on(Pattern.compile(",\\s*"));
    private static final Pattern RE_SEMICOLON = Pattern.compile(";\\s*");

    public static List<String> getSupportedEncoding(Req req) {
        List<String> retVal = new ArrayList<>(2);
        String header = req.header("Accept-Encoding", null);
        if (header != null) {
            for (String typeStr : COMMA_SPLITTER.split(header)) {
                retVal.add(RE_SEMICOLON.split(typeStr)[0]);
            }
        }
        if (!retVal.contains("identity") && !retVal.contains("*")) {
            retVal.add("identity");
        }
        return retVal;
    }

    public static List<String> getProvidedEncoding(String header) {
        List<String> retVal = new ArrayList<>(2);
        if (header != null && !header.isEmpty()) {
            for (String typeStr : COMMA_SPLITTER.split(header)) {
                retVal.add(typeStr);
            }
        }
        if (!retVal.contains("identity")) {
            retVal.add("identity");
        }
        return retVal;
    }

    public static byte[] compress(byte[] buf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
            gzipOut.write(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    public static byte[] decompress(byte[] buf) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(buf);
        try (GZIPInputStream gzipIn = new GZIPInputStream(in)) {
            return ByteStreams.toByteArray(gzipIn);
        }
    }

}
