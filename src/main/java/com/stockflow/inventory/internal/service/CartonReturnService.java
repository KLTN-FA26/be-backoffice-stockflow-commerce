package com.stockflow.inventory.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.CartonReturns;
import com.stockflow.inventory.api.CustodyInstruction;
import com.stockflow.inventory.api.HeldCarton;
import com.stockflow.inventory.api.ReceiveReturnedCartonsCommand;
import com.stockflow.inventory.api.RefundedSku;
import com.stockflow.inventory.api.ReturnedCarton;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.ReturnedCartonOwnership;
import com.stockflow.inventory.internal.domain.ReturnedCartonSettlement;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cartons received back on a return receipt (team plan 4D-2; kltn-docs 03 §4.2 step 12, BR-03, BR-04, BR-08, BR-14;
 * 17 BR-18; 09 BR-06). Returned goods never go back on sale here: each carton comes back as itself - the same LPN, the
 * same lot, whole and sealed - into a QUARANTINE area, and a warehouse manager decides on it afterwards
 * ({@link CartonQuarantineService#release}, or a {@code DAMAGED} write-off). No RECEIVING, no putaway task, nothing
 * opened or relabelled: a return receipt does not make a carton, it recognises one.
 *
 * <p>A carton the receipt found opened or empty (WF-05 TH-4) is not put into stock at all (WF-05 Q-18; Luật Kế toán
 * Điều 13 khoản 1). The books know a carton only whole, at its label's count (20 BR-14), so taking it in would make
 * stock out of a label - 1,000 on hand for a carton counted 800, or 0 - to be written off later. It stays CONSUMED,
 * marked {@code return_opened_at} (it is never taken back again), with no ledger line; what it really held is the
 * receipt's record, the return refunds that, and the goods are destroyed. Quarantine holds exactly what sealed cartons
 * brought.</p>
 *
 * <p><b>Whose goods</b> (WF-05 Q-4; Luật Thương mại 2005 Điều 62): a sealed carton of a return request goes into
 * quarantine, but it is the company's only once the return is agreed - its refund approved with units of that SKU
 * refunded. Until then it waits ({@code PENDING}): no release, no write-off. Goods the refund does not take back stay
 * the customer's, per SKU and carton (audit round 3, RAD-1): {@link #settleOwnership} reverses each such carton's
 * stock-in (no stock-in entry stands for goods nobody agreed to take back) and holds it for the customer where it
 * stands, until the warehouse manager hands it back or destroys it as the customer chose ({@link CustomerGoodsCustody}).
 * A carrier's return is the company's goods outright.</p>
 */
@Service
@Transactional
public class CartonReturnService implements CartonReturns {

    private static final Logger log = LoggerFactory.getLogger(CartonReturnService.class);

    private final StockPackageRepository stockPackages;
    private final StockPackageContents cartons;
    private final UomConversionRepository units;
    private final InventoryPolicyRepository policies;
    private final LocationDirectory locations;
    private final StockMovementLog ledger;
    private final ReturnedCartonOwnership ownership;

    public CartonReturnService(StockPackageRepository stockPackages,
                               StockPackageContents cartons, UomConversionRepository units,
                               InventoryPolicyRepository policies, LocationDirectory locations,
                               StockMovementLog ledger, ReturnedCartonOwnership ownership) {
        this.ownership = ownership;
        this.stockPackages = stockPackages;
        this.cartons = cartons;
        this.units = units;
        this.policies = policies;
        this.locations = locations;
        this.ledger = ledger;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReturnedCarton> find(String lpn) {
        String normal = StockPackageView.normalLpn(lpn);
        if (normal == null) {
            return Optional.empty();
        }
        return stockPackages.findByLpns(List.of(normal)).stream().findFirst().map(carton -> {
            UomConversion unit = unitOf(carton);
            Optional<ReturnedCartonOwnership.Owned> owned = ownership.owned(carton.id());
            ReturnedCarton seen = view(carton, unit.sku(), lotOf(carton),
                    stockPackages.returnOpenedAt(carton.id()).orElse(null),
                    owned.map(o -> o.ownership().name()).orElse(null));
            return new ReturnedCarton(seen.stockPackageId(), seen.lpn(), seen.sku(), seen.lotNumber(), seen.quantity(),
                    seen.status(), seen.locationCode(), seen.returnOpenedAt(), seen.returnOwnership(),
                    owned.map(ReturnedCartonOwnership.Owned::returnRequestId).orElse(null));
        });
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "stock-package-return", resourceId = "#command.receiptId()",
            details = "{lpns: #command.lpns(), to: #command.locationCode()}")
    public List<ReturnedCarton> receive(ReceiveReturnedCartonsCommand command) {
        LocationId to = new LocationId(command.locationCode());
        // BR-SEC-002 first: a refusal must not tell anything about another warehouse's area.
        WarehouseScope.requireLocation(to.code());
        switch (locations.stateOf(to)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + to);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it or its warehouse is not active".formatted(to));
            case USABLE -> { }
        }
        if (!locations.isQuarantineArea(to)) {
            throw new BusinessException(ErrorCode.LOCATION_AREA_MISMATCH,
                    "%s is not a QUARANTINE area; returned goods come back into one (BR-14)".formatted(to));
        }
        Map<String, StockPackage> byLpn = stockPackages.findByLpns(command.lpns()).stream()
                .collect(Collectors.toMap(StockPackage::lpn, Function.identity()));
        List<String> unknown = command.lpns().stream().filter(lpn -> !byLpn.containsKey(lpn)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + String.join(", ", unknown),
                    List.of(), Map.of("lpns", unknown));
        }
        for (StockPackage seen : byLpn.values()) {
            // Stock never changes warehouse: a carton comes back to the one it left from.
            if (!seen.location().warehouseCode().equals(to.warehouseCode())) {
                throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                        "%s left from another warehouse than %s".formatted(seen.lpn(), to), List.of(),
                        Map.of("lpn", seen.lpn()));
            }
        }
        Map<String, String> skuByLpn = byLpn.values().stream()
                .collect(Collectors.toMap(StockPackage::lpn, carton -> unitOf(carton).sku()));

        // The global lock order: SKUs in code order, then every carton (in id order).
        skuByLpn.values().stream().distinct().sorted().forEach(policies::lock);
        stockPackages.findAllByIdForUpdate(byLpn.values().stream().map(StockPackage::id).toList());

        List<ReturnedCarton> received = new ArrayList<>();
        List<StockPackageId> stocked = new ArrayList<>();
        for (String lpn : command.lpns()) {
            StockPackage seen = byLpn.get(lpn);
            Optional<ReturnedCartonOwnership.Ownership> owned = ownership.of(seen.id());
            if (owned.filter(o -> o == ReturnedCartonOwnership.Ownership.CUSTOMER_HELD
                    || o == ReturnedCartonOwnership.Ownership.CUSTOMER_DESTROYED).isPresent()) {
                // WF-05 Q-4: the customer's goods, held for them where they stand or destroyed on their behalf - they
                // never left, or no longer exist; a receipt does not take them in.
                throw new BusinessException(ErrorCode.RETURN_CARTON_IN_WAREHOUSE, ("%s is the customer's, %s: it is not "
                        + "taken back on a receipt").formatted(lpn, owned.get()), List.of(), Map.of("lpn", lpn));
            }
            if (stockPackages.openedOnReturn(seen.id())) {
                // WF-05 Q-18: it came back opened on an earlier receipt and stayed consumed; it does not come back twice.
                throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, ("%s came back opened on an "
                        + "earlier return receipt; it is not taken back again").formatted(lpn), List.of(),
                        Map.of("lpn", lpn));
            }
            if (command.openedLpns().contains(lpn)) {
                StockPackage locked = stockPackages.findAllByIdForUpdate(List.of(seen.id())).stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("No stock package " + lpn));
                if (locked.status() != com.stockflow.inventory.internal.domain.StockPackageStatus.CONSUMED) {
                    throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, ("%s is %s; only a carton "
                            + "that left stock comes back on a return").formatted(lpn, locked.status()), List.of(),
                            Map.of("lpn", lpn));
                }
                received.add(new ReturnedCarton(seen.id().value(), lpn, skuByLpn.get(lpn), lotOf(seen),
                        seen.counted() ? seen.nominalQty() : 0, "CONSUMED", locked.location().code(),
                        command.receivedAt()));
                log.info("Return receipt {}: carton {} came back opened: not put into stock, destroyed",
                        command.receiptId(), lpn);
                continue;
            }
            StockPackage back = cartons.returnIntoQuarantine(seen.id(), to);
            ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.RECEIPT, back.sku(),
                    back.lotNumber(), null, to, StockStatus.QUARANTINE, StockStatus.QUARANTINE,
                    StockMovement.ReferenceType.GOODS_RECEIPT, command.receiptId(), null, command.actorId(),
                    command.receivedAt(), seen.id())
                    .ofLot(back.lotId()).forOrder(command.orderId()));
            stocked.add(seen.id());
            received.add(new ReturnedCarton(seen.id().value(), lpn, skuByLpn.get(lpn), back.lotNumber(),
                    back.unitsInStock(), "IN_STOCK", to.code(), null,
                    command.returnRequestId() == null ? null : ReturnedCartonOwnership.Ownership.PENDING.name()));
            log.info("Return receipt {}: carton {} ({} x {}, lot {}) back into quarantine at {}", command.receiptId(),
                    lpn, back.unitsInStock(), back.sku(), back.lotNumber(), to);
        }
        stockPackages.markOpenedOnReturn(command.openedLpns().stream().map(lpn -> byLpn.get(lpn).id()).toList(),
                command.receivedAt());
        // WF-05 Q-4: a return request's cartons wait for its refund decision; a carrier's return is the company's.
        ownership.cameBack(stocked, command.returnRequestId());
        return received;
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "stock-package-return", resourceId = "#returnRequestId",
            details = "{refunded: #refunded}")
    public int settleOwnership(UUID returnRequestId, List<RefundedSku> refunded, UUID actorId, Instant at) {
        List<ReturnedCartonOwnership.Waiting> waiting = ownership.waitingFor(returnRequestId);
        if (waiting.isEmpty()) {
            return 0;
        }
        Map<String, ReturnedCartonSettlement.Decided> bySku = new java.util.HashMap<>();
        for (RefundedSku sku : refunded) {
            bySku.merge(sku.sku(), new ReturnedCartonSettlement.Decided(sku.unitsBack(), sku.unitsRefunded()),
                    (a, b) -> new ReturnedCartonSettlement.Decided(a.unitsBack() + b.unitsBack(),
                            a.unitsRefunded() + b.unitsRefunded()));
        }
        Map<ReturnedCartonOwnership.Waiting, ReturnedCartonOwnership.Ownership> split =
                ReturnedCartonSettlement.split(waiting, bySku);
        List<StockPackageId> company = split.entrySet().stream()
                .filter(e -> e.getValue() == ReturnedCartonOwnership.Ownership.COMPANY)
                .map(e -> e.getKey().id()).toList();
        List<StockPackageId> customers = split.entrySet().stream()
                .filter(e -> e.getValue() == ReturnedCartonOwnership.Ownership.CUSTOMER_HELD)
                .map(e -> e.getKey().id()).toList();
        // The global lock order: SKUs in code order, then every carton (in id order) - all of the return's waiting
        // cartons, before any of them changes.
        waiting.stream().map(ReturnedCartonOwnership.Waiting::sku).distinct().sorted().forEach(policies::lock);
        List<StockPackage> locked = stockPackages.findAllByIdForUpdate(waiting.stream()
                .map(ReturnedCartonOwnership.Waiting::id).toList());
        ownership.settle(company, ReturnedCartonOwnership.Ownership.COMPANY, at);
        if (!company.isEmpty()) {
            log.info("Return {}: {} carton(s) back in quarantine are the company's now", returnRequestId,
                    company.size());
        }
        if (customers.isEmpty()) {
            return waiting.size();
        }
        Set<StockPackageId> customersSet = Set.copyOf(customers);
        for (StockPackage carton : locked.stream().filter(c -> customersSet.contains(c.id())).toList()) {
            StockPackageContents.CartonChange change = cartons.reverseReturn(carton.id(), at);
            // No stock-in entry stands for goods nobody agreed to take back (Q-4): the receipt's line is reversed, the
            // whole carton out of the quarantine area it stands in, naming the return.
            ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.ADJUSTMENT, carton.sku(),
                    carton.lotNumber(), change.location(), null, StockStatus.QUARANTINE,
                    StockStatus.QUARANTINE, StockMovement.ReferenceType.RETURN_REQUEST, returnRequestId,
                    "Return did not refund these goods: the customer's, held for them (WF-05 Q-4)", actorId, at,
                    carton.id())
                    .ofLot(carton.lotId()));
            log.info("Return {}: carton {} ({} x {}) not refunded - out of stock, held for the customer at {}",
                    returnRequestId, carton.lpn(), -change.delta(), carton.sku(), change.location());
        }
        ownership.settle(customers, ReturnedCartonOwnership.Ownership.CUSTOMER_HELD, at);
        return waiting.size();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HeldCarton> heldFor(UUID returnRequestId) {
        return ownership.custodyOf(returnRequestId).stream().map(c -> new HeldCarton(c.lpn(), c.sku(), c.quantity(),
                c.locationCode(), c.ownership().name(), c.choice(), c.heldAt(), c.endedAt())).toList();
    }

    @Override
    public int instructCustody(UUID returnRequestId, CustodyInstruction instruction) {
        List<ReturnedCartonOwnership.Custody> custody = ownership.custodyOf(returnRequestId);
        custody.stream().filter(c -> c.ownership() != ReturnedCartonOwnership.Ownership.CUSTOMER_HELD).findFirst()
                .ifPresent(ended -> {
                    throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, ("Carton %s of this return "
                            + "was already %s: the customer's choice is carried out").formatted(ended.lpn(),
                            ended.ownership()), List.of(), Map.of("lpn", ended.lpn()));
                });
        if (custody.isEmpty()) {
            return 0;
        }
        ownership.instruct(returnRequestId, instruction.name());
        return custody.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> reversedBy(UUID returnRequestId, Collection<String> lpns) {
        return returnRequestId == null ? Set.of()
                : ownership.reversedBy(returnRequestId, StockPackageView.normalLpns(List.copyOf(lpns)));
    }

    private UomConversion unitOf(StockPackage carton) {
        return units.findAll(List.of(carton.uomConversionId())).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Stock package %s is in no known unit".formatted(carton.lpn())));
    }

    /** The carton's lot number: a carton keeps its lot for good, in stock or out (ADR-0011). */
    private static String lotOf(StockPackage carton) {
        return carton.lotNumber();
    }

    private static ReturnedCarton view(StockPackage carton, String sku, String lot, java.time.Instant openedAt,
                                       String owned) {
        return new ReturnedCarton(carton.id().value(), carton.lpn(), sku, lot,
                carton.counted() ? carton.nominalQty() : 0, carton.status().name(),
                carton.location().code(), openedAt, owned);
    }
}
