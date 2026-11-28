package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.contracts.ContractCustomerSigned;
import com.stockflow.notification.api.ContractSignOtpMail;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.service.ContractSigning;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.order.internal.service.signing.ContractSigner;
import com.stockflow.order.internal.service.signing.PadesSigner;
import com.stockflow.order.internal.service.signing.SignedPdfInspector;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SalesOwners;
import com.stockflow.support.TestPdfs;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract e-signing flow end to end (plan of 2026-11-28, the integrator's part), against the real database and the
 * real signer: draw up, send, the customer signs with a code that was mailed (captured from the mail port - the code is
 * never logged), the company signs, and the order is placed exactly as when staff record a signed file. Then the
 * fallbacks - a scan from the customer, a file signed outside from the company - and everything that must be refused.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false",
        "stockflow.order.contract-signing.otp-resend-after=PT1S"})
class ContractESigningFlowIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ContractSigning.Origin ORIGIN = new ContractSigning.Origin("203.0.113.9", "JUnit/1.0");

    @Autowired SalesContracts contracts;
    @Autowired ContractSigning signing;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired ContractSigner signer;
    @Autowired PadesSigner pades;
    @Autowired SignedPdfInspector inspector;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationContext context;
    @Autowired RecordedEvents.Recorder recorded;
    @MockitoBean NotificationService notifications;
    /** Local storage cannot sign browser links; the link is the store's business, what is linked to is checked by key. */
    @MockitoSpyBean FileTransfers files;

    private CurrentUser sales;
    private CurrentUser manager;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        sales = SalesOwners.caller(tx.execute(s -> ReferenceRows.user(entityManager)), Role.SALES_STAFF);
        manager = SalesOwners.caller(tx.execute(s -> ReferenceRows.user(entityManager)), Role.SALES_MANAGER);
        today = LocalDate.now(VIETNAM);
        recorded.clear();
        Mockito.reset(notifications);
        Mockito.doAnswer(call -> new DownloadLink("https://files.example.test/" + call.getArgument(0), null))
                .when(files).downloadLink(Mockito.anyString(), Mockito.anyString());
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    /** A customer with a portal account, a legal profile and its own SKU in cartons of 50. */
    private record Buyer(UUID customerId, CurrentUser account, CustomerSkuRows.Issued sku) { }

    private Buyer buyer(String name) {
        UUID customerId = Identifiers.newId();
        UUID accountId = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at, user_id)
                    VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW(), ?)""", customerId, name,
                    "esign-" + customerId + "@example.com", "07" + (10_000_000 + ThreadLocalRandom.current()
                            .nextInt(89_999_999)), accountId);
            jdbc.update("""
                    INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address,
                        representative_name, representative_title, version, created_at)
                    VALUES (?, ?, '9 Hai Ba Trung, TP HCM', 'Pham Van E', 'Director', 0, NOW())""",
                    Identifiers.newId(), customerId);
        });
        return new Buyer(customerId, SalesOwners.caller(accountId, Role.CUSTOMER),
                CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, 50, "STANDARD", true));
    }

    /** A DRAFT order of 20 cartons of 50 at 2,000 a piece with 10% VAT, its contract drawn up and sent. */
    private UUID sentContract(Buyer buyer) {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(buyer.sku().sku()), 1000, Money.vnd(2_000), null)
                    .withCartons(buyer.sku().uomConversionId(), 20, 50).withTax(BigDecimal.TEN);
            Order order = Order.draft(orderRepository.nextOrderNumber(today), buyer.customerId(), Identifiers.newId(),
                    List.of(line), Instant.now());
            return orderRepository.save(order).id().value();
        });
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        SalesContracts.ContractView drawn = as(sales, () -> contracts.create(sales, orderId,
                new SalesContracts.Draft(null, List.of(new SalesContracts.Batch(today.plusDays(21), today.plusDays(23),
                        List.of(new SalesContracts.BatchLine(line, 20)))), List.of(new SalesContracts.Installment(
                        "On signing", "ON_SIGNING", null, new BigDecimal("100"), null, null)), null, null)));
        as(sales, () -> contracts.send(sales, orderId, drawn.version()));
        return orderId;
    }

    private <T> T as(CurrentUser user, Supplier<T> call) {
        return WithCurrentUser.call(user, DataScope.ALL, call);
    }

    private SalesContracts.ContractView view(UUID orderId) {
        return as(sales, () -> contracts.get(orderId));
    }

    /** Asks for a code and returns what was mailed; the mail port is the only place it is ever seen. */
    private String mailedCode(Buyer buyer, UUID orderId) {
        Mockito.reset(notifications);
        signing.requestOtp(buyer.account(), orderId, ORIGIN.ip());
        ArgumentCaptor<ContractSignOtpMail> mail = ArgumentCaptor.forClass(ContractSignOtpMail.class);
        Mockito.verify(notifications).sendContractSignOtp(mail.capture());
        return mail.getValue().code();
    }

    private SalesContracts.ContractView customerSignsWithCode(Buyer buyer, UUID orderId) {
        String code = mailedCode(buyer, orderId);
        return signing.customerSignWithOtp(buyer.account(), orderId, view(orderId).version(), code, "Pham Van E",
                "Director", true, ORIGIN);
    }

    private byte[] stored(String key) {
        try (InputStream in = storage.download(key)) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private byte[] sentDocument(UUID orderId) {
        return stored(jdbc.queryForObject("SELECT sent_document_key FROM ordering.sales_contract WHERE order_id = ?",
                String.class, orderId));
    }

    private static byte[] sha256(byte[] bytes) {
        return com.stockflow.common.storage.Checksums.sha256().digest(bytes);
    }

    private String signedFileKey(UUID orderId) {
        return jdbc.queryForObject("SELECT signed_file_key FROM ordering.sales_contract WHERE order_id = ?",
                String.class, orderId);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    private static String reason(Throwable e) {
        return (String) ((BusinessException) e).details().get("reason");
    }

    // ---- the main path -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the code is mailed, the customer signs with it, the company signs: SIGNED, the order placed as when a "
            + "signed file is recorded, the signed file valid, evidence for both, verifiable by its code")
    void customerSignsWithTheCodeThenTheCompanySigns() {
        Buyer buyer = buyer("Cong ty Ky Dien Tu");
        UUID orderId = sentContract(buyer);

        // A wrong code counts down; the right one still works afterwards.
        String code = mailedCode(buyer, orderId);
        String wrong = code.equals("000000") ? "000001" : "000000";
        long version = view(orderId).version();
        assertThatThrownBy(() -> signing.customerSignWithOtp(buyer.account(), orderId, version, wrong, "Pham Van E",
                "Director", true, ORIGIN)).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_INVALID);
            assertThat(ex.details()).containsEntry("attemptsLeft", 4);
        });
        assertThat(view(orderId).status()).as("a wrong code signs nothing").isEqualTo("SENT");
        assertThatThrownBy(() -> signing.customerSignWithOtp(buyer.account(), orderId, version, code, "Pham Van E",
                "Director", false, ORIGIN)).as("without consent").extracting(ContractESigningFlowIntegrationTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        SalesContracts.ContractView signedByCustomer = signing.customerSignWithOtp(buyer.account(), orderId, version,
                code, "Pham Van E", "Director", true, ORIGIN);

        assertThat(signedByCustomer.status()).isEqualTo("CUSTOMER_SIGNED");
        assertThat(signedByCustomer.customerSignature()).satisfies(s -> {
            assertThat(s.method()).isEqualTo("OTP");
            assertThat(s.otpVerified()).isTrue();
            assertThat(s.signerName()).isEqualTo("Pham Van E");
            assertThat(s.ip()).as("never on the portal").isNull();
        });
        assertThat(signedByCustomer.verificationCode()).as("not before it is signed by both").isNull();
        assertThat(orders.findById(orderId).orElseThrow().status()).as("the order waits for the company")
                .isEqualTo(OrderStatus.PENDING_CONTRACT);
        assertThat(recorded.matching(ContractCustomerSigned.class, e -> e.orderId().equals(orderId))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT ip FROM ordering.sales_contract_signature WHERE contract_id = ? "
                + "AND party = 'CUSTOMER'", String.class, signedByCustomer.id())).isEqualTo("203.0.113.9");
        assertThat(jdbc.queryForObject("SELECT document_sha256 FROM ordering.sales_contract_signature WHERE "
                + "contract_id = ? AND party = 'CUSTOMER'", String.class, signedByCustomer.id()))
                .as("the SHA-256 of the PDF that was sent").hasSize(64);
        assertThatThrownBy(() -> signing.customerSignWithOtp(buyer.account(), orderId, version, code, "Pham Van E",
                "Director", true, ORIGIN)).as("the code was used up, the contract is signed")
                .isInstanceOf(BusinessException.class);

        long waiting = view(orderId).version();
        assertThatThrownBy(() -> signing.companySign(sales, orderId, waiting, null, null, ORIGIN))
                .as("four eyes: the account that drew it up and sent it")
                .extracting(ContractESigningFlowIntegrationTest::code).isEqualTo(ErrorCode.CONTRACT_SIGNER_IS_AUTHOR);
        assertThat(view(orderId).status()).isEqualTo("CUSTOMER_SIGNED");

        SalesContracts.ContractView signed = signing.companySign(manager, orderId, waiting, "Nguyen Thi M",
                "Deputy director", ORIGIN);

        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.signedBy()).isEqualTo(manager.userId());
        assertThat(orders.findById(orderId).orElseThrow().status())
                .as("exactly as when staff record a signed file: the installment on signing is awaited")
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.installment WHERE contract_id = ?", Long.class,
                signed.id())).isEqualTo(1);
        assertThat(signed.companySignature()).satisfies(s -> {
            assertThat(s.method()).isEqualTo("SYSTEM");
            assertThat(s.signerName()).isEqualTo("Nguyen Thi M");
            assertThat(s.testCertificate()).isTrue();
            assertThat(s.certificateSubject()).isNotBlank();
            assertThat(s.byName()).isNotBlank();
        });
        assertThat(signed.verificationCode()).matches("[A-HJKMNP-Z2-9]{12}");

        SignedPdfInspector.Inspection inspection = inspector.inspect(stored(signedFileKey(orderId)));
        assertThat(inspection.kind()).isEqualTo(SignedPdfInspector.Kind.DIGITAL);
        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.coversWholeDocument()).isTrue();
        // The certificate page went in as an incremental update: the very bytes the customer was sent come first.
        byte[] sent = sentDocument(orderId);
        byte[] companySigned = stored(signedFileKey(orderId));
        assertThat(java.util.Arrays.copyOf(companySigned, sent.length)).isEqualTo(sent);
        SignedPdfInspector.Inspection againstSent = inspector.inspect(companySigned, sha256(sent), sent.length);
        assertThat(againstSent.matchesSentDocument()).isTrue();
        assertThat(againstSent.acceptedAsSentContract()).isTrue();

        // The evidence reads back as the plan's signature view, with the address only in the back office.
        SalesContracts.SignatureView evidence = as(manager, () -> signing.signatures(orderId));
        assertThat(evidence.customer().ip()).isEqualTo("203.0.113.9");
        assertThat(evidence.company().certificateIssuer()).isNotBlank();
        assertThat(evidence.documentSha256()).hasSize(64);
        SalesContracts.ContractView portal = contracts.customerContract(buyer.customerId(), orderId);
        assertThat(portal.customerSignature().ip()).isNull();
        assertThat(portal.companySignature().byName()).isNull();
        assertThat(portal.verificationCode()).isEqualTo(signed.verificationCode());

        // The public verification: by the code, grouped and in lower case as a person types it, and a file against it.
        String grouped = signed.verificationCode().substring(0, 4).toLowerCase() + "-"
                + signed.verificationCode().substring(4, 8) + " " + signed.verificationCode().substring(8);
        ContractSigning.Verification verification = signing.verification(grouped).orElseThrow();
        assertThat(verification.status()).isEqualTo("SIGNED");
        assertThat(verification.documentSha256()).isEqualTo(evidence.documentSha256());
        assertThat(verification.testCertificate()).isTrue();
        assertThat(signing.matches(grouped, stored(signedFileKey(orderId)))).contains(true);
        assertThat(signing.matches(grouped, TestPdfs.blank())).contains(false);
        assertThat(signing.verification("AAAAAAAAAAAA")).isEmpty();
        assertThat(signing.verification("not a code")).isEmpty();
        assertThat(signing.customerCertificatePage(buyer.account(), orderId).url()).isNotBlank();
    }

    @Test
    @DisplayName("a customer who sends a scan is accepted as such; a signature that does not hold, or is over another "
            + "document, is refused with its reason, and nothing is signed")
    void customerUploadsAScan() {
        Buyer buyer = buyer("Cong ty Ban Scan");
        UUID orderId = sentContract(buyer);
        long version = view(orderId).version();

        assertThatThrownBy(() -> signing.customerSignWithFile(buyer.account(), orderId, version, "Pham Van E",
                "Director", "x.pdf", "not a pdf".getBytes(), ORIGIN)).satisfies(e -> {
            assertThat(code(e)).isEqualTo(ErrorCode.CONTRACT_SIGNED_FILE_REJECTED);
            assertThat(reason(e)).isEqualTo(SignedPdfInspector.Reasons.NOT_A_PDF);
        });
        byte[] otherDocument = pades.sign(TestPdfs.blank(), signer, "Someone", "x", "y", Instant.now());
        assertThatThrownBy(() -> signing.customerSignWithFile(buyer.account(), orderId, version, "Pham Van E",
                "Director", "other.pdf", otherDocument, ORIGIN)).satisfies(e -> {
            assertThat(code(e)).isEqualTo(ErrorCode.CONTRACT_SIGNED_FILE_REJECTED);
            assertThat(reason(e)).isNotBlank();
        });
        assertThat(view(orderId).status()).isEqualTo("SENT");

        SalesContracts.ContractView signed = signing.customerSignWithFile(buyer.account(), orderId, version,
                "Pham Van E", "Director", "scan.pdf", TestPdfs.blank(), ORIGIN);

        assertThat(signed.status()).isEqualTo("CUSTOMER_SIGNED");
        assertThat(signed.customerSignature().method()).isEqualTo("UPLOAD");
        assertThat(signed.customerSignature().otpVerified()).isFalse();
        assertThat(signed.customerSignature().inspection().kind()).isEqualTo("SCAN");
        assertThat(signed.customerSignature().inspection().valid()).isFalse();
        assertThat(jdbc.queryForObject("SELECT file_key FROM ordering.sales_contract_signature WHERE contract_id = ? "
                + "AND party = 'CUSTOMER'", String.class, signed.id())).isNotBlank();
    }

    @Test
    @DisplayName("20 wrong codes over a revision lock it: no code is issued or checked, the right one included, until "
            + "Sales extend the deadline")
    void wrongCodesLockTheRevisionUntilSalesExtendTheDeadline() {
        Buyer buyer = buyer("Cong ty Khoa Ma");
        UUID orderId = sentContract(buyer);
        UUID contractId = view(orderId).id();
        // Three earlier codes spent on wrong tries (15), long enough ago that neither limit of asking applies.
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 3; i++) {
                jdbc.update("""
                    INSERT INTO ordering.contract_sign_otp (id, contract_id, revision, customer_user_id, email,
                        code_hash, expires_at, attempts, superseded_at, created_at)
                    VALUES (?, ?, 1, ?, 'x@example.com', ?, NOW() - interval '2 hours', 5, NOW() - interval '2 hours',
                        NOW() - interval '3 hours')""", Identifiers.newId(), contractId, buyer.account().userId(),
                        "a".repeat(64));
            }
        });
        String code = mailedCode(buyer, orderId);
        String wrong = code.equals("000000") ? "000001" : "000000";
        long version = view(orderId).version();
        for (int left = 4; left >= 0; left--) {
            int expected = left;
            assertThatThrownBy(() -> signing.customerSignWithOtp(buyer.account(), orderId, version, wrong,
                    "Pham Van E", "Director", true, ORIGIN)).isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_INVALID);
                assertThat(ex.details()).containsEntry("attemptsLeft", expected);
            });
        }
        assertThat(jdbc.queryForList("SELECT revision || ':' || attempts || ':' || (forgiven_at IS NULL) || ':' "
                + "|| contract_id FROM ordering.contract_sign_otp WHERE contract_id = ?", String.class, contractId))
                .as("20 wrong tries over revision 1").hasSize(4);
        assertThat(jdbc.queryForObject("SELECT sum(attempts) FROM ordering.contract_sign_otp WHERE contract_id = ? "
                + "AND revision = 1 AND forgiven_at IS NULL", Long.class, contractId)).isEqualTo(20);
        assertThatThrownBy(() -> signing.customerSignWithOtp(buyer.account(), orderId, version, code, "Pham Van E",
                "Director", true, ORIGIN)).as("the right code, too late").extracting(
                ContractESigningFlowIntegrationTest::code).isEqualTo(ErrorCode.CONTRACT_OTP_LOCKED);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.contract_sign_otp SET created_at = created_at "
                + "- interval '2 hours' WHERE contract_id = ?", contractId));
        assertThatThrownBy(() -> signing.requestOtp(buyer.account(), orderId, ORIGIN.ip()))
                .as("no new code either").extracting(ContractESigningFlowIntegrationTest::code)
                .isEqualTo(ErrorCode.CONTRACT_OTP_LOCKED);
        assertThat(view(orderId).status()).isEqualTo("SENT");

        as(sales, () -> contracts.extendSignBy(sales, orderId, view(orderId).version(), today.plusDays(10)));

        assertThat(customerSignsWithCode(buyer, orderId).status()).as("forgiven: a new code signs")
                .isEqualTo("CUSTOMER_SIGNED");
    }

    @Test
    @DisplayName("the company signs outside: the PDF to sign is made once; the file signed over exactly it signs the "
            + "contract; a scan, or a signature over other content, is refused")
    void companySignsOutside() {
        Buyer buyer = buyer("Cong ty Ky Ngoai");
        UUID orderId = sentContract(buyer);
        customerSignsWithCode(buyer, orderId);
        long version = view(orderId).version();

        assertThat(as(manager, () -> signing.companyDocumentToSign(manager, orderId)).url()).isNotBlank();
        String outsideKey = jdbc.queryForObject("SELECT outside_document_key FROM ordering.sales_contract WHERE "
                + "order_id = ?", String.class, orderId);
        as(manager, () -> signing.companyDocumentToSign(manager, orderId));
        assertThat(jdbc.queryForObject("SELECT outside_document_key FROM ordering.sales_contract WHERE order_id = ?",
                String.class, orderId)).as("made once").isEqualTo(outsideKey);
        assertThatThrownBy(() -> as(sales, () -> signing.companyDocumentToSign(sales, orderId)))
                .extracting(ContractESigningFlowIntegrationTest::code).isEqualTo(ErrorCode.CONTRACT_SIGNER_IS_AUTHOR);
        byte[] toSign = stored(outsideKey);
        byte[] sent = sentDocument(orderId);
        assertThat(java.util.Arrays.copyOf(toSign, sent.length)).as("the sent bytes come first").isEqualTo(sent);
        try (var document = org.apache.pdfbox.Loader.loadPDF(toSign)) {
            var lastPage = new org.apache.pdfbox.text.PDFTextStripper();
            lastPage.setStartPage(document.getNumberOfPages());
            String text = lastPage.getText(document);
            assertThat(text).contains("Chuẩn bị để ký ngoài hệ thống")
                    .contains(jdbc.queryForObject("SELECT verification_code FROM ordering.sales_contract "
                            + "WHERE order_id = ?", String.class, orderId).substring(0, 4))
                    .doesNotContain("Tài khoản thực hiện").doesNotContain(manager.username());
            assertThat(text.substring(text.indexOf("3. Bên bán ký"))).as("no company signer or time in advance")
                    .doesNotContain("Thời điểm ký").doesNotContain("Người ký");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }

        assertThatThrownBy(() -> as(manager, () -> signing.companySignWithFile(manager, orderId, version, null, null,
                "scan.pdf", toSign, ORIGIN))).as("unsigned: the very PDF handed out is not a signature")
                .satisfies(e -> {
                    assertThat(code(e)).isEqualTo(ErrorCode.CONTRACT_SIGNED_FILE_REJECTED);
                    assertThat(reason(e)).isEqualTo(SignedPdfInspector.Reasons.NO_DIGITAL_SIGNATURE);
                });
        byte[] differentContent = pades.sign(TestPdfs.blank(), signer, "Someone", "x", "y", Instant.now());
        assertThatThrownBy(() -> as(manager, () -> signing.companySignWithFile(manager, orderId, version, null, null,
                "other.pdf", differentContent, ORIGIN))).as("signed, but over another content").satisfies(e -> {
            assertThat(code(e)).isEqualTo(ErrorCode.CONTRACT_SIGNED_FILE_REJECTED);
            assertThat(reason(e)).isEqualTo(SignedPdfInspector.Reasons.NOT_SENT_DOCUMENT);
        });
        byte[] truncated = java.util.Arrays.copyOf(pades.sign(toSign, signer, "Someone", "x", "y", Instant.now()),
                toSign.length + 200);
        assertThatThrownBy(() -> as(manager, () -> signing.companySignWithFile(manager, orderId, version, null, null,
                "cut.pdf", truncated, ORIGIN))).as("a truncated signed file never reads as a scan that signs")
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.CONTRACT_SIGNED_FILE_REJECTED));
        assertThat(view(orderId).status()).isEqualTo("CUSTOMER_SIGNED");

        byte[] signedOutside = pades.sign(toSign, signer, "Nguyen Thi M", "Ky hop dong", "Viet Nam", Instant.now());
        SalesContracts.ContractView signed = as(manager, () -> signing.companySignWithFile(manager, orderId, version,
                "Nguyen Thi M", "Deputy director", "signed.pdf", signedOutside, ORIGIN));

        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.companySignature().method()).isEqualTo("UPLOAD");
        assertThat(signed.companySignature().inspection().kind()).isEqualTo("DIGITAL");
        assertThat(signed.companySignature().inspection().valid()).isTrue();
        assertThat(signed.companySignature().inspection().matchesSentDocument()).isTrue();
        assertThat(signed.companySignature().inspection().coversWholeDocument()).isTrue();
        assertThat(signed.verificationCode()).as("the code its certificate page printed").isNotBlank();
        SignedPdfInspector.Inspection againstSent = inspector.inspect(stored(signedFileKey(orderId)), sha256(sent),
                sent.length);
        assertThat(againstSent.matchesSentDocument()).as("signed outside, still the contract the customer was sent")
                .isTrue();

        // Every file the contract keeps is kept by the orphaned-file sweep while a row names it.
        List<String> keys = jdbc.queryForList("""
                SELECT k FROM (
                    SELECT sent_document_key AS k FROM ordering.sales_contract WHERE order_id = :o
                    UNION SELECT signed_file_key FROM ordering.sales_contract WHERE order_id = :o
                    UNION SELECT outside_document_key FROM ordering.sales_contract WHERE order_id = :o
                    UNION SELECT outside_page_key FROM ordering.sales_contract WHERE order_id = :o
                    UNION SELECT r.sent_document_key FROM ordering.sales_contract_revision r
                          JOIN ordering.sales_contract c ON c.id = r.contract_id WHERE c.order_id = :o
                    UNION SELECT s.file_key FROM ordering.sales_contract_signature s
                          JOIN ordering.sales_contract c ON c.id = s.contract_id WHERE c.order_id = :o
                    UNION SELECT s.certificate_page_key FROM ordering.sales_contract_signature s
                          JOIN ordering.sales_contract c ON c.id = s.contract_id WHERE c.order_id = :o
                ) keys WHERE k IS NOT NULL""".replace(":o", "'" + orderId + "'"), String.class);
        assertThat(keys).as("sent, signed, to sign outside, its page, the revision's PDF").hasSizeGreaterThanOrEqualTo(4);
        assertThat(context.getBeansOfType(com.stockflow.common.storage.StoredFileReferences.class).values().stream()
                .flatMap(r -> r.referenced(com.stockflow.common.storage.FileCategory.DOCUMENT,
                        new java.util.HashSet<>(keys)).stream()).collect(java.util.stream.Collectors.toSet()))
                .containsAll(keys);
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(signing.matches(signed.verificationCode(), signedOutside)).contains(true);
    }

    @Test
    @DisplayName("staff recording a file signed by both inspects it and keeps evidence of both; a scan is never refused")
    void staffRecordingStoresEvidence() {
        Buyer buyer = buyer("Cong ty Ghi Nhan");
        UUID orderId = sentContract(buyer);
        long version = view(orderId).version();
        byte[] scan = TestPdfs.blank();

        SalesContracts.ContractView signed = as(sales, () -> contracts.sign(sales, orderId, version,
                com.stockflow.common.storage.IncomingFiles.sent(List.of(new com.stockflow.common.storage.FileUpload(
                        "signed.pdf", "application/pdf", scan.length, new java.io.ByteArrayInputStream(scan)))), today));

        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.customerSignature().method()).isEqualTo("STAFF_RECORDED");
        assertThat(signed.companySignature().method()).isEqualTo("STAFF_RECORDED");
        assertThat(signed.companySignature().inspection().kind()).isEqualTo("SCAN");
        assertThat(signed.verificationCode()).isNotBlank();
        assertThat(signing.verification(signed.verificationCode()).orElseThrow().status()).isEqualTo("SIGNED");
    }

    @Test
    @DisplayName("the evidence is append-only: the database refuses to change or delete a signature")
    void evidenceCannotBeChanged() {
        Buyer buyer = buyer("Cong ty Bang Chung");
        UUID orderId = sentContract(buyer);
        customerSignsWithCode(buyer, orderId);
        UUID contractId = view(orderId).id();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.sales_contract_signature "
                + "SET signer_name = 'Someone else' WHERE contract_id = ?", contractId)))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("DELETE FROM "
                + "ordering.sales_contract_signature WHERE contract_id = ?", contractId)))
                .hasMessageContaining("append-only");
    }
}
