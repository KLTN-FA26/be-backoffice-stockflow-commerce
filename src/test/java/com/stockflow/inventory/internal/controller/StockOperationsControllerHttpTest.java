package com.stockflow.inventory.internal.controller;

import com.stockflow.common.security.AuthenticatedUserArgumentResolver;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.internal.service.StockOperations;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP edge of stock operations: the SKU alphabet is the one the variants use, not a private copy. */
class StockOperationsControllerHttpTest {

    private final StockOperations operations = mock(StockOperations.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final MockMvc mvc;

    StockOperationsControllerHttpTest() {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "keeper",
                Set.of(), Set.of(), DataScope.ALL, Set.of())));
        mvc = MockMvcBuilders.standaloneSetup(new StockOperationsController(operations,
                        new StockOperationsWebMapperImpl()))
                .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver(users)).build();
    }

    private static String move(String sku) {
        return """
                {"requestId":"%s","sku":"%s","fromLocation":"A","toLocation":"B","quantity":1}
                """.formatted(UUID.randomUUID(), sku);
    }

    /** Variant SKUs may carry an underscore (product codes do); the old private pattern refused them. */
    @Test
    void aSkuWithAnUnderscoreIsAccepted() throws Exception {
        when(operations.move(any())).thenReturn(new StockMove(UUID.randomUUID(), "CUP_PET", null, "A", "B", 1,
                Instant.now()));

        mvc.perform(post("/api/v1/inventory/movements").contentType("application/json").content(move("CUP_PET")))
                .andExpect(status().isCreated());

        ArgumentCaptor<MoveStockCommand> sent = ArgumentCaptor.forClass(MoveStockCommand.class);
        verify(operations).move(sent.capture());
        assertThat(sent.getValue().sku().code()).isEqualTo("CUP_PET");
    }

    @Test
    void aMalformedSkuIsStillRefused() throws Exception {
        mvc.perform(post("/api/v1/inventory/movements").contentType("application/json").content(move("-bad")))
                .andExpect(status().isBadRequest());
    }
}
