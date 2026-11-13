package com.stockflow.common.storage;

import java.util.Set;

/**
 * What a file is for. Determines where it is stored, what types are accepted, and how large it may
 * be.
 *
 * <p>A closed set rather than a free-text folder name, so the limits are decided once per kind of
 * file rather than at each upload site. Supplier invoice scans ({@code supplier-invoices/}) went with purchasing
 * (4A-8, kltn-docs 09-removal row 22): the category is gone and nothing writes or sweeps that prefix. It also makes "what is in the bucket" answerable: the
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
     * Documents kept with a business record: a production order's slip (4A-3), a load's waybill from its carrier
     * (kltn-docs 09 §4.4, BR-28). A photo of a paper document too: a delivery note is signed on paper. A purchase
     * order's attachments were kept here until purchasing went (4A-8): no record claims them any more, so the
     * orphaned-file sweep finds them orphaned - reported, and deleted where it is told to delete - as kltn-docs
     * 09-removal row 22 drops them.
     */
    DOCUMENT("documents", 25 * 1024 * 1024,
            Set.of("application/pdf",
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
     * Photographs taken during goods receipt (receiving QC's from before it was removed among them). Evidence - never
     * deleted casually. The {@code qc-evidence/} prefix stays: stored keys and the database's checks name it. The photos
     * of unit-level damage reports were stored here too; since team plan 5-6 (V20261114000400) no record names them,
     * so the orphaned-file sweep reports them and deletes them when told to.
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
     * Photos and videos of a production order's print samples (4A-4, kltn-docs 17 §4.3 step 9): what the producer printed
     * to the approved file, uploaded by the Production Coordinator for the customer to approve or send back. Private,
     * shown to the coordinator, Sales and the customer who owns the order through a short link; a phone video, so the
     * size limit is a video's - uploaded straight to the store, the multipart limit being lower.
     */
    PRINT_SAMPLE("print-samples", 100 * 1024 * 1024,
            Set.of("image/jpeg", "image/png", "image/webp", "video/mp4", "video/quicktime", "video/webm")),

    /**
     * Display copies of evidence photos (return and goods-receipt photos): re-encoded without metadata at
     * {@link StorageKeys#evidenceCopyOf}, written next to the original by the platform, never uploaded. Private like the
     * originals; what a reviewer is shown.
     */
    EVIDENCE_COPY("evidence-copies", 10 * 1024 * 1024, Set.of("image/jpeg")),

    /**
     * A file of waybill numbers imported from the system's template, and the extract of it kept with a warehouse's loads
     * (3-3; kltn-docs 09 §4.4 step 4, QD-17: CSV UTF-8). A category of its own: CSV has no signature to check, so it is
     * not let into a category whose every other type is checked.
     */
    WAYBILL_IMPORT("waybill-imports", 2 * 1024 * 1024, Set.of("text/csv")),

    /**
     * An issued e-invoice as the provider returned it (WF-05 E2; kltn-docs 21 §1): the XML, which is the invoice in
     * law, and the PDF people read. Written by the system when the invoice is issued, and locked for ten years
     * ({@link RecordRetention#EINVOICE}; Luật Kế toán 2015 Điều 41). The one upload here is the signed agreement of
     * the two parties attached to a correction of an issued invoice (WF-05 E4; TT 91/2026/TT-BTC Điều 10 khoản 1 điểm
     * b), a PDF kept and locked with the invoice it justifies. No module claims the prefix for the orphaned-file
     * sweep, so nothing under it is ever swept.
     */
    EINVOICE("einvoices", 10 * 1024 * 1024, Set.of("application/xml", "application/pdf")),

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
