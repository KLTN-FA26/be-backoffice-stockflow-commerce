package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.api.ReturnedCarton;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.ReturnedCartonOwnership;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner decision Q1 (2026-11-26): a carton row is its catalogue variant's and every SKU of the variant shares it, so a
 * carton that comes back is the SKU of its own item - a customer's SKU - never its carton row's.
 */
class CartonReturnServiceTest {

    private static final String LPN = "LPN-20261109-000001";
    private static final UUID ITEM = UUID.randomUUID();

    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final CartonReturnService returns = new CartonReturnService(stockPackages, mock(StockItemRepository.class),
            mock(StockPackageContents.class), policies, mock(LocationDirectory.class), mock(StockMovementLog.class),
            mock(ReturnedCartonOwnership.class));

    @Test
    @DisplayName("a carton looked up on a return names its own item's SKU, the customer's, not its variant's carton row")
    void foundCartonNamesItsOwnSku() {
        StockPackage carton = new StockPackage(new StockPackageId(UUID.randomUUID()), LPN, ITEM, UUID.randomUUID(), 24,
                24, new LocationId("WH1-QC01"), StockPackageStatus.IN_STOCK, UUID.randomUUID(), null, 0L);
        when(stockPackages.findByLpns(List.of(LPN))).thenReturn(List.of(carton));
        when(policies.skusOfItems(List.of(ITEM))).thenReturn(Map.of(ITEM, "CUP-12OZ-MOC"));

        ReturnedCarton found = returns.find(LPN).orElseThrow();

        assertThat(found.sku()).isEqualTo("CUP-12OZ-MOC");
    }
}
