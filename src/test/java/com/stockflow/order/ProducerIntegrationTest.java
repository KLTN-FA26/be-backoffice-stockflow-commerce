package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-1 against a real database (kltn-docs 17 §4.3; 00 §2.1, 2026-11-07): the producer catalogue the Production
 * Coordinator keeps, its codes once each, its edits at the version read, and the role and grants V20261108002600 seeds.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ProducerIntegrationTest {

    @Autowired ProducerService producers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private static String code() {
        return "IN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static Producer.Details email(String name, int days) {
        return new Producer.Details(name, "Chi Lan", "0901234567", "don@minhphat.vn", Producer.Channel.EMAIL, days,
                null);
    }

    private static ErrorCode errorCode(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("registered, read back, listed by its name or code; one code once (PRODUCER_CODE_TAKEN)")
    void registerAndFind() {
        String code = code();
        Producer saved = producers.register(code.toLowerCase(Locale.ROOT), email("Xuong in Minh Phat", 10));

        assertThat(producers.get(saved.id()).code()).isEqualTo(code);
        assertThat(producers.list(code, null, 0, 20, null).items()).extracting(Producer::id).containsExactly(saved.id());
        assertThat(producers.list("minh phat", Producer.Status.ACTIVE, 0, 50, null).items()).extracting(Producer::id)
                .contains(saved.id());
        assertThatThrownBy(() -> producers.register(code, email("Another", 10))).extracting(
                ProducerIntegrationTest::errorCode).isEqualTo(ErrorCode.PRODUCER_CODE_TAKEN);
        assertThatThrownBy(() -> producers.get(UUID.randomUUID())).extracting(ProducerIntegrationTest::errorCode)
                .isEqualTo(ErrorCode.PRODUCER_NOT_FOUND);
    }

    @Test
    @DisplayName("edited at the version read; a stale version is refused; taken out of use, it is INACTIVE")
    void editAndDeactivate() {
        Producer saved = producers.register(code(), email("Xuong A", 14));
        Producer edited = producers.update(saved.id(), saved.version(), new Producer.Details("Xuong A moi", null, null,
                null, Producer.Channel.PDF, 30, "giao tan kho"), Producer.Status.INACTIVE);

        assertThat(edited.name()).isEqualTo("Xuong A moi");
        assertThat(edited.channel()).isEqualTo(Producer.Channel.PDF);
        assertThat(edited.standardLeadDays()).isEqualTo(30);
        assertThat(edited.status()).isEqualTo(Producer.Status.INACTIVE);
        assertThat(producers.list(saved.code(), Producer.Status.ACTIVE, 0, 20, null).items()).isEmpty();
        assertThatThrownBy(() -> producers.update(saved.id(), saved.version(), email("Late", 14), null))
                .extracting(ProducerIntegrationTest::errorCode).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    @DisplayName("the database holds the same rules: e-mail orders need an address, the lead time 1-365, a code once")
    void databaseRules() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.producer (id, code, name, channel, standard_lead_days)
                VALUES (gen_random_uuid(), ?, 'No address', 'EMAIL', 14)""", code())))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_producer_email");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.producer (id, code, name, channel, standard_lead_days)
                VALUES (gen_random_uuid(), ?, 'Slow', 'PDF', 400)""", code())))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_producer_lead_days");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.producer (id, code, name, channel, standard_lead_days)
                VALUES (gen_random_uuid(), 'lower', 'Lower', 'PDF', 14)""")))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_producer_code");
    }

    @Test
    @DisplayName("00 §2.1 (2026-11-07): the Production Coordinator keeps the catalogue; the others read it")
    void roleAndGrants() {
        assertThat(jdbc.queryForObject("SELECT data_scope FROM identity.app_role WHERE code = 'PRODUCTION_COORDINATOR'",
                String.class)).isEqualTo("ALL");
        assertThat(jdbc.queryForList("""
                SELECT p.action FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE r.code = 'PRODUCTION_COORDINATOR' AND p.resource = 'sales-producers' ORDER BY 1""",
                String.class)).containsExactly("CREATE", "READ", "UPDATE", "VIEW_PAGE");
        assertThat(jdbc.queryForList("""
                SELECT r.code FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.code = 'sales-producers:UPDATE' ORDER BY 1""", String.class))
                .containsExactly("PRODUCTION_COORDINATOR", "SYSTEM_ADMIN");
    }
}
