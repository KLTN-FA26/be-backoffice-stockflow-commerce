package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PackingSession;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackingSessionStore;
import com.stockflow.inventory.api.TransferDispatchGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fulfillment's answer to inventory before a transfer's picking is cancelled (SCRUM-287 PR T; kltn-docs 10 §5, BR-04;
 * 09 BR-14). Runs in inventory's transaction; inventory then releases the transfer's holds on its source stock.
 *
 * <ul>
 *   <li>Picking cancelled before anything left (inventory refused it already once goods left, {@code
 *       TRANSFER_GOODS_LEFT}): the transfer's load plan is cancelled - refused while a vehicle of it is being loaded
 *       ({@code LOAD_PLAN_LOADING_STARTED}: only the controlled unloading of BR-14, not built yet) - then its parcels
 *       are voided with their labels and the packing session is CANCELLED (08 BR-05/06). The goods were never taken from
 *       stock: nothing is put back.</li>
 * </ul>
 *
 * <p>Lock order: the packing session's row, then (through {@link LoadPlanReleases}) the plans' and the loads'.</p>
 */
@Component
class TransferDispatchGuardService implements TransferDispatchGuard {

    private static final Logger log = LoggerFactory.getLogger(TransferDispatchGuardService.class);

    private final PackJpaRepository packs;
    private final PackingSessionStore store;
    private final LoadPlanReleases loadPlans;

    TransferDispatchGuardService(PackJpaRepository packs, PackingSessionStore store, LoadPlanReleases loadPlans) {
        this.packs = packs;
        this.store = store;
        this.loadPlans = loadPlans;
    }

    @Override
    @Transactional
    public void beforePickingCancelled(UUID transferId, Instant at) {
        List<PackJpaEntity> live = live(transferId).stream().map(p -> packs.lockById(p.getId()).orElseThrow())
                .toList();
        loadPlans.releaseTransfer(transferId, "picking of its transfer order was cancelled", at);
        for (PackJpaEntity pack : live) {
            PackingSession session = store.load(pack, "transfer", List.of());
            int voided = session.cancel(null, at).size();
            store.save(session, pack);
            log.info("Transfer {}: picking cancelled - packing session {} cancelled, {} package(s) voided",
                    transferId, pack.getId(), voided);
        }
    }

    private List<PackJpaEntity> live(UUID transferId) {
        return packs.findByTransferOrderIdOrderByCreatedAtDesc(transferId).stream()
                .filter(p -> p.getStatus() != PackStatus.CANCELLED).toList();
    }
}
