package com.stockflow.order.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.order.internal.domain.ContractSignatures;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ContractSignatures} in SQL on {@code ordering.sales_contract_signature} (append-only: a trigger refuses UPDATE
 * and DELETE) and on the columns of {@code ordering.sales_contract} that belong to signing. Those columns are not
 * mapped by the contract's entity, so the contract's own save cannot overwrite them; the database lets each be set once.
 */
@Repository
class ContractSignaturesAdapter implements ContractSignatures {

    private static final String COLUMNS = "contract_id, revision, party, method, signer_name, signer_title, email, "
            + "account_user_id, signed_at, ip, user_agent, document_sha256, file_key, inspection::text AS inspection, "
            + "certificate_subject, certificate_issuer, certificate_serial, certificate_page_key, test_certificate";

    private static final RowMapper<Signature> SIGNATURE = (rs, n) -> new Signature(
            rs.getObject("contract_id", UUID.class), rs.getInt("revision"), Party.valueOf(rs.getString("party")),
            Method.valueOf(rs.getString("method")), rs.getString("signer_name"), rs.getString("signer_title"),
            rs.getString("email"), rs.getObject("account_user_id", UUID.class),
            rs.getTimestamp("signed_at").toInstant(), rs.getString("ip"), rs.getString("user_agent"),
            rs.getString("document_sha256"), rs.getString("file_key"), rs.getString("inspection"),
            rs.getString("certificate_subject"), rs.getString("certificate_issuer"),
            rs.getString("certificate_serial"), rs.getString("certificate_page_key"),
            rs.getBoolean("test_certificate"));

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditorAware<String> auditor;

    ContractSignaturesAdapter(NamedParameterJdbcTemplate jdbc, AuditorAware<String> auditor) {
        this.jdbc = jdbc;
        this.auditor = auditor;
    }

    @Override
    public void record(Signature s) {
        jdbc.update("""
                INSERT INTO ordering.sales_contract_signature (id, contract_id, revision, party, method, signer_name,
                    signer_title, email, account_user_id, signed_at, ip, user_agent, document_sha256, file_key,
                    inspection, certificate_subject, certificate_issuer, certificate_serial, certificate_page_key, test_certificate,
                    created_at, created_by)
                VALUES (:id, :contract, :revision, :party, :method, :name, :title, :email, :account, :at, :ip, :agent,
                    :sha, :file, CAST(:inspection AS jsonb), :subject, :issuer, :serial, :page, :test, :at, :auditor)""",
                new MapSqlParameterSource().addValue("id", Identifiers.newId())
                        .addValue("contract", s.contractId()).addValue("revision", s.revision())
                        .addValue("party", s.party().name()).addValue("method", s.method().name())
                        .addValue("name", s.signerName()).addValue("title", s.signerTitle())
                        .addValue("email", s.email()).addValue("account", s.accountUserId())
                        .addValue("at", Timestamp.from(s.signedAt())).addValue("ip", s.ip())
                        .addValue("agent", s.userAgent()).addValue("sha", s.documentSha256())
                        .addValue("file", s.fileKey()).addValue("inspection", s.inspectionJson())
                        .addValue("subject", s.certificateSubject()).addValue("issuer", s.certificateIssuer())
                        .addValue("serial", s.certificateSerial()).addValue("page", s.certificatePageKey())
                        .addValue("test", s.testCertificate())
                        .addValue("auditor", auditor.getCurrentAuditor().orElse(null)));
    }

    @Override
    public List<Signature> of(UUID contractId, int revision) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ordering.sales_contract_signature WHERE contract_id = :contract "
                + "AND revision = :revision ORDER BY party", new MapSqlParameterSource("contract", contractId)
                .addValue("revision", revision), SIGNATURE);
    }

    @Override
    public Optional<Signature> find(UUID contractId, int revision, Party party) {
        return of(contractId, revision).stream().filter(s -> s.party() == party).findFirst();
    }

    @Override
    public void sealWithCode(UUID contractId, String code) {
        int changed = jdbc.update("UPDATE ordering.sales_contract SET verification_code = :code "
                + "WHERE id = :contract AND verification_code IS NULL", new MapSqlParameterSource("code", code)
                .addValue("contract", contractId));
        if (changed != 1) {
            throw new IllegalStateException("Contract " + contractId + " has a verification code already");
        }
    }

    @Override
    public boolean codeExists(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ordering.sales_contract "
                + "WHERE verification_code = :code)", new MapSqlParameterSource("code", code), Boolean.class));
    }

    @Override
    public Optional<String> codeOf(UUID contractId) {
        return jdbc.query("SELECT verification_code FROM ordering.sales_contract WHERE id = :contract "
                + "AND verification_code IS NOT NULL", new MapSqlParameterSource("contract", contractId),
                (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    @Override
    public Optional<Verified> findByCode(String code) {
        return jdbc.query("""
                SELECT c.contract_number, c.revision, c.seller_name, c.buyer_name, c.status, c.signed_at,
                       k.document_sha256, k.test_certificate,
                       COALESCE(c.customer_signed_at, u.signed_at) AS customer_signed_at
                  FROM ordering.sales_contract c
                  JOIN ordering.sales_contract_signature k
                    ON k.contract_id = c.id AND k.revision = c.revision AND k.party = :company
                  LEFT JOIN ordering.sales_contract_signature u
                    ON u.contract_id = c.id AND u.revision = c.revision AND u.party = :customer
                 WHERE c.verification_code = :code""", new MapSqlParameterSource("code", code)
                        .addValue("company", Party.COMPANY.name()).addValue("customer", Party.CUSTOMER.name()),
                (rs, n) -> new Verified(rs.getString("contract_number"), rs.getInt("revision"),
                        rs.getString("seller_name"), rs.getString("buyer_name"), rs.getString("status"),
                        instant(rs.getTimestamp("customer_signed_at")), instant(rs.getTimestamp("signed_at")),
                        rs.getString("document_sha256"), rs.getBoolean("test_certificate"))).stream().findFirst();
    }

    @Override
    public Optional<String> companyFileKeyByCode(String code) {
        return jdbc.query("""
                SELECT k.file_key FROM ordering.sales_contract c
                  JOIN ordering.sales_contract_signature k
                    ON k.contract_id = c.id AND k.revision = c.revision AND k.party = :company
                 WHERE c.verification_code = :code""", new MapSqlParameterSource("code", code)
                        .addValue("company", Party.COMPANY.name()),
                (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    @Override
    public Optional<OutsideDocument> outsideDocument(UUID contractId) {
        return jdbc.query("SELECT outside_document_key, outside_document_sha256, outside_document_length, outside_page_key "
                + "FROM ordering.sales_contract WHERE id = :contract AND outside_document_key IS NOT NULL",
                new MapSqlParameterSource("contract", contractId), (rs, n) -> new OutsideDocument(
                        rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4))).stream().findFirst();
    }

    @Override
    public boolean recordOutsideDocument(UUID contractId, OutsideDocument document) {
        return jdbc.update("UPDATE ordering.sales_contract SET outside_document_key = :key, "
                + "outside_document_sha256 = :sha, outside_document_length = :length, outside_page_key = :page "
                + "WHERE id = :contract AND outside_document_key IS NULL", new MapSqlParameterSource()
                .addValue("key", document.key()).addValue("sha", document.sha256())
                .addValue("length", document.length()).addValue("page", document.pageKey())
                .addValue("contract", contractId)) == 1;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
