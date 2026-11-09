package com.stockflow.inventory.internal.controller;

import com.stockflow.common.security.AuthenticatedUserArgumentResolver;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.service.StockOperations;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP edge of a person's move (kltn-docs 11, team plan 4C-2): cartons scanned by LPN and one destination, no SKU,
 * lot or quantity; the request id becomes the ledger reference that makes a resend harmless. And of an adjustment
 * (kltn-docs 20 §4.3, team plan 4C-3): one carton by LPN, a direction and a reason - never a quantity, a location, a
 * SKU or a lot.
 */
class StockOperationsControllerHttpTest {

    private final StockOperations operations = mock(StockOperations.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final UUID clerk = UUID.randomUUID();
    private final MockMvc mvc;

    StockOperationsControllerHttpTest() {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(clerk, "keeper",
                Set.of(), Set.of(), DataScope.ALL, Set.of())));
        mvc = MockMvcBuilders.standaloneSetup(new StockOperationsController(operations,
                        new StockOperationsWebMapperImpl()))
                .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver(users)).build();
    }

    @Test
    void cartonsAreMovedByLpnToOneDestination() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(operations.moveStockPackages(any())).thenReturn(List.of(new StockPackageView(UUID.randomUUID(),
                "LPN-20261109-000001", null, "CUP_PET", UUID.randomUUID(), "CARTON", 50, 50, 50, "HCM-A01-1-A",
                "IN_STOCK")));

        mvc.perform(post("/api/v1/inventory/movements").contentType("application/json").content("""
                        {"requestId":"%s","lpns":["lpn-20261109-000001","LPN-20261109-000002"],
                         "toLocation":"HCM-A01-1-A"}""".formatted(requestId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].lpn").value("LPN-20261109-000001"))
                .andExpect(jsonPath("$.data[0].locationCode").value("HCM-A01-1-A"))
                .andExpect(jsonPath("$.data[0].quantity").value(50));

        ArgumentCaptor<MoveStockPackagesCommand> sent = ArgumentCaptor.forClass(MoveStockPackagesCommand.class);
        verify(operations).moveStockPackages(sent.capture());
        assertThat(sent.getValue().lpns()).containsExactly("LPN-20261109-000001", "LPN-20261109-000002");
        assertThat(sent.getValue().toLocationCode()).isEqualTo("HCM-A01-1-A");
        assertThat(sent.getValue().reference()).isEqualTo(MoveReference.MOVE_TASK);
        assertThat(sent.getValue().referenceId()).isEqualTo(requestId);
        assertThat(sent.getValue().actorId()).isEqualTo(clerk);
    }

    @Test
    void aMoveWithoutCartonsOrInTheOldLooseShapeIsRefused() throws Exception {
        mvc.perform(post("/api/v1/inventory/movements").contentType("application/json").content("""
                        {"requestId":"%s","lpns":[],"toLocation":"B"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/inventory/movements").contentType("application/json").content("""
                        {"requestId":"%s","sku":"CUP_PET","fromLocation":"A","toLocation":"B","quantity":1}"""
                        .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(operations);
    }

    private static StockAdjustmentSummary summary(UUID carton) {
        return new StockAdjustmentSummary(UUID.randomUUID(), "ADJ-20261113-0001", "HCM-A01-1-A", "CUP_PET", "L1", -50,
                StockAdjustmentReason.DAMAGED, "Crushed", StockAdjustmentStatus.PENDING_APPROVAL, UUID.randomUUID(),
                java.time.Instant.now(), null, null, null, null, null, 0L, carton, null);
    }

    @Test
    void anAdjustmentNamesOneCartonByLpnADirectionAndAReason() throws Exception {
        UUID carton = UUID.randomUUID();
        when(operations.requestPackageAdjustment(any())).thenReturn(summary(carton));

        mvc.perform(post("/api/v1/inventory/adjustments").contentType("application/json").content("""
                        {"lpn":"lpn-20261113-000001","direction":"WRITE_OFF","reason":"DAMAGED","note":"Crushed"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.stockPackageId").value(carton.toString()))
                .andExpect(jsonPath("$.data.quantityDelta").value(-50));

        ArgumentCaptor<StockOperations.PackageAdjustmentRequest> sent =
                ArgumentCaptor.forClass(StockOperations.PackageAdjustmentRequest.class);
        verify(operations).requestPackageAdjustment(sent.capture());
        assertThat(sent.getValue().lpn()).isEqualToIgnoringCase("LPN-20261113-000001");
        assertThat(sent.getValue().direction()).isEqualTo(AdjustmentDirection.WRITE_OFF);
        assertThat(sent.getValue().reason()).isEqualTo(StockAdjustmentReason.DAMAGED);
        assertThat(sent.getValue().note()).isEqualTo("Crushed");
        assertThat(sent.getValue().requestedBy()).isEqualTo(clerk);
    }

    @Test
    void aRestockIsAskedTheSameWay() throws Exception {
        when(operations.requestPackageAdjustment(any())).thenReturn(summary(UUID.randomUUID()));

        mvc.perform(post("/api/v1/inventory/adjustments").contentType("application/json").content("""
                        {"lpn":"LPN-20261113-000001","direction":"RESTOCK","reason":"FOUND"}"""))
                .andExpect(status().isCreated());

        ArgumentCaptor<StockOperations.PackageAdjustmentRequest> sent =
                ArgumentCaptor.forClass(StockOperations.PackageAdjustmentRequest.class);
        verify(operations).requestPackageAdjustment(sent.capture());
        assertThat(sent.getValue().direction()).isEqualTo(AdjustmentDirection.RESTOCK);
        assertThat(sent.getValue().note()).isNull();
    }

    @Test
    void anAdjustmentWithoutALpnDirectionOrReasonOrInTheOldLooseShapeIsRefused() throws Exception {
        for (String body : List.of(
                "{\"direction\":\"WRITE_OFF\",\"reason\":\"DAMAGED\"}",
                "{\"lpn\":\"LPN-1\",\"reason\":\"DAMAGED\"}",
                "{\"lpn\":\"LPN-1\",\"direction\":\"WRITE_OFF\"}",
                "{\"lpn\":\"LPN-1\",\"direction\":\"SIDEWAYS\",\"reason\":\"DAMAGED\"}",
                "{\"lpn\":\"LPN-1\",\"direction\":\"WRITE_OFF\",\"reason\":\"NOT_A_REASON\"}",
                "{\"locationCode\":\"HCM-A01-1-A\",\"sku\":\"CUP_PET\",\"quantityDelta\":-3,\"reason\":\"DAMAGED\"}")) {
            mvc.perform(post("/api/v1/inventory/adjustments").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(operations, org.mockito.Mockito.never()).requestPackageAdjustment(any());
    }
}
