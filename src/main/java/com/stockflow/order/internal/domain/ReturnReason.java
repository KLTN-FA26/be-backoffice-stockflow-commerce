package com.stockflow.order.internal.domain;

/**
 * Why the customer wants goods back (SCRUM-243). Mapped {@code EnumType.STRING}; {@code ck_return_request_reason}.
 *
 * <p>SCRUM-243 narrowed returns to production defects (B2B plan 2026-10-06), and BR-RMA-002 has goods printed to the
 * customer's design come back only when defective. Every reason but {@link #CHANGED_MIND} is the seller's fault - a
 * defect, a design that does not match the approved sample, damage on the way, the wrong goods, too few of them. A
 * change of mind is recorded rather than left out of the list so it can be refused by name and counted.</p>
 */
public enum ReturnReason {

    /** Lỗi in: smudged, faded, misregistered print, wrong colours. */
    PRINT_DEFECT,

    /** Sai thiết kế so với mẫu duyệt: the print does not match the sample the customer approved. */
    DESIGN_MISMATCH,

    /** Hư hỏng khi vận chuyển: crushed, broken, wet on arrival. */
    DAMAGED_IN_TRANSIT,

    /** Giao sai hàng: another SKU than the order line's arrived. */
    WRONG_ITEM,

    /** Giao thiếu: fewer units arrived than were delivered on paper; there is nothing to send back. */
    SHORT_DELIVERY,

    /** Đổi ý: not a fault of the seller. Refused for printed goods (BR-RMA-002), and by default for any goods. */
    CHANGED_MIND,

    /** Khác: described in the note, decided at review. */
    OTHER;

    /** Whether the seller is at fault: anything but a change of mind. */
    public boolean isSellerFault() {
        return this != CHANGED_MIND;
    }

    /** A short delivery has no goods to send back; every other claim may have to. */
    public boolean hasGoodsToReturn() {
        return this != SHORT_DELIVERY;
    }
}
