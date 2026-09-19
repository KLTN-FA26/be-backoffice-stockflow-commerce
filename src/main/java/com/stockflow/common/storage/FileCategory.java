package com.stockflow.common.storage;

import java.util.Set;

/**
 * What a file is for. Determines where it is stored, what types are accepted, and how large it may
 * be.
 *
 * <p>A closed set rather than a free-text folder name, so the limits are decided once per kind of
 * file rather than at each upload site. It also makes "what is in the bucket" answerable: the
 * category is the first segment of every key.</p>
 */
public enum FileCategory {

    /**
     * Approved display renditions, and nothing else: the CDN serves this whole prefix. Objects get
     * here only by {@link FileTransfers#publish} when a gallery is approved - never by an upload -
     * so a draft image is never reachable from the internet.
     */
    PRODUCT_IMAGE("product-images", 10 * 1024 * 1024,
            Set.of("image/jpeg", "image/png", "image/webp")),

    /**
     * Re-encoded display renditions as generated at upload, before anyone has approved them.
     * Private, like the original; a rendition is copied to {@link #PRODUCT_IMAGE} on approval.
     */
    PRODUCT_RENDITION("product-renditions", 10 * 1024 * 1024,
            Set.of("image/jpeg", "image/png", "image/webp")),

    /**
     * The photograph exactly as uploaded, EXIF/GPS included. Private: outside the CDN's origin
     * path and bucket policy, reachable only through a presigned link from the application.
     */
    PRODUCT_IMAGE_ORIGINAL("product-originals", 10 * 1024 * 1024,
            Set.of("image/jpeg", "image/png", "image/webp")),

    /** Renders produced by the design studio. Larger, because they are print-resolution. */
    DESIGN_RENDER("design-renders", 50 * 1024 * 1024,
            Set.of("image/jpeg", "image/png", "image/webp", "application/pdf")),

    /** Supplier documents, contracts, certificates of conformity. */
    DOCUMENT("documents", 25 * 1024 * 1024,
            Set.of("application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),

    /** Photographs taken during goods receipt and QC. Evidence - never deleted casually. */
    QC_EVIDENCE("qc-evidence", 20 * 1024 * 1024,
            Set.of("image/jpeg", "image/png")),

    /** Generated reports awaiting download. Short-lived; the retention job clears them. */
    EXPORT("exports", 100 * 1024 * 1024,
            Set.of("text/csv",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/pdf"));

    private final String prefix;
    private final long maxBytes;
    private final Set<String> allowedContentTypes;

    FileCategory(String prefix, long maxBytes, Set<String> allowedContentTypes) {
        this.prefix = prefix;
        this.maxBytes = maxBytes;
        this.allowedContentTypes = allowedContentTypes;
    }

    public String prefix() {
        return prefix;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /**
     * The types this category accepts.
     *
     * <p>An allow-list, always. A deny-list of dangerous types is a list somebody has to keep
     * complete for ever, and the first thing missed is the one that matters — an SVG with an
     * embedded script served from the same origin as the application is stored cross-site
     * scripting.</p>
     */
    public Set<String> allowedContentTypes() {
        return allowedContentTypes;
    }
}
