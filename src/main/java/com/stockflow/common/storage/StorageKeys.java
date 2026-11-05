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

    /** The quarantine prefix browser uploads land in. Never served, never a category. */
    static final String INCOMING = "incoming";

    /**
     * Where a browser writes the file of {@code uploadId} before it is checked: {@code incoming/<category>/<date>/<id>},
     * no extension - nothing about the bytes is trusted yet.
     */
    public static String incoming(FileCategory category, java.util.UUID uploadId, Instant now) {
        return "%s/%s/%s/%s".formatted(INCOMING, category.prefix(), DATE_PATH.format(now), uploadId);
    }

    public static boolean isIncoming(String key) {
        return key != null && key.startsWith(INCOMING + "/");
    }

    /**
     * The key an accepted upload is copied to: the layout of {@link #generate}, with the upload's own id, so
     * attaching the same upload twice lands on the same object.
     */
    public static String forUpload(FileCategory category, String contentType, java.util.UUID uploadId, Instant created) {
        return "%s/%s/%s%s".formatted(category.prefix(), DATE_PATH.format(created), uploadId, extensionFor(contentType));
    }

    /** Whether photos of {@code category} get display copies ({@link #evidenceCopyOf}). */
    public static boolean hasDisplayCopies(FileCategory category) {
        return category == FileCategory.RETURN_EVIDENCE || category == FileCategory.QC_EVIDENCE;
    }

    /**
     * Where the display copy of an evidence photo at longest edge {@code edge} lives: the original's date and id under
     * {@code evidence-copies/<its category>/}, always JPEG - so finding it needs no column.
     */
    public static String evidenceCopyOf(String originalKey, int edge) {
        FileCategory category = categoryOf(originalKey);
        if (!hasDisplayCopies(category)) {
            throw new StorageException("Not an evidence photo key: " + originalKey);
        }
        String tail = originalKey.substring(category.prefix().length() + 1);
        int dot = tail.lastIndexOf('.');
        String stem = dot < 0 ? tail : tail.substring(0, dot);
        return "%s/%s/%s-%d.jpg".formatted(FileCategory.EVIDENCE_COPY.prefix(), category.prefix(), stem, edge);
    }

    public static String generate(FileCategory category, String contentType, Instant now) {
        return "%s/%s/%s%s".formatted(
                category.prefix(),
                DATE_PATH.format(now),
                Identifiers.newId(),
                extensionFor(contentType));
    }

    /**
     * The name a download is saved under: the uploader's name without its extension, plus the extension of what was
     * stored - which the key carries ({@link #extensionFor} of the type the bytes were checked against). The uploader
     * chooses the name, not what opens it: a JPEG named {@code invoice.pdf} whose bytes also parse as a PDF would
     * otherwise reach the accountant's PDF reader without ever having been checked as a PDF. Null for a blank name.
     */
    static String downloadName(String originalName, String key) {
        if (originalName == null || originalName.isBlank()) {
            return null;
        }
        String leaf = key.substring(key.lastIndexOf('/') + 1);
        int keyDot = leaf.lastIndexOf('.');
        String extension = keyDot < 0 ? "" : leaf.substring(keyDot).toLowerCase(Locale.ROOT);
        String base = originalName.strip();
        base = base.substring(Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        base = base.strip();
        if (base.isEmpty() || base.equals(".")) {
            base = "file";
        }
        // Short enough that the header's own limit never cuts the extension off; by code point, never mid-pair.
        int room = 200 - extension.length();
        if (base.codePointCount(0, base.length()) > room) {
            base = base.substring(0, base.offsetByCodePoints(0, room));
        }
        return base + extension;
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
        if (INCOMING.equals(prefix)) {
            // incoming/<category>/...: the second segment must be a category, so a quarantined key is ours too.
            String rest = key.substring(prefix.length() + 1);
            String category = rest.substring(0, Math.max(0, rest.indexOf('/')));
            for (FileCategory candidate : FileCategory.values()) {
                if (candidate.prefix().equals(category)) {
                    return key;
                }
            }
            throw new StorageException("Storage key is not in a known category: " + key);
        }
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
