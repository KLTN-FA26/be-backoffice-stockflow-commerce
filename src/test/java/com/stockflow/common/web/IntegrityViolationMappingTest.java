package com.stockflow.common.web;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.i18n.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every refusal the database can raise used to come back as {@code DUPLICATE_KEY}: a 301-character
 * description "already existed". The SQLSTATE, read from wherever it sits in the cause chain, now
 * decides the answer.
 */
class IntegrityViolationMappingTest {

    private final StaticMessageSource source = new StaticMessageSource();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new Messages(source), source);

    @ParameterizedTest(name = "SQLSTATE {0} -> {2} {1}")
    @CsvSource({
            "23505, DUPLICATE_KEY,     409",   // unique
            "22001, VALIDATION_FAILED, 400",   // string longer than the column
            "22003, VALIDATION_FAILED, 400",   // number past the column's precision
            "22P02, VALIDATION_FAILED, 400",   // text that does not parse as the column type
            "23502, VALIDATION_FAILED, 400",   // NOT NULL
            "23514, CONFLICT,          409",   // CHECK
            "23503, CONFLICT,          409",   // foreign key
            "23P01, CONFLICT,          409",   // exclusion
    })
    void theSqlStateDecidesTheCode(String sqlState, String code, int status) {
        // Wrapped twice, as Hibernate and the driver wrap it, so the walk down the chain is tested.
        var ex = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("hibernate", new SQLException("ERROR: refused", sqlState)));

        ResponseEntity<ApiResponse<Void>> response = handler.handleIntegrity(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().errorCode()).isEqualTo(code);
    }

    @Test
    void withoutASqlStateItIsAConflictNotADuplicate() {
        var response = handler.handleIntegrity(new DataIntegrityViolationException("no driver cause"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().errorCode()).isEqualTo("CONFLICT");
    }

    /**
     * Review of #155: a deferred constraint trigger refuses the transaction at the commit; Spring reports it as a failed
     * commit, which was a 500. With an integrity SQLSTATE it is the same refusal as above.
     */
    @Test
    void aRuleCheckedAtTheCommitIsAConflictNotAServerError() {
        var sql = new SQLException("ERROR: tg_package_kit_ships_whole: kit ships whole", "23514");
        var commit = new org.springframework.transaction.TransactionSystemException("Could not commit JPA transaction",
                new RuntimeException("rollback", sql));
        var jpa = new org.springframework.orm.jpa.JpaSystemException(new RuntimeException("commit", sql));

        for (Exception failed : java.util.List.of(commit, jpa)) {
            var response = handler.handleFailedCommit(failed);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().errorCode()).isEqualTo("CONFLICT");
            assertThat(response.getBody().message()).doesNotContain("tg_package_kit");
        }
    }

    @Test
    void aFailedCommitWithoutAnIntegritySqlStateIsStillADefect() {
        var response = handler.handleFailedCommit(new org.springframework.transaction.TransactionSystemException(
                "Could not commit", new SQLException("connection reset", "08006")));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }
}
