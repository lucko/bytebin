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

import io.jooby.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ContentEncoding {
    private ContentEncoding() {}

    public static final String GZIP = "gzip";
    public static final String IDENTITY = "identity";

    private static final Splitter COMMA_SPLITTER = Splitter.on(Pattern.compile(",\\s*"));
    private static final Pattern RE_SEMICOLON = Pattern.compile(";\\s*");

    public static Set<String> getAcceptedEncoding(Context ctx) {
        String header = ctx.header("Accept-Encoding").valueOrNull();
        if (header == null || header.isEmpty()) {
            return Collections.singleton(IDENTITY);
        }

        Set<String> set = new HashSet<>();
        set.add(IDENTITY);

        for (String encoding : COMMA_SPLITTER.split(header)) {
            set.add(getEncodingName(RE_SEMICOLON.split(encoding)[0]));
        }

        return set;
    }

    public static List<String> getContentEncoding(String header) {
        if (header == null || header.isEmpty()) {
            return new ArrayList<>();
        }

        LinkedList<String> list = new LinkedList<>();
        for (String encoding : COMMA_SPLITTER.split(header)) {
            list.add(getEncodingName(encoding));
        }

        // remove 'identity' if it comes last
        while (!list.isEmpty() && list.getLast().equals(IDENTITY)) {
            list.removeLast();
        }

        return list;
    }

    private static String getEncodingName(String name) {
        if ("x-gzip".equals(name)) {
            return GZIP;
        }
        return name;
    }

}
