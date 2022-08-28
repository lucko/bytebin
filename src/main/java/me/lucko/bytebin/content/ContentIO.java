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

import me.lucko.bytebin.util.ContentEncoding;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;

public final class ContentIO {

    private ContentIO() {}

    public static void write(Content c, OutputStream outputStream) throws IOException {
        DataOutputStream out = new DataOutputStream(outputStream);

        // write version
        out.writeInt(2);

        // write name
        out.writeUTF(c.getKey());

        // write content type
        byte[] contextType = c.getContentType().getBytes();
        out.writeInt(contextType.length);
        out.write(contextType);

        // write expiry time
        out.writeLong(c.getExpiry() == Instant.MAX ? -1 : c.getExpiry().toEpochMilli());

        // write last modified
        out.writeLong(c.getLastModified());

        // write modifiable state data
        out.writeBoolean(c.isModifiable());
        if (c.isModifiable()) {
            out.writeUTF(c.getAuthKey());
        }

        // write encoding
        byte[] encoding = c.getEncoding().getBytes();
        out.writeInt(encoding.length);
        out.write(encoding);

        // write content
        out.writeInt(c.getContent().length);
        out.write(c.getContent());
    }

    public static Content read(InputStream inputStream, boolean skipContent) throws IOException {
        DataInputStream in = new DataInputStream(inputStream);

        // read version
        int version = in.readInt();

        // read key
        String key = in.readUTF();

        // read content type
        byte[] contentTypeBytes = new byte[in.readInt()];
        in.readFully(contentTypeBytes);
        String contentType = new String(contentTypeBytes);

        // read expiry
        long expiry = in.readLong();
        Instant expiryInstant = expiry == -1 ? Instant.MAX : Instant.ofEpochMilli(expiry);

        // read last modified time
        long lastModified = in.readLong();

        // read modifiable state data
        boolean modifiable = in.readBoolean();
        String authKey = null;
        if (modifiable) {
            authKey = in.readUTF();
        }

        // read encoding
        String encoding;
        if (version == 1) {
            encoding = ContentEncoding.GZIP;
        } else {
            byte[] encodingBytes = new byte[in.readInt()];
            in.readFully(encodingBytes);
            encoding = new String(encodingBytes);
        }

        // read content
        byte[] content;
        if (skipContent) {
            content = Content.EMPTY_BYTES;
        } else {
            content = new byte[in.readInt()];
            in.readFully(content);
        }

        return new Content(key, contentType, expiryInstant, lastModified, modifiable, authKey, encoding, content);
    }

}
