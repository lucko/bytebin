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

package me.lucko.bytebin.content.storage;

import me.lucko.bytebin.content.Content;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Storage backend that uses S3 to persist content.
 */
public class S3Backend implements StorageBackend {

    private static final Logger LOGGER = LogManager.getLogger(S3Backend.class);

    /** The id of the backend */
    private final String backendId;

    private final String bucketName;
    private final S3Client client;

    public S3Backend(String backendId, String bucketName) {
        this.backendId = backendId;
        this.bucketName = bucketName;

        // configure with environment variables: AWS_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
        S3ClientBuilder builder = S3Client.builder();

        String s3EndpointUrl = System.getenv("AWS_S3_ENDPOINT_URL");
        if (s3EndpointUrl != null && !s3EndpointUrl.isBlank()) {
            builder = builder.endpointOverride(URI.create(s3EndpointUrl));
        }

        this.client = builder.build();
    }

    @Override
    public String getBackendId() {
        return this.backendId;
    }

    @Override
    public Content load(String key) throws Exception {
        try (ResponseInputStream<GetObjectResponse> in = this.client.getObject(GetObjectRequest.builder()
                .bucket(this.bucketName)
                .key(key)
                .build()
        )) {
            Content content = read(key, in.response().metadata(), in.readAllBytes());
            content.setBackendId(this.backendId);
            return content;
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    public void save(Content content) throws Exception {
        this.client.putObject(
                PutObjectRequest.builder()
                        .bucket(this.bucketName)
                        .key(content.getKey())
                        .metadata(writeMetadata(content))
                        .build(),
                RequestBody.fromBytes(content.getContent())
        );
    }

    @Override
    public void delete(String key) throws Exception {
        this.client.deleteObject(DeleteObjectRequest.builder()
                .bucket(this.bucketName)
                .key(key)
                .build()
        );
    }

    @Override
    public Stream<String> listKeys() throws Exception {
        ListObjectsV2Iterable iter = this.client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                .bucket(this.bucketName)
                .build()
        );
        return iter.stream().flatMap(resp -> resp.contents().stream().map(S3Object::key));
    }

    @Override
    public Stream<Content> list() throws Exception {
        ListObjectsV2Iterable iter = this.client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                .bucket(this.bucketName)
                .build()
        );
        return iter.stream().flatMap(resp -> resp.contents().stream()
                .map(object -> {
                    String key = object.key();
                    try {
                        HeadObjectResponse objectResp = this.client.headObject(HeadObjectRequest.builder()
                                .bucket(this.bucketName)
                                .key(key)
                                .build()
                        );
                        Content content = read(key, objectResp.metadata(), Content.EMPTY_BYTES);
                        content.setBackendId(this.backendId);
                        content.setContentLength(objectResp.contentLength().intValue());
                        return content;
                    } catch (Exception e) {
                        LOGGER.error("Exception occurred loading meta for '" + key + "'", e);
                        return null;
                    }
                }))
                .filter(Objects::nonNull);
    }

    private static Map<String, String> writeMetadata(Content c) {
        Map<String, String> meta = new HashMap<>();
        meta.put("bytebin-version", "1");
        meta.put("bytebin-contenttype", c.getContentType());
        meta.put("bytebin-expiry", Long.toString(c.getExpiry() == null ? -1 : c.getExpiry().getTime()));
        meta.put("bytebin-lastmodified", Long.toString(c.getLastModified()));
        meta.put("bytebin-modifiable", Boolean.toString(c.isModifiable()));
        if (c.isModifiable()) {
            meta.put("bytebin-authkey", c.getAuthKey());
        }
        meta.put("bytebin-encoding", c.getEncoding());
        return meta;
    }

    private static Content read(String key, Map<String, String> meta, byte[] buf) {
        //int version = Integer.parseInt(meta.get("bytebin-version"));
        String contentType = meta.get("bytebin-contenttype");
        long expiry = Long.parseLong(meta.get("bytebin-expiry"));
        Date expiryDate = expiry == -1 ? null : new Date(expiry);
        long lastModified = Long.parseLong(meta.get("bytebin-lastmodified"));
        boolean modifiable = Boolean.parseBoolean(meta.get("bytebin-modifiable"));
        String authKey = null;
        if (modifiable) {
            authKey = meta.get("bytebin-authkey");
        }
        String encoding = meta.get("bytebin-encoding");
        return new Content(key, contentType, expiryDate, lastModified, modifiable, authKey, encoding, buf);
    }
}
