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

/**
 * The contract e-signing plan (2026-11-28) on the aggregate: the customer signs first and the company after, the customer
 * can send a sent contract back as the next revision, the signing deadline, and the Sales Manager's approval of terms that
 * deviate from the standard ones. The database says the same ({@code tg_sales_contract_transition} and the CHECKs of
 * V20270129000100), tried in {@code tools/db/qa/99_contract_content_and_lifecycle.sql}.
 */
class SalesContractEsigningTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final UUID SALES = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID CUSTOMER_USER = UUID.randomUUID();
    private static final ContractParty SELLER = new ContractParty("Seller", "0101", "1 A St", "Ms A", "Director");
    private static final ContractParty BUYER = new ContractParty("Buyer", null, "2 B St", "Mr B", "Owner");
    /** What stockflow.order.contract.* says by default. */
    private static final ContractAgreement.Standard STANDARD = new ContractAgreement.Standard(8, 5, 6);

    private static Order order() {
        OrderLine line = Order.line(UUID.randomUUID(), new Sku("LY-A"), 1000, Money.vnd(2_000), null)
                .withTax(BigDecimal.TEN).withCartons(UUID.randomUUID(), 20, 50);
        return Order.draft(OrderNumber.of(TODAY, 3), UUID.randomUUID(), UUID.randomUUID(), List.of(line), NOW);
    }

    private static SalesContract draft(Order order) {
        List<DeliveryBatch> schedule = ContractTerms.schedule(order, List.of(new ContractTerms.BatchEntry(
                TODAY.plusDays(20), TODAY.plusDays(20),
                List.of(new ContractTerms.LineEntry(order.lines().getFirst().id(), 20)))));
        List<PlannedInstallment> plan = ContractTerms.plan(order, 1, List.of(new ContractTerms.InstallmentEntry(
                "On signing", PaymentMilestone.ON_SIGNING, null, new BigDecimal("100"), null, null)), false, null);
        return SalesContract.draft(UUID.randomUUID(), "HD-9", order, SELLER, BUYER, 2, schedule, plan);
    }

    private static SalesContract sent(Order order) {
        SalesContract contract = draft(order);
        contract.send(order, NOW, SALES, "documents/hd-9.pdf", TODAY.plusDays(7));
        return contract;
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("sending sets the signing deadline; it is overdue the day after, and only while it waits for the customer")
    void signingDeadline() {
        Order order = order();
        SalesContract contract = sent(order);
        assertThat(contract.signBy()).isEqualTo(TODAY.plusDays(7));
        assertThat(contract.revision()).isEqualTo(1);
        assertThat(contract.overdueToSign(TODAY.plusDays(7))).as("the deadline day itself").isFalse();
        assertThat(contract.overdueToSign(TODAY.plusDays(8))).isTrue();

        contract.extendSignBy(TODAY.plusDays(30), TODAY.plusDays(8));
        assertThat(contract.signBy()).isEqualTo(TODAY.plusDays(30));
        assertThat(contract.overdueToSign(TODAY.plusDays(8))).isFalse();
        assertThatThrownBy(() -> contract.extendSignBy(TODAY.plusDays(7), TODAY.plusDays(8)))
                .as("in the past").extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> contract.extendSignBy(TODAY.plusDays(69), TODAY.plusDays(8)))
                .as("more than 60 days ahead").extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        contract.extendSignBy(TODAY.plusDays(68), TODAY.plusDays(8));

        contract.customerSigned(NOW.plusSeconds(60));
        assertThat(contract.overdueToSign(TODAY.plusDays(90))).as("signed by the customer: not overdue").isFalse();
        assertThatThrownBy(() -> contract.extendSignBy(TODAY.plusDays(40), TODAY.plusDays(8)))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThatThrownBy(() -> draft(order).extendSignBy(TODAY.plusDays(2), TODAY))
                .as("a draft has no deadline").extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
    }

    @Test
    @DisplayName("the customer signs first: SENT -> CUSTOMER_SIGNED; the company then makes it SIGNED, once")
    void customerThenCompany() {
        Order order = order();
        SalesContract contract = sent(order);
        contract.customerSigned(NOW.plusSeconds(3600));
        assertThat(contract.status()).isEqualTo(SalesContractStatus.CUSTOMER_SIGNED);
        assertThat(contract.customerSignedAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(contract.signedAt()).as("not in force yet").isNull();
        assertThatThrownBy(() -> contract.customerSigned(NOW.plusSeconds(7200)))
                .as("twice").extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_NOT_SIGNABLE);
        assertThatThrownBy(() -> contract.revise(order, 1, contract.schedule(), contract.plan()))
                .as("locked like SENT").extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThatThrownBy(() -> contract.requestChanges("Please change the delivery windows"))
                .as("not from CUSTOMER_SIGNED").extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);

        contract.companySigned(TODAY, "signed/hd-9.pdf", "hd-9-signed.pdf", NOW.plusSeconds(7200), MANAGER);
        assertThat(contract.status()).isEqualTo(SalesContractStatus.SIGNED);
        assertThat(contract.signedBy()).isEqualTo(MANAGER);
        assertThatThrownBy(() -> contract.companySigned(TODAY, "k", "f.pdf", NOW.plusSeconds(7300), MANAGER))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThatThrownBy(() -> contract.cancel("too late", NOW, SALES)).extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
    }

    @Test
    @DisplayName("the company does not sign what the customer has not: SENT and DRAFT are refused")
    void companyNeedsTheCustomerFirst() {
        Order order = order();
        SalesContract contract = sent(order);
        assertThatThrownBy(() -> contract.companySigned(TODAY, "k", "f.pdf", NOW, MANAGER))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
        assertThatThrownBy(() -> draft(order).customerSigned(NOW)).extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.CONTRACT_NOT_SIGNABLE);
    }

    @Test
    @DisplayName("a customer cannot sign past the deadline until Sales extends it")
    void noSigningAfterTheDeadline() {
        Order order = order();
        SalesContract contract = sent(order);
        Instant late = NOW.plusSeconds(8L * 24 * 3600);
        assertThatThrownBy(() -> contract.customerSigned(late)).extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.CONTRACT_SIGN_DEADLINE_PASSED);
        assertThatThrownBy(() -> contract.requireCustomerSignable(TODAY.plusDays(8)))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_SIGN_DEADLINE_PASSED);
        contract.requireCustomerSignable(TODAY.plusDays(7));
        contract.extendSignBy(TODAY.plusDays(20), TODAY.plusDays(8));
        contract.customerSigned(late);
        assertThat(contract.status()).isEqualTo(SalesContractStatus.CUSTOMER_SIGNED);
    }

    @Test
    @DisplayName("a file signed by both sides is recorded from SENT only: once the customer signed electronically the "
            + "company signs with its own signature (USE_COMPANY_SIGNATURE); a draft refuses")
    void recordedSignedFileFromSentOnly() {
        Order order = order();
        SalesContract fromSent = sent(order);
        fromSent.sign(TODAY, "k", "f.pdf", NOW, SALES);
        assertThat(fromSent.status()).isEqualTo(SalesContractStatus.SIGNED);

        SalesContract customerSigned = sent(order);
        customerSigned.customerSigned(NOW);
        for (Runnable attempt : List.<Runnable>of(customerSigned::requireSignable,
                () -> customerSigned.sign(TODAY, "k", "f.pdf", NOW, SALES))) {
            assertThatThrownBy(attempt::run).isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
                assertThat(ex.details()).containsEntry("reason", SalesContract.USE_COMPANY_SIGNATURE);
            });
        }
        assertThat(customerSigned.status()).isEqualTo(SalesContractStatus.CUSTOMER_SIGNED);
        customerSigned.companySigned(TODAY, "k", "f.pdf", NOW, UUID.randomUUID());
        assertThat(customerSigned.status()).as("the company's signature is the way").isEqualTo(SalesContractStatus.SIGNED);

        assertThatThrownBy(() -> draft(order).sign(TODAY, "k", "f.pdf", NOW, SALES))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
    }

    @Test
    @DisplayName("cancelled from DRAFT, SENT and CUSTOMER_SIGNED, never once SIGNED")
    void cancelBeforeTheCompanySigns() {
        Order order = order();
        SalesContract customerSigned = sent(order);
        customerSigned.customerSigned(NOW);
        customerSigned.cancel("The customer withdrew", NOW, SALES);
        assertThat(customerSigned.status()).isEqualTo(SalesContractStatus.CANCELLED);
        assertThat(customerSigned.status().live()).isFalse();
    }

    @Test
    @DisplayName("the customer's change request: SENT -> DRAFT as revision 2, the sent PDF handed back for the revision row")
    void changeRequest() {
        Order order = order();
        SalesContract contract = sent(order);
        assertThatThrownBy(() -> contract.requestChanges("too short"))
                .as("10 characters at least").extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> contract.requestChanges("x".repeat(2001))).extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        SentRevision ended = contract.requestChanges("  Please split the goods in two deliveries.  ");
        assertThat(ended.revision()).isEqualTo(1);
        assertThat(ended.documentKey()).isEqualTo("documents/hd-9.pdf");
        assertThat(ended.sentAt()).isEqualTo(NOW);
        assertThat(ended.sentBy()).isEqualTo(SALES);
        assertThat(contract.status()).isEqualTo(SalesContractStatus.DRAFT);
        assertThat(contract.revision()).isEqualTo(2);
        assertThat(contract.sentAt()).isNull();
        assertThat(contract.sentDocumentKey()).isNull();
        assertThat(contract.signBy()).isNull();

        // Editable again, and sent as revision 2 with a deadline of its own.
        contract.revise(order, 3, contract.schedule(), contract.plan());
        contract.send(order, NOW.plusSeconds(3600), SALES, "documents/hd-9-v2.pdf", TODAY.plusDays(7));
        assertThat(contract.sentRevision()).isEqualTo(new SentRevision(2, "documents/hd-9-v2.pdf",
                NOW.plusSeconds(3600), SALES));
        assertThatThrownBy(() -> draft(order).requestChanges("Please split the goods in two deliveries"))
                .as("a draft was never sent").extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.SALES_CONTRACT_STATUS_INVALID);
    }

    @Test
    @DisplayName("the account that drew it up, or sent it, may not sign for the company")
    void fourEyes() {
        Order order = order();
        SalesContract contract = draft(order);
        contract.drawnUpBy(SALES);
        assertThatThrownBy(() -> contract.drawnUpBy(MANAGER)).isInstanceOf(IllegalStateException.class);
        contract.send(order, NOW, MANAGER, "k", null);
        assertThatThrownBy(() -> contract.requireNotAuthor(SALES)).extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.CONTRACT_SIGNER_IS_AUTHOR);
        assertThatThrownBy(() -> contract.requireNotAuthor(MANAGER)).extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.CONTRACT_SIGNER_IS_AUTHOR);
        contract.requireNotAuthor(UUID.randomUUID());
    }

    // ---- terms review ---------------------------------------------------------------------------------------------------

    private static ContractAgreement special() {
        return new ContractAgreement("Free replacement of damaged cartons within 30 days.", null, null, null, null, null);
    }

    @Test
    @DisplayName("a standard contract needs no approval: asking for one is refused, sending is not")
    void standardNeedsNoReview() {
        Order order = order();
        SalesContract contract = draft(order);
        // The configured terms written down are the standard ones.
        contract.reviseAgreement(order, new ContractAgreement(null, 8, 5, 6, null, null), STANDARD);
        assertThat(contract.nonStandard()).isFalse();
        assertThatThrownBy(() -> contract.submitForTermsReview(order, NOW, SALES))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_NOT_NEEDED);
        contract.send(order, NOW, SALES, "k", TODAY.plusDays(7));
        assertThat(contract.termsReview()).isEqualTo(TermsReview.NONE);
    }

    @Test
    @DisplayName("special terms, or an override that differs from the default, make it non-standard")
    void whatIsNonStandard() {
        assertThat(ContractAgreement.NONE.deviates(STANDARD)).isFalse();
        assertThat(special().deviates(STANDARD)).isTrue();
        assertThat(new ContractAgreement(null, 5, null, null, null, null).deviates(STANDARD)).as("penalty 5 < 8").isTrue();
        assertThat(new ContractAgreement(null, null, 10, null, null, null).deviates(STANDARD)).isTrue();
        assertThat(new ContractAgreement(null, null, null, 12, null, null).deviates(STANDARD)).isTrue();
        assertThat(new ContractAgreement("  ", null, null, null, null, null).deviates(STANDARD))
                .as("blank special terms are no special terms").isFalse();
        assertThat(new ContractAgreement(null, null, null, null,
                new ContractAgreement.SellerSigner("Ms C", "Deputy director", "12/UQ-2026"),
                new ContractAgreement.BuyerSigner("Mr D", null)).deviates(STANDARD))
                .as("other signers are not other terms").isFalse();
    }

    @Test
    @DisplayName("the caps: penalty 0..8, tolerance 0..10, warranty 0..36, special terms 4000, a title needs a name")
    void agreementCaps() {
        assertThatThrownBy(() -> new ContractAgreement(null, 9, null, null, null, null))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new ContractAgreement(null, -1, null, null, null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ContractAgreement(null, null, 11, null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ContractAgreement(null, null, null, 37, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ContractAgreement("x".repeat(4001), null, null, null, null, null))
                .isInstanceOf(BusinessException.class);
        new ContractAgreement("x".repeat(4000), 0, 0, 0, null, null);
        assertThatThrownBy(() -> new ContractAgreement.SellerSigner(null, "Director", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ContractAgreement.SellerSigner("Ms C", null, "x".repeat(101)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new ContractAgreement.BuyerSigner(" ", "Director"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("QĐ-4: a non-standard draft is not sent until a manager approves it; it is locked while it waits")
    void termsReviewHappyPath() {
        Order order = order();
        SalesContract contract = draft(order);
        contract.reviseAgreement(order, special(), STANDARD);
        assertThat(contract.nonStandard()).isTrue();
        assertThatThrownBy(() -> contract.send(order, NOW, SALES, "k", TODAY.plusDays(7)))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_REQUIRED);

        contract.submitForTermsReview(order, NOW, SALES);
        assertThat(contract.termsReview()).isEqualTo(TermsReview.PENDING);
        assertThat(contract.termsReviewRequestedBy()).isEqualTo(SALES);
        assertThatThrownBy(() -> contract.submitForTermsReview(order, NOW, SALES))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_PENDING);
        assertThatThrownBy(() -> contract.send(order, NOW, SALES, "k", TODAY.plusDays(7)))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_PENDING);
        assertThatThrownBy(() -> contract.revise(order, 1, contract.schedule(), contract.plan()))
                .as("locked").extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_PENDING);
        assertThatThrownBy(() -> contract.reviseAgreement(order, ContractAgreement.NONE, STANDARD))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_PENDING);

        contract.approveTerms("  Agreed with the director  ", NOW.plusSeconds(60), MANAGER);
        assertThat(contract.termsReview()).isEqualTo(TermsReview.APPROVED);
        assertThat(contract.termsReviewNote()).isEqualTo("Agreed with the director");
        assertThat(contract.termsReviewedBy()).isEqualTo(MANAGER);
        contract.submitForTermsReview(order, NOW, SALES);
        assertThat(contract.termsReview()).as("asking again once approved changes nothing")
                .isEqualTo(TermsReview.APPROVED);

        contract.send(order, NOW.plusSeconds(120), SALES, "k", TODAY.plusDays(7));
        assertThat(contract.status()).isEqualTo(SalesContractStatus.SENT);
        assertThat(contract.termsReview()).isEqualTo(TermsReview.APPROVED);
    }

    @Test
    @DisplayName("editing an approved draft takes the approval back; it has to be approved again")
    void editingResetsTheApproval() {
        Order order = order();
        SalesContract contract = draft(order);
        contract.reviseAgreement(order, special(), STANDARD);
        contract.submitForTermsReview(order, NOW, SALES);
        contract.approveTerms(null, NOW, MANAGER);
        assertThat(contract.termsReviewNote()).isNull();

        contract.revise(order, 5, contract.schedule(), contract.plan());
        assertThat(contract.termsReview()).isEqualTo(TermsReview.NONE);
        assertThat(contract.termsReviewedBy()).isNull();
        assertThatThrownBy(() -> contract.send(order, NOW, SALES, "k", TODAY.plusDays(7)))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_REQUIRED);

        // Back to standard: nothing to approve any more.
        contract.reviseAgreement(order, ContractAgreement.NONE, STANDARD);
        contract.send(order, NOW, SALES, "k", TODAY.plusDays(7));
        assertThat(contract.status()).isEqualTo(SalesContractStatus.SENT);
    }

    @Test
    @DisplayName("a rejection needs a reason, hands the draft back, and a change after it is asked for again")
    void rejectionAndWithdrawal() {
        Order order = order();
        SalesContract contract = draft(order);
        contract.reviseAgreement(order, special(), STANDARD);
        assertThatThrownBy(() -> contract.approveTerms("ok", NOW, MANAGER))
                .as("nothing waits").extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_NOT_PENDING);
        assertThatThrownBy(contract::withdrawTermsReview).extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_NOT_PENDING);

        contract.submitForTermsReview(order, NOW, SALES);
        assertThatThrownBy(() -> contract.rejectTerms("  ", NOW, MANAGER)).extracting(SalesContractEsigningTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> contract.rejectTerms("x".repeat(501), NOW, MANAGER))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        contract.rejectTerms("The warranty clause is not ours to give", NOW, MANAGER);
        assertThat(contract.termsReview()).isEqualTo(TermsReview.REJECTED);
        assertThat(contract.termsReviewNote()).isEqualTo("The warranty clause is not ours to give");
        assertThatThrownBy(() -> contract.send(order, NOW, SALES, "k", TODAY.plusDays(7)))
                .extracting(SalesContractEsigningTest::code).isEqualTo(ErrorCode.CONTRACT_TERMS_REVIEW_REQUIRED);

        // Sales may send it for review again as it is, or change it first (the edit clears the refusal).
        contract.submitForTermsReview(order, NOW, SALES);
        assertThat(contract.termsReview()).isEqualTo(TermsReview.PENDING);
        assertThat(contract.termsReviewNote()).as("the old refusal is gone").isNull();
        contract.withdrawTermsReview();
        assertThat(contract.termsReview()).isEqualTo(TermsReview.NONE);
        assertThat(contract.termsReviewRequestedBy()).isNull();
    }

    @Test
    @DisplayName("cancelled while it waits for a manager, it waits for nothing")
    void cancelledWhileWaiting() {
        Order order = order();
        SalesContract contract = draft(order);
        contract.reviseAgreement(order, special(), STANDARD);
        contract.submitForTermsReview(order, NOW, SALES);
        contract.cancel("The customer went elsewhere", NOW, SALES);
        assertThat(contract.status()).isEqualTo(SalesContractStatus.CANCELLED);
        assertThat(contract.termsReview()).isEqualTo(TermsReview.NONE);
    }

    @Test
    @DisplayName("a contract that was sent and comes back as a draft keeps its approval until it is edited")
    void changeRequestKeepsTheApproval() {
        Order order = order();
        SalesContract contract = draft(order);
        contract.reviseAgreement(order, special(), STANDARD);
        contract.submitForTermsReview(order, NOW, SALES);
        contract.approveTerms(null, NOW, MANAGER);
        contract.send(order, NOW, SALES, "k", TODAY.plusDays(7));
        contract.requestChanges("Please add a delivery window in January");
        assertThat(contract.termsReview()).isEqualTo(TermsReview.APPROVED);
        // Unchanged, it may go out again as approved; edited, it needs the manager again.
        contract.revise(order, 4, contract.schedule(), contract.plan());
        assertThat(contract.termsReview()).isEqualTo(TermsReview.NONE);
    }
}
