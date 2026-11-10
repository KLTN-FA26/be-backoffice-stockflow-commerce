package com.stockflow.inventory.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.CartonReturns;
import com.stockflow.inventory.api.ReceiveReturnedCartonsCommand;
import com.stockflow.inventory.api.ReturnedCarton;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cartons received back on a return receipt (team plan 4D-2; kltn-docs 03 §4.2 step 12, BR-03, BR-04, BR-08, BR-14;
 * 17 BR-18; 09 BR-06). Returned goods never go back on sale here: each carton comes back as itself - the same LPN, the
 * same lot, whole and sealed - into a QUARANTINE area, and a warehouse manager decides on it afterwards
 * ({@link CartonQuarantineService#release}, or a {@code DAMAGED} write-off). No RECEIVING, no putaway task, nothing
 * opened or relabelled: a return receipt does not make a carton, it recognises one.
 */
@Service
@Transactional
public class CartonReturnService implements CartonReturns {

    private static final Logger log = LoggerFactory.getLogger(CartonReturnService.class);

    private final StockPackageRepository stockPackages;
    private final StockItemRepository stockItems;
    private final StockPackageContents cartons;
    private final UomConversionRepository units;
    private final InventoryPolicyRepository policies;
    private final LocationDirectory locations;
    private final StockMovementLog ledger;

    public CartonReturnService(StockPackageRepository stockPackages, StockItemRepository stockItems,
                               StockPackageContents cartons, UomConversionRepository units,
                               InventoryPolicyRepository policies, LocationDirectory locations,
                               StockMovementLog ledger) {
        this.stockPackages = stockPackages;
        this.stockItems = stockItems;
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
            return view(carton, unit.sku(), lotOf(carton));
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

        // The global lock order: SKUs in code order, every carton (in id order), then each carton's row.
        skuByLpn.values().stream().distinct().sorted().forEach(policies::lock);
        stockPackages.findAllByIdForUpdate(byLpn.values().stream().map(StockPackage::id).toList());

        List<ReturnedCarton> received = new ArrayList<>();
        for (String lpn : command.lpns()) {
            StockPackage seen = byLpn.get(lpn);
            StockItem row = cartons.returnIntoQuarantine(seen.id(), to);
            ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.RECEIPT, row.sku(),
                    row.lotNumber(), null, to, row.onHand().value(), StockStatus.QUARANTINE, StockStatus.QUARANTINE,
                    StockMovement.ReferenceType.GOODS_RECEIPT, command.receiptId(), null, command.actorId(),
                    command.receivedAt())
                    .ofLot(row.lotId()).forOrder(command.orderId()).arriving(seen.id()));
            received.add(new ReturnedCarton(seen.id().value(), lpn, skuByLpn.get(lpn), row.lotNumber(),
                    row.onHand().value(), "IN_STOCK", to.code()));
            log.info("Return receipt {}: carton {} ({} x {}, lot {}) back into quarantine at {}", command.receiptId(),
                    lpn, row.onHand().value(), row.sku(), row.lotNumber(), to);
        }
        return received;
    }

    private UomConversion unitOf(StockPackage carton) {
        return units.findAll(List.of(carton.uomConversionId())).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Stock package %s is in no known unit".formatted(carton.lpn())));
    }

    /** The lot of the carton's row: one row per carton since team plan 4C-1, and a row keeps its lot for good. */
    private String lotOf(StockPackage carton) {
        return stockItems.findIdsInStockPackage(carton.id()).stream()
                .map(stockItems::findById).flatMap(Optional::stream)
                .min(Comparator.comparing(row -> row.id().value().toString()))
                .map(StockItem::lotNumber).orElse(null);
    }

    private static ReturnedCarton view(StockPackage carton, String sku, String lot) {
        return new ReturnedCarton(carton.id().value(), carton.lpn(), sku, lot,
                carton.receivedQty() == null ? 0 : carton.receivedQty(), carton.status().name(),
                carton.location().code());
    }
}
