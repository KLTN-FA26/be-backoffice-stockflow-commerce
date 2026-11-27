package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingRequest;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ItemRow;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import com.stockflow.inventory.internal.repository.VariantRotations;
import com.stockflow.inventory.spi.CartonRotation;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The reader of an item and its units that feeds the packaging rules (the rules themselves: PackagingRulesTest). */
class PackagingSpecResolverImplTest {

    private static final Sku SKU = new Sku("CUP-12OZ");
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID CARTON = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();

    private final InventoryPolicyRepository items = mock(InventoryPolicyRepository.class);
    private final UomConversionRepository conversions = mock(UomConversionRepository.class);
    /** The catalogue's answer (owner decision Q9: whether a carton may be turned is its variant's). */
    private final Map<UUID, Boolean> catalogue = new HashMap<>();
    private final PackagingSpecResolverImpl resolver =
            new PackagingSpecResolverImpl(items, conversions, new VariantRotations(answering(catalogue)));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CartonRotation> answering(Map<UUID, Boolean> catalogue) {
        ObjectProvider<CartonRotation> provider = mock(ObjectProvider.class);
        CartonRotation rotation = ids -> Map.copyOf(catalogue);
        when(provider.getIfAvailable()).thenReturn(rotation);
        return provider;
    }

    /** The item, its variant's cartons turning or not as the catalogue answers. */
    private ItemRow row(boolean rotatable) {
        catalogue.put(VARIANT, rotatable);
        return new ItemRow(ITEM, SKU.code(), 1, new ItemLogistics("PCS", null), VARIANT);
    }

    private static UomConversion carton(BigDecimal length) {
        return new UomConversion(CARTON, SKU.code(), "CARTON", 24, new BigDecimal("0.300"), length,
                new BigDecimal("20.5"), new BigDecimal("30"), true, 0);
    }

    @Test
    @DisplayName("a quantity under one base unit is VALIDATION_FAILED, before anything is read")
    void quantityMustBePositive() {
        assertThat(codeOf(() -> resolver.resolve(SKU, 0))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(items, never()).findBySkus(any());
    }

    @Test
    @DisplayName("a SKU with no inventory item is INVENTORY_ITEM_NOT_FOUND, naming it")
    void unknownSku() {
        when(items.findBySkus(any())).thenReturn(List.of());

        assertThat(codeOf(() -> resolver.resolve(SKU, 5))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("an inventory item id that does not exist is INVENTORY_ITEM_NOT_FOUND")
    void unknownItemId() {
        when(items.findSku(ITEM)).thenReturn(Optional.empty());

        assertThat(codeOf(() -> resolver.resolve(ITEM, 5))).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("48 units with a 24-per-carton default shipping unit pack into 2 whole cartons, no partial one")
    void resolvesWholeCartons() {
        when(items.findBySkus(any())).thenReturn(List.of(row(true)));
        when(conversions.bySkus(any())).thenReturn(Map.of(SKU.code(), List.of(carton(new BigDecimal("40")))));

        PackagingSpec spec = resolver.resolve(SKU, 48);

        assertThat(spec.status()).isEqualTo(PackagingSpec.Status.RESOLVED);
        assertThat(spec.fullCartons()).isEqualTo(2);
        assertThat(spec.partialCarton()).isNull();
        assertThat(spec.unitOfMeasure()).isEqualTo("CARTON");
        assertThat(spec.factor()).isEqualTo(24);
        assertThat(spec.inventoryItemId()).isEqualTo(ITEM);
    }

    @Test
    @DisplayName("50 units leave a partial carton of the 2 loose units")
    void resolvesPartialCarton() {
        when(items.findBySkus(any())).thenReturn(List.of(row(true)));
        when(conversions.bySkus(any())).thenReturn(Map.of(SKU.code(), List.of(carton(new BigDecimal("40")))));

        PackagingSpec spec = resolver.resolve(SKU, 50);

        assertThat(spec.fullCartons()).isEqualTo(2);
        assertThat(spec.partialCarton().units()).isEqualTo(2);
    }

    @Test
    @DisplayName("resolveAll answers in the order asked, one spec per line, reading each SKU once")
    void resolveAllKeepsOrder() {
        when(items.findBySkus(any())).thenReturn(List.of(row(true)));
        when(conversions.bySkus(any())).thenReturn(Map.of(SKU.code(), List.of(carton(new BigDecimal("40")))));

        List<PackagingSpec> specs = resolver.resolveAll(List.of(new PackagingRequest(SKU, 24),
                new PackagingRequest(SKU, 48)));

        assertThat(specs).extracting(PackagingSpec::quantity).containsExactly(24, 48);
        verify(items).findBySkus(org.mockito.ArgumentMatchers.<Collection<String>>argThat(skus -> skus.size() == 1));
    }

    @Test
    @DisplayName("a unit with all four of length, width, height and weight is a carton spec in whole millimetres, rounded up")
    void unitCartonInMillimetres() {
        when(conversions.find(SKU.code(), CARTON)).thenReturn(Optional.of(carton(new BigDecimal("40.01"))));
        when(items.findBySkus(any())).thenReturn(List.of(row(false)));

        PackagingSpec.CartonSpec spec = resolver.unitCarton(SKU, CARTON).orElseThrow();

        assertThat(spec.lengthMm()).isEqualTo(401);
        assertThat(spec.widthMm()).isEqualTo(205);
        assertThat(spec.heightMm()).isEqualTo(300);
        assertThat(spec.weightKg()).isEqualByComparingTo("0.300");
        assertThat(spec.rotatable()).isFalse();
    }

    @Test
    @DisplayName("a unit missing a dimension has no carton spec")
    void unitCartonIncomplete() {
        when(conversions.find(SKU.code(), CARTON)).thenReturn(Optional.of(carton(null)));

        assertThat(resolver.unitCarton(SKU, CARTON)).isEmpty();
    }

    @Test
    @DisplayName("an unknown unit has no carton spec")
    void unitCartonUnknown() {
        when(conversions.find(SKU.code(), CARTON)).thenReturn(Optional.empty());

        assertThat(resolver.unitCarton(SKU, CARTON)).isEmpty();
    }

    @Test
    @DisplayName("a carton is rotatable unless its variant says otherwise, also when the item cannot be read")
    void rotatableDefaultsToTrue() {
        when(conversions.find(SKU.code(), CARTON)).thenReturn(Optional.of(carton(new BigDecimal("40"))));
        when(items.findBySkus(any())).thenReturn(List.of());

        assertThat(resolver.unitCarton(SKU, CARTON).orElseThrow().rotatable()).isTrue();
    }
}
