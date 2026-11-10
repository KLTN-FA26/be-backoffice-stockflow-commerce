package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 4B-4 (kltn-docs 14 §4.3 steps 11-16, §5.2, BR-14): a sales contract's life. */
class SalesContractTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final UUID SALES = UUID.randomUUID();
    private static final ContractParty SELLER = new ContractParty("Seller", "0101", "1 A St", "Ms A", "Director");
    private static final ContractParty BUYER = new ContractParty("Buyer", null, "2 B St", "Mr B", "Owner");

    private static Order order(boolean cartons) {
        OrderLine line = Order.line(UUID.randomUUID(), new Sku("LY-A"), 1000, Money.vnd(2_000), null)
                .withTax(BigDecimal.TEN);
        if (cartons) {
            line = line.withCartons(UUID.randomUUID(), 20, 50);
        }
        return Order.draft(OrderNumber.of(TODAY, 3), UUID.randomUUID(), UUID.randomUUID(), List.of(line), NOW);
    }

    private static SalesContract contract(Order order, LocalDate window) {
        List<DeliveryBatch> schedule = ContractTerms.schedule(order, List.of(new ContractTerms.BatchEntry(window,
                window, List.of(new ContractTerms.LineEntry(order.lines().getFirst().id(), 20)))));
        List<PlannedInstallment> plan = ContractTerms.plan(order, 1, List.of(new ContractTerms.InstallmentEntry(
                "On signing", PaymentMilestone.ON_SIGNING, null, new BigDecimal("100"), null, null)), false, null);
        return SalesContract.draft(UUID.randomUUID(), "HD-1", order, SELLER, BUYER, 2, schedule, plan);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("drawn up for a draft order of cartons only")
    void forADraftOrderOfCartons() {
        assertThatThrownBy(() -> SalesContract.requireContractable(order(false)))
                .extracting(SalesContractTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_ORDER_NOT_CARTONS);
        Order placed = order(true);
        placed.lines().forEach(line -> placed.attachReservations(line.id(), List.of(UUID.randomUUID())));
        placed.submit();
        assertThatThrownBy(() -> SalesContract.requireContractable(placed))
                .extracting(SalesContractTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_ORDER_NOT_DRAFT);
        SalesContract contract = contract(order(true), TODAY.plusDays(20));
        assertThat(contract.status()).isEqualTo(SalesContractStatus.DRAFT);
        assertThat(contract.freeSampleRounds()).isEqualTo(2);
    }

    @Test
    @DisplayName("draft → sent → signed; locked once sent; a signed contract is never cancelled (BR-14)")
    void sentAndSigned() {
        Order order = order(true);
        SalesContract contract = contract(order, TODAY.plusDays(20));
        contract.revise(order, 3, contract.schedule(), contract.plan());
        assertThat(contract.freeSampleRounds()).isEqualTo(3);

        contract.send(order, NOW, SALES, "documents/hd-1.pdf");
        assertThat(contract.status()).isEqualTo(SalesContractStatus.SENT);
        assertThat(contract.sentDocumentKey()).isEqualTo("documents/hd-1.pdf");
        assertThatThrownBy(() -> contract.revise(order, 1, contract.schedule(), contract.plan()))
                .extracting(SalesContractTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThatThrownBy(() -> contract.send(order, NOW, SALES, "x"))
                .extracting(SalesContractTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);

        assertThatThrownBy(() -> contract.sign(TODAY.plusDays(1), "k", "signed.pdf", NOW, SALES))
                .as("signed tomorrow").extracting(SalesContractTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> contract.sign(TODAY.minusDays(1), "k", "signed.pdf", NOW, SALES))
                .as("signed before it was sent").extracting(SalesContractTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        contract.sign(TODAY, "documents/signed.pdf", "signed.pdf", NOW, SALES);
        assertThat(contract.status()).isEqualTo(SalesContractStatus.SIGNED);
        assertThat(contract.signedOn()).isEqualTo(TODAY);
        assertThatThrownBy(() -> contract.cancel("changed our mind", NOW, SALES))
                .extracting(SalesContractTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
    }

    @Test
    @DisplayName("sent with every window known and none in the past")
    void notSentWithoutItsWindows() {
        Order order = order(true);
        assertThatThrownBy(() -> contract(order, null).send(order, NOW, SALES, "x"))
                .extracting(SalesContractTest::code).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
        assertThatThrownBy(() -> contract(order, TODAY.minusDays(1)).send(order, NOW, SALES, "x"))
                .extracting(SalesContractTest::code).isEqualTo(ErrorCode.DELIVERY_SCHEDULE_INVALID);
    }

    @Test
    @DisplayName("cancelled before it is signed, with a reason; a new one may then be drawn up")
    void cancelled() {
        Order order = order(true);
        SalesContract draft = contract(order, TODAY.plusDays(20));
        assertThatThrownBy(() -> draft.cancel(" ", NOW, SALES))
                .extracting(SalesContractTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        draft.cancel("The customer wants two batches", NOW, SALES);
        assertThat(draft.status()).isEqualTo(SalesContractStatus.CANCELLED);
        assertThat(draft.status().live()).isFalse();

        SalesContract sent = contract(order, TODAY.plusDays(20));
        sent.send(order, NOW, SALES, "x");
        sent.cancel("Refused by the customer", NOW, SALES);
        assertThat(sent.cancellationReason()).isEqualTo("Refused by the customer");
    }
}
