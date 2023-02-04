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
import me.lucko.bytebin.content.ContentIndexDatabase;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class AuditTask implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(AuditTask.class);

    private final ContentIndexDatabase index;
    private final List<StorageBackend> backends;

    public AuditTask(ContentIndexDatabase index, List<StorageBackend> backends) {
        this.index = index;
        this.backends = backends;
    }

    @Override
    public void run() {
        try {
            run0();
        } catch (Exception e) {
            LOGGER.error("Error occurred while auditing", e);
        }
    }

    private void run0() throws Exception {
        LOGGER.info("[AUDIT] Starting audit...");

        for (StorageBackend backend : this.backends) {
            String backendId = backend.getBackendId();

            LOGGER.info("[AUDIT] Listing content for backend {}", backendId);
            List<String> keys = backend.listKeys().toList();
            LOGGER.info("[AUDIT] Found {} entries for backend {}", keys.size(), backendId);

            List<String> keysToDelete = keys.stream()
                    .filter(key -> this.index.get(key) == null)
                    .toList();

            LOGGER.info("[AUDIT] Found {} records that exist in the {} backend but not the index!", keysToDelete.size(), backendId);
            LOGGER.info("[AUDIT] " + String.join(",", keysToDelete));
        }

        LOGGER.info("[AUDIT] Finished audit");
    }
}
