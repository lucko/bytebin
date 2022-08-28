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

import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

/**
 * Encapsulates a piece of stored content.
 */
@DatabaseTable(tableName = "content")
public final class Content {

    /** Empty byte array */
    public static final byte[] EMPTY_BYTES = new byte[0];

    /** Empty content instance */
    public static final Content EMPTY_CONTENT = new Content(null, "text/plain", null, Long.MIN_VALUE, false, null, "", EMPTY_BYTES);

    /** Number of bytes in a megabyte */
    public static final long MEGABYTE_LENGTH = 1024L * 1024L;

    /** The key used to identify the content */
    @DatabaseField(columnName = "key", id = true, canBeNull = false)
    private String key;

    /** The type of the content */
    @DatabaseField(columnName = "content_type", index = true)
    private String contentType;

    /** The time when the content will expire */
    @DatabaseField(columnName = "expiry", dataType = DataType.DATE_INTEGER, index = true)
    private Date expiry;

    /** The time when the content was last modified in unix millis */
    @DatabaseField(columnName = "last_modified")
    private long lastModified;

    /** If the content can be modified using PUT requests */
    private boolean modifiable;

    /** The auth key required to modify the content */
    private String authKey;

    /** The 'Content-Encoding' used to encode this content */
    @DatabaseField(columnName = "encoding")
    private String encoding;

    /** The id of the backend currently storing this content - use null for unknown */
    @DatabaseField(columnName = "backend_id")
    private String backendId;

    /** The actual content, optional */
    private byte[] content;

    /** The length of the content */
    @DatabaseField(columnName = "content_length")
    private int contentLength;

    // future that is completed after the content has been saved to disk
    private final CompletableFuture<Void> saveFuture = new CompletableFuture<>();

    public Content(String key, String contentType, Date expiry, long lastModified, boolean modifiable, String authKey, String encoding, byte[] content) {
        this.key = key;
        this.contentType = contentType;
        this.expiry = expiry;
        this.lastModified = lastModified;
        this.modifiable = modifiable;
        this.authKey = authKey;
        this.encoding = encoding;
        this.content = content;
        this.contentLength = content.length;
    }

    // for ormlite
    Content() {

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

    public Date getExpiry() {
        return this.expiry;
    }

    public void setExpiry(Date expiry) {
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
        return this.encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getBackendId() {
        return this.backendId;
    }

    public void setBackendId(String backendId) {
        this.backendId = backendId;
    }

    public byte[] getContent() {
        return this.content;
    }

    public void setContent(byte[] content) {
        this.content = content;
        this.contentLength = content.length;
    }

    public int getContentLength() {
        return this.contentLength;
    }

    public void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }

    public CompletableFuture<Void> getSaveFuture() {
        return this.saveFuture;
    }

}
