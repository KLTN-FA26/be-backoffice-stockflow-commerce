package com.stockflow.product.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A customer's own SKUs (kltn-docs 12 §4.5, 01 §4.8; owner decisions Q1, Q4, Q5 of 2026-11-26): issued when the Sales
 * Manager approves a customer's design, the only goods that are quoted, ordered and stocked, and visible to their owner
 * alone (13 BR-02). Their own table ({@code product.customer_skus}) since V20270105000100: a catalogue variant is never
 * one, and every method here answers about customers' SKUs only - a catalogue variant's code is "not a customer's SKU",
 * exactly like an unknown code.
 */
public interface CustomerSkus {

    /**
     * Issues the customer's SKU for an approved design snapshot, in the caller's transaction (the approval's: the design
     * is approved, frozen and given its SKU in one step, or not at all - 12 BR-09). The code is the product's code, the
     * customer's code and the next number for that customer on that product; the SKU is ACTIVE at once and is packed in
     * the source variant's carton - nothing is copied onto it (owner decision Q1), and it has no inventory item until an
     * order names it (Q5). Issuing again for the same snapshot returns the SKU issued before.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VARIANT_NOT_FOUND} for an unknown source SKU (a
     *         customer's SKU is not a source: it is not a variant); {@code PRODUCT_NOT_CUSTOMIZABLE};
     *         {@code CUSTOMER_SKU_SOURCE_UNAVAILABLE} when the product or the source variant is not on sale;
     *         {@code CONFLICT} when the snapshot already has a SKU of another customer or variant
     */
    CustomerSku issue(IssueCustomerSkuCommand command);

    /**
     * The code {@link #issue} gives the next SKU of {@code customerId} on {@code sourceSku}'s product, with that product
     * locked until the caller's transaction ends - the lock {@code issue} takes - so nothing else issues on it
     * meanwhile and the code stays the one issued. For a document that must name the SKU and be frozen before the SKU
     * can exist: the production instruction of an approval, which the snapshot the SKU names holds (kltn-docs 12 §4.5).
     *
     * @throws com.stockflow.common.error.BusinessException {@code VARIANT_NOT_FOUND} for an unknown source SKU;
     *         {@code VALIDATION_FAILED} for a customer code a SKU cannot hold
     */
    String nextSku(String sourceSku, UUID customerId, String customerCode);

    /** The SKU issued for an approved design snapshot, if any (one per snapshot: uk_customer_skus_design_snapshot). */
    Optional<CustomerSku> ofSnapshot(UUID designSnapshotId);

    /** {@link #ofSnapshot} of each of {@code designSnapshotIds}, by snapshot; a snapshot without a SKU is absent. */
    Map<UUID, CustomerSku> ofSnapshots(Collection<UUID> designSnapshotIds);

    /**
     * Refuses any of {@code skus} that is another customer's own SKU, as if it did not exist (kltn-docs 13 BR-02: other
     * customers' SKUs are invisible): what every order and quote line for {@code customerId} passes through. Catalogue
     * variants and unknown SKUs pass; whoever reads them next decides about those.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VARIANT_NOT_FOUND}
     */
    void requireVisibleTo(Collection<String> skus, UUID customerId);

    /** The owner of {@code sku}, or empty for a catalogue variant (or an unknown SKU). */
    Optional<UUID> ownerOf(String sku);

    /** The customer's own SKU with this code; empty for a catalogue variant or an unknown code. */
    Optional<CustomerSku> find(String sku);

    /**
     * The customer's own SKU with this id ({@link CustomerSku#id}) - what a chat conversation about it names (kltn-docs
     * 16 BR-02: its owner is checked against the conversation's customer); empty for an unknown id.
     */
    Optional<CustomerSku> findById(UUID id);

    /**
     * {@link #find} of each of {@code skus} in one read, by SKU as stored; catalogue and unknown SKUs are absent. What a
     * page of quote, order or contract lines reads to name each line's design without a read per line.
     */
    Map<String, CustomerSku> findAll(Collection<String> skus);

    /** {@link #ownerOf} of each of {@code skus} in one read, by SKU as stored; catalogue and unknown SKUs are absent. */
    Map<String, UUID> ownersOf(Collection<String> skus);

    /**
     * The variant (quy cách) each of {@code skus} was drawn on, by SKU as stored: whose standard price a quote line of
     * the SKU starts from and is measured against when the customer has no price of its own (kltn-docs 13 BR-04, 14
     * BR-10 as changed on 2026-11-26), and whose carton it is packed in. Catalogue and unknown SKUs are absent.
     */
    Map<String, String> sourceSkusOf(Collection<String> skus);

    /**
     * Of {@code skus}, those that are {@code customerId}'s own and ACTIVE, in one read bounded by the SKUs asked: what a
     * customer may be told about (kltn-docs 13 BR-01, BR-02). Codes as stored; unknown, other customers' and retired
     * SKUs are absent.
     */
    Set<String> activeOwnedBy(UUID customerId, Collection<String> skus);

    /** The customer's own SKUs, of one product when {@code productId} is not null. */
    List<CustomerSku> ownedBy(UUID customerId, UUID productId);

    /**
     * The customers' SKUs drawn on a catalogue variant, by code, every customer's and whatever their status: where the
     * stock of goods of that variant is, since a catalogue variant holds none of its own (V20270105000100). For the
     * back-office assistant, asked "how much kraft 12oz is there?" with the catalogue's code: it lists the stock of
     * these instead of answering that the code has no stock. Read only; who may be told whose they are is the
     * caller's to decide.
     *
     * @param sourceSku a catalogue variant's SKU, any case
     * @param limit     at most this many, 1..100
     * @return empty when {@code sourceSku} is no catalogue variant (a customer's SKU, or an unknown code)
     */
    Optional<DrawnSkus> drawnOn(String sourceSku, int limit);

    /**
     * @param skus  the first ones by code
     * @param total every customer SKU drawn on the variant
     */
    record DrawnSkus(List<CustomerSku> skus, long total) {

        public DrawnSkus {
            skus = List.copyOf(skus);
        }
    }
}
