package com.stockflow.common.storage;

import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds storage keys. One implementation, so every file in the bucket is laid out the same way.
 *
 * <h2>The layout, and why each part is there</h2>
 *
 * <pre>
 * product-images/2026/09/07/0192f4c1-8e3a-7b21-9c44-3f2a1b8d5e60.jpg
 * └── category    └── date   └── generated id                    └── extension
 * </pre>
 *
 * <ul>
 *   <li><b>Category first</b> so a lifecycle rule can expire {@code exports/} without touching
 *       {@code qc-evidence/}, and so a listing is browsable by a human.</li>
 *   <li><b>Date next</b> because object stores list lexicographically: without it, a bucket with a
 *       million files under one prefix is unusable in any console, and S3 request rates degrade on
 *       a single hot prefix.</li>
 *   <li><b>A generated id, never the filename.</b> Two users uploading {@code invoice.pdf} must not
 *       collide, and a user-supplied name is attacker-controlled — {@code ../../etc/passwd} or a
 *       500-character Unicode name has no business in a path. The original name is kept in the
 *       database, where it is data rather than a path.</li>
 *   <li><b>An extension</b> derived from the <i>content type</i>, not the filename, so the object
 *       store can serve a sensible {@code Content-Type} and a developer can recognise the file.</li>
 * </ul>
 */
public final class StorageKeys {

    private static final DateTimeFormatter DATE_PATH =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private StorageKeys() {
    }

    public static String generate(FileCategory category, String contentType, Instant now) {
        return "%s/%s/%s%s".formatted(
                category.prefix(),
                DATE_PATH.format(now),
                Identifiers.newId(),
                extensionFor(contentType));
    }

    /** Extension including the dot, or empty when the type has no obvious one. */
    static String extensionFor(String contentType) {
        return switch (ContentTypePolicy.normalise(contentType)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "application/pdf" -> ".pdf";
            case "text/csv" -> ".csv";
            case "text/plain" -> ".txt";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            default -> "";
        };
    }

    /**
     * Rejects a key that is not one of ours.
     *
     * <p>Keys come back from the database, and a corrupted or hand-edited row must not be able to
     * make the storage layer read outside its prefix. {@code ..} is the specific thing being
     * blocked: on the local filesystem implementation it is a path traversal, and on S3 it produces
     * a key nobody intended.</p>
     */
    public static String requireValid(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("Storage key must not be blank");
        }
        if (key.contains("..") || key.startsWith("/") || key.contains("\\")) {
            throw new StorageException("Malformed storage key: " + key);
        }
        String prefix = key.substring(0, Math.max(0, key.indexOf('/')));
        boolean known = false;
        for (FileCategory category : FileCategory.values()) {
            if (category.prefix().equals(prefix)) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new StorageException("Storage key is not in a known category: " + key);
        }
        return key;
    }

    /**
     * Where an approved rendition is served from: the same date/id tail under the public prefix, so
     * the mapping needs no column and publishing twice lands on the same object.
     *
     * <p>A key already under {@code product-images/} is returned as is: renditions stored before
     * they had a private home are public already.</p>
     */
    public static String publishedKeyOf(String renditionKey) {
        FileCategory category = categoryOf(renditionKey);
        if (category == FileCategory.PRODUCT_IMAGE) {
            return renditionKey;
        }
        if (category != FileCategory.PRODUCT_RENDITION) {
            throw new StorageException("Not a product rendition key: " + renditionKey);
        }
        return FileCategory.PRODUCT_IMAGE.prefix() + renditionKey.substring(category.prefix().length());
    }

    /** The category a key belongs to, for lifecycle and permission decisions. */
    public static FileCategory categoryOf(String key) {
        String prefix = requireValid(key).substring(0, key.indexOf('/'));
        for (FileCategory category : FileCategory.values()) {
            if (category.prefix().equals(prefix)) {
                return category;
            }
        }
        throw new StorageException("Storage key is not in a known category: " + key);
    }

    static String lowercase(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
