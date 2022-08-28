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

import java.util.stream.Stream;

/**
 * The storage backend interface.
 */
public interface StorageBackend {

    /**
     * Get the id of the backend.
     *
     * @return the id
     */
    String getBackendId();

    /**
     * Loads content from the backend.
     *
     * @param key the key to identify the content
     * @return the content, or null
     * @throws Exception catch all
     */
    Content load(String key) throws Exception;

    /**
     * Saves content to the backend.
     *
     * @param content the content
     * @throws Exception catch all
     */
    void save(Content content) throws Exception;

    /**
     * Deletes content from the backend.
     *
     * @param key the key to identify the content
     * @throws Exception catch all
     */
    void delete(String key) throws Exception;

    /**
     * Lists metadata about all the content stored in the backend. (doesn't load the actual data).
     * Used primarily if the index needs to be rebuilt.
     *
     * @return a list of metadata
     * @throws Exception catch all
     */
    Stream<Content> list() throws Exception;

}
