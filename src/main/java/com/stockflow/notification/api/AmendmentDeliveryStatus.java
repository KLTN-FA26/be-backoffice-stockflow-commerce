package com.stockflow.notification.api;

/**
 * The delivery state of an order's newest amendment notice that was attempted (SCRUM-117), with the revision it
 * announced. The revision is part of the answer because the newest notice attempted is not always the newest
 * amendment approved: a later one that was not attempted yet must not borrow the previous one's state, and only the
 * caller knows which revision is active.
 *
 * @param revisionNo the amendment revision the notice announced
 * @param status     {@code DELIVERED}, {@code RETRYING} or {@code FAILED}
 */
public record AmendmentDeliveryStatus(int revisionNo, String status) {
}
