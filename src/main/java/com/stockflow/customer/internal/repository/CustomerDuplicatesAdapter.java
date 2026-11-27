package com.stockflow.customer.internal.repository;

import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.internal.domain.CustomerDuplicates;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link CustomerDuplicates} in one SQL statement. The name is folded the same way on both sides, in SQL, so the rule
 * cannot drift between Java and the database: accents removed (the crossed d read as d), lower case, the legal-form
 * words dropped, everything but letters and digits dropped.
 */
@Repository
class CustomerDuplicatesAdapter implements CustomerDuplicates {

    /** The legal-form words set aside when names are compared, accents already removed. */
    private static final String LEGAL_FORMS = "\\m(cong ty|cty|tnhh|co phan|cp|mtv|mot thanh vien|trach nhiem huu han"
            + "|dntn|doanh nghiep tu nhan|hkd|ho kinh doanh)\\M";

    private final NamedParameterJdbcTemplate jdbc;

    CustomerDuplicatesAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Match> find(String taxCode, String phone, String email, String name, int limit) {
        String folded = folded("c.full_name");
        String wanted = folded("CAST(:name AS text)");
        String sql = """
                SELECT * FROM (
                    SELECT c.id, c.full_name, c.sales_owner_id, c.sales_owner_state,
                           (CAST(:taxCode AS varchar) IS NOT NULL AND c.tax_code = :taxCode) AS on_tax_code,
                           (CAST(:phone AS varchar) IS NOT NULL AND c.phone = :phone) AS on_phone,
                           (CAST(:email AS varchar) IS NOT NULL AND lower(c.email) = :email) AS on_email,
                           (CAST(:name AS varchar) IS NOT NULL AND WANTED <> '' AND FOLDED = WANTED) AS on_name
                      FROM customer.customer c) m
                 WHERE on_tax_code OR on_phone OR on_email OR on_name
                 ORDER BY on_tax_code DESC, full_name, id
                 LIMIT :limit""".replace("WANTED", wanted).replace("FOLDED", folded);
        return jdbc.query(sql,
                new MapSqlParameterSource("taxCode", taxCode).addValue("phone", phone).addValue("email", email)
                        .addValue("name", name).addValue("limit", limit).addValue("legalForms", LEGAL_FORMS),
                (rs, row) -> {
                    Set<On> on = EnumSet.noneOf(On.class);
                    if (rs.getBoolean("on_tax_code")) on.add(On.TAX_CODE);
                    if (rs.getBoolean("on_phone")) on.add(On.PHONE);
                    if (rs.getBoolean("on_email")) on.add(On.EMAIL);
                    if (rs.getBoolean("on_name")) on.add(On.NAME);
                    return new Match(rs.getObject("id", UUID.class), rs.getString("full_name"), on,
                            rs.getObject("sales_owner_id", UUID.class),
                            SalesOwnerState.valueOf(rs.getString("sales_owner_state")));
                });
    }

    private static String folded(String expression) {
        return "regexp_replace(regexp_replace(lower(public.unaccent(" + expression + ")), CAST(:legalForms AS text), "
                + "' ', 'g'), '[^a-z0-9]+', '', 'g')";
    }
}
