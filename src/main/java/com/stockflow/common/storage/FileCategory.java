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

    /**
     * An image a customer places in a design of their own (kltn-docs 12 BR-04): a logo, a photo, a pattern - kept as
     * uploaded, at print resolution, so the size limit is a design render's. Private, never re-encoded and never shown
     * from a CDN: the bytes are what the producer prints, and a customer's artwork is nobody else's to see. Each
     * customization template narrows the types further; this is the widest set any template may allow.
     */
    DESIGN_ASSET("design-assets", 50 * 1024 * 1024,
            Set.of("image/jpeg", "image/png", "image/webp")),

    /**
     * A reference die-line the Sales Manager attaches to a customization template (kltn-docs 01 §4.10): what the
     * printed area looks like flat, for the customer to compare against. Only a reference - the print area itself is
     * computed from the template's model - so a PDF or a PNG; no SVG, whose embedded scripts would need a sanitizer
     * nobody has written.
     */
    PRINT_TEMPLATE("print-templates", 20 * 1024 * 1024,
            Set.of("application/pdf", "image/png")),

    /**
     * Documents kept with a business record: a purchase order's quotations, contracts, confirmations and delivery notes;
     * a load's waybill from its carrier, and a file of waybill numbers imported (CSV; kltn-docs 09 §4.4, BR-28). A
     * photo of a paper document too: a delivery note is signed on paper.
     */
    DOCUMENT("documents", 25 * 1024 * 1024,
            Set.of("application/pdf", "text/csv",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "image/jpeg", "image/png")),

    /**
     * A supplier's invoice as received (SCRUM-308): the PDF it came as, or a photo or scan of the paper. A financial
     * record - a replaced scan is kept in storage, not deleted.
     */
    SUPPLIER_INVOICE("supplier-invoices", 15 * 1024 * 1024,
            Set.of("application/pdf", "image/jpeg", "image/png")),

    /**
     * Photographs taken during goods receipt (receiving QC's from before it was removed among them) and of damaged
     * stock. Evidence - never deleted casually. The {@code qc-evidence/} prefix stays: stored keys and the database's
     * checks name it.
     */
    QC_EVIDENCE("qc-evidence", 20 * 1024 * 1024,
            Set.of("image/jpeg", "image/png")),

    /**
     * Photographs of a return (SCRUM-243): the customer's of the defect, the warehouse's and QC's of what came back.
     * Private, served only through a short presigned link; a phone photo, so WebP too, and no documents - a delivery
     * note is photographed like the goods.
     */
    RETURN_EVIDENCE("return-evidence", 15 * 1024 * 1024,
            Set.of("image/jpeg", "image/png", "image/webp")),

    /**
     * Display copies of evidence photos (return and damage photos): re-encoded without metadata at
     * {@link StorageKeys#evidenceCopyOf}, written next to the original by the platform, never uploaded. Private like the
     * originals; what a reviewer is shown.
     */
    EVIDENCE_COPY("evidence-copies", 10 * 1024 * 1024, Set.of("image/jpeg")),

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
