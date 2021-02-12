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

/**
 * Encapsulates content within the service
 */
public final class Content {

    /** Empty byte array */
    public static final byte[] EMPTY_BYTES = new byte[0];

    /** Empty content instance */
    public static final Content EMPTY_CONTENT = new Content(null, "text/plain", Long.MAX_VALUE, Long.MIN_VALUE, false, null, "", EMPTY_BYTES);

    /** Number of bytes in a megabyte */
    public static final long MEGABYTE_LENGTH = 1024L * 1024L;

    private final String key;
    private String contentType;
    private long expiry;
    private long lastModified;
    private final boolean modifiable;
    private final String authKey;
    private String encoding;
    private byte[] content;

    public Content(String key, String contentType, long expiry, long lastModified, boolean modifiable, String authKey, String encoding, byte[] content) {
        this.key = key;
        this.contentType = contentType;
        this.expiry = expiry;
        this.lastModified = lastModified;
        this.modifiable = modifiable;
        this.authKey = authKey;
        this.encoding = encoding;
        this.content = content;
    }

    public String getKey() {
        return this.key;
    }

    public String getContentType() {
        return this.contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getExpiry() {
        return this.expiry;
    }

    public void setExpiry(long expiry) {
        this.expiry = expiry;
    }

    public long getLastModified() {
        return this.lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public boolean isModifiable() {
        return this.modifiable;
    }

    public String getAuthKey() {
        return this.authKey;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public byte[] getContent() {
        return this.content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public boolean shouldExpire() {
        return this.getExpiry() < System.currentTimeMillis();
    }
}
