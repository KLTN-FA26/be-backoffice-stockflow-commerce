package com.stockflow.order.internal.service.signing;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.notification.api.ContractSignOtpMail;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Inspection;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.stockflow.order.internal.service.signing.SigningTestSupport.pdf;
import static com.stockflow.order.internal.service.signing.SigningTestSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parts of contract signing that live in the database: the test certificate made once however many instances start
 * together, and the customer's one-time code with its limits, against a real Postgres and the demo seed's contracts.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false",
        "stockflow.order.contract-signing.otp-resend-after=PT30S",
        "stockflow.order.contract-signing.otp-per-hour=3"})
class ContractSigningIntegrationTest {

    @Autowired ContractSignOtps otps;
    @Autowired ContractSigner signer;
    @Autowired ContractSigningKeys keys;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean NotificationService notifications;

    private UUID contractId;
    private UUID customer;
    private UUID otherCustomer;

    @BeforeEach
    void setUp() {
        contractId = jdbc.queryForObject("SELECT id FROM ordering.sales_contract ORDER BY id LIMIT 1", UUID.class);
        var users = jdbc.queryForList("SELECT id FROM identity.app_user ORDER BY id LIMIT 2", UUID.class);
        customer = users.get(0);
        otherCustomer = users.get(1);
        update("DELETE FROM ordering.contract_sign_otp WHERE contract_id = ?", contractId);
        Mockito.reset(notifications);
    }

    /** A write that commits: the connections do not auto-commit, so a bare JdbcTemplate update would roll back. */
    private void update(String sql, Object... args) {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> jdbc.update(sql, args));
    }

    private String issue() {
        otps.issue(contractId, "045/2027/HDMB", 1, customer, "minh@example.com", "203.0.113.5");
        ArgumentCaptor<ContractSignOtpMail> mail = ArgumentCaptor.forClass(ContractSignOtpMail.class);
        Mockito.verify(notifications, Mockito.atLeastOnce()).sendContractSignOtp(mail.capture());
        return mail.getValue().code();
    }

    /** Lets the resend interval pass without waiting: moves every code of the contract back in time. */
    private void ageCodes() {
        update("""
                UPDATE ordering.contract_sign_otp
                   SET created_at = created_at - interval '2 minutes', expires_at = expires_at - interval '2 minutes'
                 WHERE contract_id = ?""", contractId);
    }

    @Test
    @DisplayName("a code is mailed once, stored only as a hash, and signs once")
    void codeIsMailedHashedAndUsedOnce() {
        var issued = otps.issue(contractId, "045/2027/HDMB", 1, customer, "minh@example.com", "203.0.113.5");

        ArgumentCaptor<ContractSignOtpMail> mail = ArgumentCaptor.forClass(ContractSignOtpMail.class);
        Mockito.verify(notifications).sendContractSignOtp(mail.capture());
        String code = mail.getValue().code();
        assertThat(code).matches("\\d{6}");
        assertThat(mail.getValue().toString()).doesNotContain(code);
        assertThat(issued.sentTo()).isEqualTo("m***@example.com");
        assertThat(issued.expiresAt()).isAfter(Instant.now().plusSeconds(500)).isBefore(Instant.now().plusSeconds(700));
        assertThat(issued.resendAfter()).isAfter(Instant.now());
        String stored = jdbc.queryForObject("SELECT code_hash FROM ordering.contract_sign_otp WHERE contract_id = ?",
                String.class, contractId);
        assertThat(stored).matches("[0-9a-f]{64}").doesNotContain(code);

        otps.verify(contractId, 1, customer, code);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.contract_sign_otp WHERE contract_id = ? "
                + "AND consumed_at IS NOT NULL", Integer.class, contractId)).isEqualTo(1);
        assertThatThrownBy(() -> otps.verify(contractId, 1, customer, code)).isInstanceOfSatisfying(
                BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_EXPIRED));
    }

    @Test
    @DisplayName("a wrong code counts down five tries and then the code is spent, even for the right digits")
    void wrongCodesSpendTheCode() {
        String code = issue();
        String wrong = code.equals("000000") ? "000001" : "000000";

        for (int left = 4; left >= 0; left--) {
            int expected = left;
            assertThatThrownBy(() -> otps.verify(contractId, 1, customer, wrong)).isInstanceOfSatisfying(
                    BusinessException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_INVALID);
                        assertThat(ex.details()).containsEntry("attemptsLeft", expected);
                    });
        }
        assertThatThrownBy(() -> otps.verify(contractId, 1, customer, code)).isInstanceOfSatisfying(
                BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_EXPIRED));
    }

    @Test
    @DisplayName("tries stay counted when the caller's own transaction rolls back")
    void triesSurviveACallersRollback() {
        String code = issue();
        String wrong = code.equals("000000") ? "000001" : "000000";
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> otps.verify(contractId, 1, customer,
                wrong))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT attempts FROM ordering.contract_sign_otp WHERE contract_id = ?",
                Integer.class, contractId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an expired code, another customer's code and another revision's are not checked against the right one")
    void expiredAndForeignCodes() {
        String code = issue();

        assertThatThrownBy(() -> otps.verify(contractId, 1, otherCustomer, code)).isInstanceOfSatisfying(
                BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_EXPIRED));
        assertThatThrownBy(() -> otps.verify(contractId, 2, customer, code)).isInstanceOfSatisfying(
                BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_EXPIRED));
        assertThat(jdbc.queryForObject("SELECT attempts FROM ordering.contract_sign_otp WHERE contract_id = ?",
                Integer.class, contractId)).as("no try is burnt by someone else's request").isZero();

        update("""
                UPDATE ordering.contract_sign_otp SET created_at = now() - interval '2 hours',
                       expires_at = now() - interval '1 hour' WHERE contract_id = ?""", contractId);
        assertThatThrownBy(() -> otps.verify(contractId, 1, customer, code)).isInstanceOfSatisfying(
                BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_EXPIRED));
    }

    @Test
    @DisplayName("asking again supersedes the old code; asking too soon, or a fourth time in the hour, is 429")
    void resendAndHourlyLimits() {
        String first = issue();
        assertThatThrownBy(this::issue).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_TOO_MANY);
            assertThat(ex.details()).containsKey("retryAfterSeconds");
        });

        ageCodes();
        String second = issue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.contract_sign_otp WHERE contract_id = ? "
                + "AND superseded_at IS NULL AND consumed_at IS NULL", Integer.class, contractId))
                .as("one live code per contract").isEqualTo(1);
        if (!first.equals(second)) {
            assertThatThrownBy(() -> otps.verify(contractId, 1, customer, first)).isInstanceOf(
                    BusinessException.class);
        }
        otps.verify(contractId, 1, customer, second);

        ageCodes();
        issue();
        ageCodes();
        assertThatThrownBy(this::issue).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_OTP_TOO_MANY));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.contract_sign_otp WHERE contract_id = ?",
                Integer.class, contractId)).isEqualTo(3);
    }

    @Test
    @DisplayName("a mail that cannot be sent leaves no code behind and is not counted")
    void mailFailureRollsTheCodeBack() {
        Mockito.doThrow(new IllegalStateException("provider down")).when(notifications)
                .sendContractSignOtp(Mockito.any());

        assertThatThrownBy(() -> otps.issue(contractId, "1/2027", 1, customer, "minh@example.com", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.contract_sign_otp WHERE contract_id = ?",
                Integer.class, contractId)).isZero();
    }

    @Test
    @DisplayName("the test certificate is made once: four callers at once all get the same stored key, and it signs")
    void certificateIsGeneratedOnce() throws Exception {
        update("DELETE FROM ordering.contract_signing_key");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                go.await();
                return keys.loadOrCreate("TEST_CERT", () -> new ContractSigningKeys.Stored(
                        ("certificate-" + i).getBytes(), "v1:sealed-by-" + i));
            })).toList();
            go.countDown();
            String first = null;
            for (Future<ContractSigningKeys.Stored> future : futures) {
                String sealed = future.get().privateKeySealed();
                first = first == null ? sealed : first;
                assertThat(sealed).isEqualTo(first);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.contract_signing_key", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the real signer stores its key sealed (never in clear), reuses it, and signs a PDF that inspects valid")
    void signerStoresItsKeySealed() {
        update("DELETE FROM ordering.contract_signing_key");
        // A fresh signer over the real store: the Spring bean may have loaded before the table was cleared.
        var fresh = new TestCertificateSigner(SigningTestSupport.properties("", "", SigningTestSupport.SECRET),
                SigningTestSupport.seller(), new SigningSecrets(
                SigningTestSupport.properties("", "", SigningTestSupport.SECRET),
                SigningTestSupport.testProfile()), keys, java.time.Clock.systemUTC());

        var identity = fresh.identity();
        String sealed = jdbc.queryForObject("SELECT private_key_sealed FROM ordering.contract_signing_key",
                String.class);
        assertThat(sealed).startsWith("v1:").doesNotContain("MIIE");
        // A second signer (another instance, a restart) is the same certificate, without generating another.
        var again = new TestCertificateSigner(SigningTestSupport.properties("", "", SigningTestSupport.SECRET),
                SigningTestSupport.seller(), new SigningSecrets(
                SigningTestSupport.properties("", "", SigningTestSupport.SECRET),
                SigningTestSupport.testProfile()), keys, java.time.Clock.systemUTC());
        assertThat(again.identity().serial()).isEqualTo(identity.serial());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.contract_signing_key", Integer.class))
                .isEqualTo(1);

        byte[] sent = pdf(1);
        Inspection inspection = new SignedPdfInspector().inspect(
                new PadesSigner().sign(sent, again, "A", "r", "l", Instant.now()), sha256(sent), sent.length);
        assertThat(inspection.acceptedAsSentContract()).isTrue();

        // The wrong secret cannot open it, and says why, without a new certificate being made.
        var wrongSecret = new TestCertificateSigner(SigningTestSupport.properties("", "", "another-secret"),
                SigningTestSupport.seller(), new SigningSecrets(
                SigningTestSupport.properties("", "", "another-secret"),
                SigningTestSupport.testProfile()), keys, java.time.Clock.systemUTC());
        assertThatThrownBy(wrongSecret::identity).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONTRACT_SIGNING_SECRET");
    }

    @Test
    @DisplayName("the application's own signer bean is the test certificate signer")
    void theBeanIsWired() {
        assertThat(signer).isInstanceOf(TestCertificateSigner.class);
        assertThat(ContractSignOtps.masked("minh@example.com")).isEqualTo("m***@example.com");
        assertThat(ContractSignOtps.masked("nonsense")).isEqualTo("***");
    }
}
