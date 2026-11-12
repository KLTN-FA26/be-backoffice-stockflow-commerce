package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceSending;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import com.stockflow.order.api.ReturnRequestLookup;
import com.stockflow.order.api.ReturnRequestReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The accountant corrects an issued invoice (WF-05 E4; TT 91/2026/TT-BTC Điều 10 as read in plans/E0-legal.md, rows 2
 * and 3 and khoản 6): {@link EInvoiceCorrections#adjust} and {@link EInvoiceCorrections#replace} draw the correction
 * up, and {@link EInvoiceIssuanceSteps} is where it is held back for the agreement and where the invoice it corrects
 * takes its mark. The repository is mocked: the subject is what the service asks of the invoice under its lock and what
 * it then saves. The same rules as triggers of {@code invoicing.einvoice}, and the endpoints, are pinned against the
 * database in {@code EInvoiceCorrectionEndpointsIntegrationTest}; goods returned in
 * {@link EInvoiceCorrectionsReturnTest}.
 *
 * <p>The invoice corrected is {@link EInvoiceFixtures}': 20 cartons of 50 at 100,000 a carton taxed 10%, and a plate
 * fee of 500,000 taxed 8% - 2,740,000 with tax.</p>
 */
class EInvoiceCorrectionsTest {

    /** 10:00 on Friday 13 November 2026 in Vietnam: the day the corrections are drawn up. */
    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final String REASON = "sai so luong";

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final ReturnRequestLookup returns = mock(ReturnRequestLookup.class);
    private final EInvoiceCorrections corrections = new EInvoiceCorrections(invoices, returns,
            mock(EInvoiceAgreements.class), mock(FileTransfers.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private final InvoiceSettings settings = mock(InvoiceSettings.class);
    private final EInvoiceProviders providers = mock(EInvoiceProviders.class);
    private final EInvoiceProvider provider = mock(EInvoiceProvider.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final EInvoiceIssuanceSteps steps = new EInvoiceIssuanceSteps(invoices, mock(EInvoiceSending.class),
            settings, providers, files, mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void aRepositoryThatKeepsWhatItIsGiven() {
        when(invoices.nextCode(any())).thenReturn("EINV-20261113-0001");
        when(invoices.save(any())).thenAnswer(call -> call.getArgument(0));
        when(invoices.correctionsOf(any())).thenReturn(EInvoice.Corrections.NONE);
        when(invoices.piecesTakenOff(any())).thenAnswer(call -> new HashMap<UUID, Long>());
        when(invoices.findEarlierUnissued(any(), any())).thenReturn(Optional.empty());
    }

    @BeforeEach
    void aProviderThatIssuesCorrections() {
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("SANDBOX", "TEST", null, null, false, false, 0));
        when(settings.seriesInUse("SANDBOX", 2026)).thenReturn(Optional.of(EInvoiceFixtures.SERIES));
        when(providers.require("SANDBOX")).thenReturn(provider);
        when(provider.issuesCorrections()).thenReturn(true);
        when(files.store(eq(FileCategory.EINVOICE), any(FileUpload.class))).thenAnswer(call -> {
            FileUpload upload = call.getArgument(1);
            return new StoredFile("einvoices/2026/11/13/" + upload.originalName(), upload.originalName(),
                    upload.contentType(), upload.sizeBytes(), NOW);
        });
    }

    /** An invoice the repository hands out, under its lock and without. */
    private EInvoice held(EInvoice invoice) {
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    private EInvoice issuedOriginal() {
        return held(EInvoiceFixtures.in(EInvoiceStatus.ISSUED));
    }

    /** An error's adjustment of {@code cartons} cartons of the invoice's goods line, at the line's own price. */
    private EInvoiceCorrections.Adjust byCartons(EInvoice original, int cartons) {
        return new EInvoiceCorrections.Adjust(original.id(), CorrectionBasis.ERROR, REASON, null,
                List.of(change(original, cartons)), null, null);
    }

    /** The invoice as it should have been: {@code cartons} cartons on its goods line, the fee as it was. */
    private EInvoiceCorrections.Replace stating(EInvoice original, int cartons) {
        return new EInvoiceCorrections.Replace(original.id(), REASON, null, List.of(change(original, cartons)));
    }

    private static EInvoiceCorrections.LineChange change(EInvoice original, int cartons) {
        return new EInvoiceCorrections.LineChange(original.lines().getFirst().id(), cartons, null, null, null);
    }

    private static void assertRefused(ThrowingCallable call, ErrorCode expected) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(expected);
    }

    /** The provider's answer to a correction sent under {@code number}. */
    private void issue(EInvoice correction, int number) {
        steps.issued(correction.id(), correction.transactionId(), new EInvoiceProvider.Result(
                        EInvoiceProvider.ResultState.ISSUED, number, "SBX" + number, "SBXL" + number, NOW, null, null),
                new EInvoiceProvider.Documents("<HDon/>".getBytes(StandardCharsets.UTF_8),
                        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)));
    }

    // ---- drawing up ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an adjustment is a draft of signed lines that names the invoice it adjusts - which stays as it was")
    void anAdjustmentIsADraftOfSignedLinesNamingTheOriginal() {
        EInvoice original = issuedOriginal();
        EInvoice.Snapshot before = original.snapshot();

        EInvoice.Snapshot adjustment = corrections.adjust(byCartons(original, -2));

        assertThat(adjustment.type()).isEqualTo(EInvoiceType.ADJUSTMENT);
        assertThat(adjustment.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(adjustment.originalEInvoiceId()).isEqualTo(original.id());
        assertThat(adjustment.correctionBasis()).isEqualTo(CorrectionBasis.ERROR);
        assertThat(adjustment.adjustmentReason()).isEqualTo(REASON);
        assertThat(adjustment.issueDate()).as("dated the day it is drawn up, not the delivery's").isEqualTo(TODAY);
        assertThat(adjustment.invoiceNumber()).as("numbered by the provider, when it is issued").isNull();
        // Khoản 6 điểm c: a reduction is written below zero, and so are its money and its tax.
        assertThat(adjustment.lines()).hasSize(1);
        EInvoiceLine line = adjustment.lines().getFirst();
        assertThat(line.quantity()).isEqualTo(-2);
        assertThat(line.pieces()).isEqualTo(-100L);
        assertThat(line.unitPrice()).isEqualByComparingTo("100000");
        assertThat(line.amount()).isEqualByComparingTo("-200000");
        assertThat(line.taxAmount()).isEqualByComparingTo("-20000");
        assertThat(line.orderLineId()).isEqualTo(original.lines().getFirst().orderLineId());
        assertThat(adjustment.subtotal()).isEqualByComparingTo("-200000");
        assertThat(adjustment.taxTotal()).isEqualByComparingTo("-20000");
        assertThat(adjustment.grandTotal()).isEqualByComparingTo("-220000");
        // Report WF05-E4 "for E5": a correction is of the same order, shipment and customer as what it corrects.
        assertThat(adjustment.orderId()).isEqualTo(before.orderId());
        assertThat(adjustment.shipmentId()).isEqualTo(before.shipmentId());
        assertThat(adjustment.customerId()).isEqualTo(before.customerId());
        assertThat(adjustment.buyer()).isEqualTo(before.buyer());

        assertThat(original.snapshot()).as("drawing a draft up changes nothing on the issued invoice").isEqualTo(before);
        verify(invoices, never()).save(original);
        // Read under its lock: the other corrections are looked at only once nobody else can add one.
        verify(invoices).findByIdForUpdate(original.id());
        verify(invoices, never()).findById(any());
    }

    @Test
    @DisplayName("a replacement is a draft of the whole invoice as it should have been; the original stays in force "
            + "until the replacement is issued")
    void aReplacementStatesTheWholeInvoice() {
        EInvoice original = issuedOriginal();
        EInvoice.Snapshot before = original.snapshot();

        EInvoice.Snapshot replacement = corrections.replace(stating(original, 18));

        assertThat(replacement.type()).isEqualTo(EInvoiceType.REPLACEMENT);
        assertThat(replacement.status()).isEqualTo(EInvoiceStatus.DRAFT);
        assertThat(replacement.originalEInvoiceId()).isEqualTo(original.id());
        assertThat(replacement.correctionBasis()).as("a replacement is always of an error").isEqualTo(CorrectionBasis.ERROR);
        assertThat(replacement.issueDate()).isEqualTo(TODAY);
        assertThat(replacement.lines()).as("the corrected line, and the line nobody touched").hasSize(2);
        assertThat(replacement.lines()).extracting(EInvoiceLine::quantity).containsExactly(18, 1);
        assertThat(replacement.lines()).extracting(EInvoiceLine::id)
                .as("its own lines, not the original's").doesNotContainAnyElementsOf(
                        before.lines().stream().map(EInvoiceLine::id).toList());
        // 18 x 100,000 + 10%, and the fee of 500,000 + 8%.
        assertThat(replacement.subtotal()).isEqualByComparingTo("2300000");
        assertThat(replacement.taxTotal()).isEqualByComparingTo("220000");
        assertThat(replacement.grandTotal()).isEqualByComparingTo("2520000");

        assertThat(original.snapshot()).as("REPLACED only once the replacement is issued").isEqualTo(before);
        verify(invoices, never()).save(original);
    }

    // ---- the agreement (khoản 1 điểm b) ----------------------------------------------------------------------------

    @Test
    @DisplayName("E0 rows 2-3: the correction of an error is not sent without the written agreement with the buyer - "
            + "EINVOICE_AGREEMENT_REQUIRED, and it stays a draft; with it, it goes out")
    void aCorrectionOfAnErrorWaitsForTheAgreement() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice adjustment = held(EInvoiceFixtures.adjustment(original, CorrectionBasis.ERROR, -2));
        EInvoice replacement = held(EInvoiceFixtures.replacement(EInvoiceFixtures.in(EInvoiceStatus.ISSUED), 18));

        for (EInvoice correction : List.of(adjustment, replacement)) {
            assertRefused(() -> steps.submit(correction.id(), null, null), ErrorCode.EINVOICE_AGREEMENT_REQUIRED);
            assertThat(correction.status()).isEqualTo(EInvoiceStatus.DRAFT);
            assertThat(correction.transactionId()).as("nothing the provider could know it by").isNull();
        }
        verify(invoices, never()).save(any());

        EInvoiceFixtures.agreed(adjustment);
        assertThat(steps.submit(adjustment.id(), null, null).status()).isEqualTo(EInvoiceStatus.SUBMITTING);
    }

    @Test
    @DisplayName("E0 row 4: an adjustment for goods returned needs no agreement - the return is the record")
    void aReturnAdjustmentNeedsNoAgreement() {
        EInvoice adjustment = held(EInvoiceFixtures.adjustment(EInvoiceFixtures.in(EInvoiceStatus.ISSUED),
                CorrectionBasis.RETURN, -2));

        assertThat(steps.submit(adjustment.id(), null, null).status()).isEqualTo(EInvoiceStatus.SUBMITTING);
    }

    // ---- the form is kept (khoản 6 điểm a) -------------------------------------------------------------------------

    @Test
    @DisplayName("an invoice with a live replacement is not adjusted, one with a live adjustment is not replaced, and "
            + "one being replaced is not replaced twice - EINVOICE_CORRECTION_FORM_FIXED")
    void oneFormOfCorrectionAtATime() {
        EInvoice beingReplaced = issuedOriginal();
        when(invoices.correctionsOf(beingReplaced.id()))
                .thenReturn(new EInvoice.Corrections(false, true, BigDecimal.ZERO));
        EInvoice beingAdjusted = issuedOriginal();
        when(invoices.correctionsOf(beingAdjusted.id()))
                .thenReturn(new EInvoice.Corrections(true, false, new BigDecimal("-220000")));

        assertRefused(() -> corrections.adjust(byCartons(beingReplaced, -2)), ErrorCode.EINVOICE_CORRECTION_FORM_FIXED);
        assertRefused(() -> corrections.replace(stating(beingReplaced, 18)), ErrorCode.EINVOICE_CORRECTION_FORM_FIXED);
        assertRefused(() -> corrections.replace(stating(beingAdjusted, 18)), ErrorCode.EINVOICE_CORRECTION_FORM_FIXED);
        verify(invoices, never()).save(any());

        // The form first used goes on being used: adjusted once, adjusted again.
        assertThat(corrections.adjust(byCartons(beingAdjusted, -1)).type()).isEqualTo(EInvoiceType.ADJUSTMENT);
    }

    @Test
    @DisplayName("the form first used is kept once issued: an adjusted invoice is never replaced, and an invoice that "
            + "replaced another has its errors replaced again, never adjusted")
    void theFormFirstUsedIsKept() {
        // Adjusted, the adjustment issued: nothing live is needed for the invoice to remember it.
        EInvoice adjusted = issuedOriginal();
        adjusted.markAdjusted();
        // A replacement in force.
        EInvoice replaced = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice replacement = held(EInvoiceFixtures.issued(EInvoiceFixtures.agreed(
                EInvoiceFixtures.replacement(replaced, 18)), 2));

        assertRefused(() -> corrections.replace(stating(adjusted, 18)), ErrorCode.EINVOICE_CORRECTION_FORM_FIXED);
        assertRefused(() -> corrections.adjust(byCartons(replacement, -2)), ErrorCode.EINVOICE_CORRECTION_FORM_FIXED);
        verify(invoices, never()).save(any());

        EInvoice.Snapshot again = corrections.replace(stating(replacement, 17));
        assertThat(again.type()).isEqualTo(EInvoiceType.REPLACEMENT);
        assertThat(again.originalEInvoiceId()).as("it replaces the invoice in force").isEqualTo(replacement.id());
    }

    // ---- what can be corrected -----------------------------------------------------------------------------------

    @Test
    @DisplayName("only an invoice in force is corrected: a draft, one at the provider, a rejected, a voided and a "
            + "replaced one are refused - EINVOICE_NOT_IN_FORCE")
    void onlyAnInvoiceInForceIsCorrected() {
        for (EInvoiceStatus status : EInvoiceStatus.values()) {
            if (status == EInvoiceStatus.ISSUED) {
                continue;
            }
            EInvoice notInForce = held(EInvoiceFixtures.in(status));

            assertRefused(() -> corrections.adjust(byCartons(notInForce, -2)), ErrorCode.EINVOICE_NOT_IN_FORCE);
            assertRefused(() -> corrections.replace(stating(notInForce, 18)), ErrorCode.EINVOICE_NOT_IN_FORCE);
        }
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("an invoice is never adjusted below nothing: its total with the adjustments it already has is the "
            + "bound - EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE")
    void anAdjustmentNeverTakesMoreThanTheInvoiceHolds() {
        EInvoice original = issuedOriginal();
        // 2,740,000 with tax. 24 cartons off is 2,640,000: still within it. 25 is 2,750,000.
        assertThat(corrections.adjust(byCartons(original, -24)).grandTotal()).isEqualByComparingTo("-2640000");
        assertRefused(() -> corrections.adjust(byCartons(original, -25)),
                ErrorCode.EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE);

        // Already adjusted by -2,200,000 (20 cartons), a draft or issued: 540,000 is left, less than 5 cartons.
        when(invoices.correctionsOf(original.id()))
                .thenReturn(new EInvoice.Corrections(true, false, new BigDecimal("-2200000")));
        assertThat(corrections.adjust(byCartons(original, -4)).grandTotal()).isEqualByComparingTo("-440000");
        assertRefused(() -> corrections.adjust(byCartons(original, -5)),
                ErrorCode.EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE);
    }

    @Test
    @DisplayName("goods returned are never more than the invoice still holds of their order line, whatever its money "
            + "would allow - EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE")
    void aReturnNeverTakesMorePiecesThanTheLineHolds() {
        EInvoice original = issuedOriginal();
        UUID returned = UUID.randomUUID();
        when(returns.find(returned)).thenReturn(Optional.of(new ReturnRequestReference(returned, "RMA-20261113-0001",
                original.orderId(), "SO-20261112-0001", original.customerId(), "RESOLVED", false)));

        // 21 cartons of the 20 invoiced: 2,310,000, which the invoice's 2,740,000 would cover - its goods do not.
        assertRefused(() -> corrections.adjust(new EInvoiceCorrections.Adjust(original.id(), CorrectionBasis.RETURN,
                "hang tra lai", null, List.of(change(original, -21)), returned, null)),
                ErrorCode.EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE);

        EInvoice.Snapshot all = corrections.adjust(new EInvoiceCorrections.Adjust(original.id(), CorrectionBasis.RETURN,
                "hang tra lai", null, List.of(change(original, -20)), returned, null));
        assertThat(all.returnRequestId()).isEqualTo(returned);
        assertThat(all.lines().getFirst().pieces()).isEqualTo(-1000L);
    }

    // ---- issuing a correction marks what it corrects -------------------------------------------------------------

    @Test
    @DisplayName("issuing an adjustment flags the invoice it adjusts, which stays ISSUED; the agreement's file is "
            + "locked with the invoice's own")
    void issuingAnAdjustmentFlagsTheOriginal() {
        EInvoice original = issuedOriginal();
        EInvoice adjustment = held(EInvoiceFixtures.agreed(
                EInvoiceFixtures.adjustment(original, CorrectionBasis.ERROR, -2)));
        adjustment.submit(EInvoiceFixtures.SERIES, null, NOW);

        issue(adjustment, 2);

        assertThat(adjustment.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(original.status()).as("an adjusted invoice stays in force").isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(original.adjusted()).isTrue();
        verify(invoices).save(original);
        verify(files).retainNow(adjustment.agreementFileKey(), RecordRetention.EINVOICE);
    }

    @Test
    @DisplayName("issuing a replacement makes the invoice it replaces REPLACED, in the same step")
    void issuingAReplacementReplacesTheOriginal() {
        EInvoice original = issuedOriginal();
        EInvoice replacement = held(EInvoiceFixtures.agreed(EInvoiceFixtures.replacement(original, 18)));
        replacement.submit(EInvoiceFixtures.SERIES, null, NOW);

        issue(replacement, 2);

        assertThat(replacement.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(original.status()).isEqualTo(EInvoiceStatus.REPLACED);
        assertThat(original.adjusted()).isFalse();
        verify(invoices).save(original);
    }
}
