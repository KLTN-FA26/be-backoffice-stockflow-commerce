package com.stockflow.inventory.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The RMA numbers of return requests, for the cartons that came back on them (WF-05 Q-4; audit round 3, RAD-4): a
 * carton in quarantine waiting for its return's refund decision says which return it waits for, so the warehouse
 * manager is told "chờ duyệt hoàn tiền - RMA-..." instead of being offered a release the decision refuses.
 *
 * <p>An interface in {@code inventory :: api} implemented <b>by the order module</b>, as payment's
 * {@code InstallmentOrders}: {@code order} already depends on {@code inventory :: api} (reservations, returned cartons),
 * so inventory calling the order module would close a cycle {@code ModularityTest} rejects. Optional to inventory: a
 * context without the order module (the inventory module test) answers no numbers.</p>
 */
public interface ReturnRequestNumbers {

    /** By id, the RMA numbers of those of {@code returnRequestIds} that exist. */
    Map<UUID, String> rmaNumbers(Collection<UUID> returnRequestIds);
}
