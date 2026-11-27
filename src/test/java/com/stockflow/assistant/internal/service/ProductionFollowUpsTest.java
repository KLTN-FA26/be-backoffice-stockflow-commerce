package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.ProductionOrderQueries;
import com.stockflow.order.api.ProductionOrderQueries.FollowUpReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Production Coordinator's follow-ups and the draft to the producer (contract 3.3). */
class ProductionFollowUpsTest {

    private static final Instant NOW = Instant.parse("2026-11-27T02:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final UUID PO = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final String CUSTOMER_NAME = "Công ty Bao Bì Khách";

    private final ProductionOrderQueries queries = mock(ProductionOrderQueries.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final List<LanguageModel.Request> asked = new ArrayList<>();

    private static CurrentUser user(String... resources) {
        Set<PermissionCode> permissions = new java.util.HashSet<>();
        for (String resource : resources) {
            permissions.add(PermissionCode.of(resource, Action.READ));
        }
        return new CurrentUser(UUID.randomUUID(), "u", Set.of(Role.SYSTEM_ADMIN), permissions, DataScope.ALL,
                Set.of(), Set.of());
    }

    private static ProductionOrderQueries.FollowUp followUp(String status, FollowUpReason reason) {
        ProductionOrderQueries.ProductionOrderRow row = new ProductionOrderQueries.ProductionOrderRow(PO,
                "PRD-20261110-0001", status, UUID.randomUUID(), "SO-20261101-000042", 2, "Xưởng in Việt", 10, "HCM",
                TODAY.minusDays(2), TODAY.plusDays(1), NOW, null, false, 0, null,
                List.of(new ProductionOrderQueries.Line("CUP-12OZ-KH", 40, 50, 15)));
        return new ProductionOrderQueries.FollowUp(row, CUSTOMER, TODAY.minusDays(2), TODAY.plusDays(3), reason,
                reason == FollowUpReason.LATE ? 2 : null);
    }

    private ProductionFollowUps followUps(String reply) {
        LanguageModel model = new LanguageModel() {
            @Override
            public boolean available() {
                return reply != null;
            }

            @Override
            public Reply next(Request request) {
                asked.add(request);
                return new Reply(new Turn.ModelTurn("{}", reply, List.of()), StopReason.END_TURN,
                        Usage.NONE, "test-model");
            }

            @Override
            public Turn.ModelTurn closingTurn(String text) {
                return new Turn.ModelTurn("{}", text, List.of());
            }
        };
        ModelRouter router = new ModelRouter(List.of(new ModelRouter.Candidate("test-model", model)),
                new ModelRoutingProperties(List.of("test-model"), false, null), ZoneOffset.UTC,
                Clock.fixed(NOW, ZoneOffset.UTC));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new ProductionFollowUps(queries, customers, new DraftWriter(router, customers,
                mock(CustomerLegalProfiles.class), mock(IdentityService.class), clock), new ObjectMapper(), clock);
    }

    @Test
    @DisplayName("the list asks for late, at risk and due within 3 days, and names order and customer only to who "
            + "may read orders")
    void listNamesOrdersOnlyForWhoMayReadThem() {
        when(queries.followUps(eq(TODAY), eq(3), eq(null), anyInt())).thenReturn(PageResponse.of(
                List.of(followUp("IN_PRODUCTION", FollowUpReason.LATE)), 0, 50, 1));
        when(customers.labels(anyCollection())).thenReturn(Map.of(CUSTOMER,
                new CustomerLabel(CUSTOMER, CUSTOMER_NAME, "KH01")));

        ProductionFollowUps.FollowUpList sales = followUps("x").list(user(ToolPermissions.PRODUCTION_ORDERS,
                ToolPermissions.SALES_ORDERS));
        ProductionFollowUps.FollowUpList coordinator = followUps("x").list(user(ToolPermissions.PRODUCTION_ORDERS));

        ProductionFollowUps.Item item = sales.items().getFirst();
        assertThat(item.priority()).isEqualTo("HIGH");
        assertThat(item.daysLate()).isEqualTo(2);
        assertThat(item.href()).isEqualTo("/admin/production-orders/" + PO);
        assertThat(item.affects()).singleElement().satisfies(affected -> {
            assertThat(affected.orderNumber()).isEqualTo("SO-20261101-000042");
            assertThat(affected.customerName()).isEqualTo(CUSTOMER_NAME);
            assertThat(affected.batchSeq()).isEqualTo(2);
        });
        assertThat(coordinator.items().getFirst().affects().getFirst().orderNumber()).isNull();
        assertThat(coordinator.items().getFirst().affects().getFirst().customerName()).isNull();
        assertThat(coordinator.total()).isEqualTo(1);
        assertThat(coordinator.truncated()).isFalse();
    }

    @Test
    @DisplayName("the producer's draft is built from the production order alone - no customer, no order number - and "
            + "what it states that no fact holds is flagged")
    void draftKeepsTheCustomerOut() {
        when(queries.followUp(PO, TODAY)).thenReturn(Optional.of(followUp("IN_PRODUCTION", FollowUpReason.LATE)));

        Draft draft = followUps("Kính gửi Xưởng in Việt, lệnh PRD-20261110-0001 đã trễ 2 ngày, "
                + "mới nhận 15 thùng. Mong xưởng giao 25 thùng còn lại trước PRD-20261110-0009.").draft(PO);

        String facts = asked.getFirst().history().getFirst() instanceof Turn.Question question ? question.text() : "";
        assertThat(facts).contains("PRD-20261110-0001", "CUP-12OZ-KH", "Xưởng in Việt")
                .doesNotContain("SO-20261101-000042", CUSTOMER.toString(), CUSTOMER_NAME);
        assertThat(asked.getFirst().tools()).isEmpty();
        assertThat(asked.getFirst().systemPrompt()).contains("Never mention a customer");
        assertThat(draft.unverifiedCodes()).contains("PRD-20261110-0009");
        assertThat(draft.unverifiedCodes()).doesNotContain("PRD-20261110-0001", "15");
        assertThat(draft.model()).isEqualTo("test-model");
        assertThat(draft.sources()).singleElement().satisfies(source -> {
            assertThat(source.kind()).isEqualTo(Draft.Kind.PRODUCTION_ORDER);
            assertThat(source.label()).isEqualTo("PRD-20261110-0001");
        });
        verify(customers, never()).labels(anyCollection());
        verify(customers, never()).findById(any());
    }

    @Test
    @DisplayName("finding 15: the producer's draft is written as every draft is - told the business day, its dates "
            + "as the drafts write them")
    void draftIsToldTheBusinessDay() {
        when(queries.followUp(PO, TODAY)).thenReturn(Optional.of(followUp("IN_PRODUCTION", FollowUpReason.LATE)));

        Draft draft = followUps("Lệnh PRD-20261110-0001 cần trước 25/11/2026.").draft(PO);

        Turn.Question question = (Turn.Question) asked.getFirst().history().getFirst();
        assertThat(question.context()).isEqualTo("Business date: 27/11/2026");
        assertThat(question.text()).contains("\"today\":\"27/11/2026\"", "\"needByDate\":\"25/11/2026\"")
                .doesNotContain("2026-11-25");
        assertThat(draft.unverifiedCodes()).isEmpty();
    }

    @Test
    @DisplayName("no draft for one not with its producer, one not found, or with no model: 409, 404, 503")
    void refusals() {
        when(queries.followUp(PO, TODAY)).thenReturn(Optional.of(followUp("RECEIVED", FollowUpReason.NONE)));
        assertThatThrownBy(() -> followUps("x").draft(PO)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID));

        when(queries.followUp(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> followUps("x").draft(PO)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_FOUND));

        when(queries.followUp(PO, TODAY)).thenReturn(Optional.of(followUp("SENT", FollowUpReason.LATE)));
        assertThatThrownBy(() -> followUps(null).draft(PO)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(asked).isEmpty();
    }
}
