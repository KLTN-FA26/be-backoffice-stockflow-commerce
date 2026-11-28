package com.stockflow.customer.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.customer.internal.controller.dto.SalesOwnerRequests;
import com.stockflow.customer.internal.domain.Lead;
import com.stockflow.customer.internal.service.LeadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Finding S1: once the public form's body is accepted, nothing that happens next may change the answer. An error only
 * one branch can raise (the insert of a new lead, which a customer's tax code never reaches) told a stranger the tax
 * code was not a customer's.
 */
class LeadControllerTest {

    private final LeadService service = mock(LeadService.class);
    private final LeadController controller = new LeadController(service);

    private final SalesOwnerRequests.SubmitLead request = new SalesOwnerRequests.SubmitLead("Cong ty A", "0312345678",
            "Nguyen Van A", null, "0912345678", "a@example.test", null, List.of(Lead.Interest.CUP), null, null, true,
            null);

    @Test
    @DisplayName("S1: a server error after the body was accepted is answered 202 received, like every other outcome")
    void aServerErrorIsAnsweredLikeTheRest() {
        when(service.submit(any(), isNull())).thenThrow(new DataIntegrityViolationException("insert refused"));

        ApiResponse<SalesOwnerRequests.LeadReceipt> answer = controller.submit(request);

        doReturn(LeadService.Outcome.EXISTING_CUSTOMER).when(service).submit(any(), isNull());
        ApiResponse<SalesOwnerRequests.LeadReceipt> customer = controller.submit(request);

        assertThat(answer.data()).isEqualTo(new SalesOwnerRequests.LeadReceipt(true)).isEqualTo(customer.data());
        assertThat(answer.success()).isEqualTo(customer.success());
    }

    @Test
    @DisplayName("S1: a null among the interests is dropped by the form, never carried to the database")
    void aNullInterestIsDropped() {
        Lead.Form form = new Lead.Form("A", null, "B", null, "+84912345678", null, null,
                java.util.Arrays.asList(Lead.Interest.CUP, null, Lead.Interest.CUP), null, null);

        assertThat(form.interests()).containsExactly(Lead.Interest.CUP);
    }
}
