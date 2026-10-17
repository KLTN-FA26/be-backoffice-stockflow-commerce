package com.stockflow.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Plain JDBC for the migration upgrade tests, which run against a database Flyway migrated only part of the way, so
 * no Spring context and no repository exist. Values are always bound, never spliced into the SQL text, and every
 * statement and result set is closed: a test that seeds hundreds of rows on one connection otherwise leaks a
 * server-side cursor per row.
 */
public final class RawSql {

    private RawSql() {
    }

    /** Runs one statement with {@code values} bound to its {@code ?} placeholders; the number of rows it changed. */
    public static int update(Connection db, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, values)) {
            return statement.executeUpdate();
        }
    }

    /** The first column of the first row, as a number. */
    public static long number(Connection db, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, values); ResultSet row = statement.executeQuery()) {
            assertThat(row.next()).as("a row from: %s", sql).isTrue();
            return row.getLong(1);
        }
    }

    /** The first column of the first row, as text; null for SQL NULL. */
    public static String text(Connection db, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, values); ResultSet row = statement.executeQuery()) {
            assertThat(row.next()).as("a row from: %s", sql).isTrue();
            return row.getString(1);
        }
    }

    /** The first column of every row, in the order the query returns them. */
    public static List<String> texts(Connection db, String sql, Object... values) throws SQLException {
        List<String> texts = new ArrayList<>();
        try (PreparedStatement statement = prepare(db, sql, values); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                texts.add(rows.getString(1));
            }
        }
        return texts;
    }

    /** A two-column query as a map from the first column (text) to the second (a number). */
    public static Map<String, Long> numbersByText(Connection db, String sql, Object... values) throws SQLException {
        Map<String, Long> numbers = new HashMap<>();
        try (PreparedStatement statement = prepare(db, sql, values); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                numbers.put(rows.getString(1), rows.getLong(2));
            }
        }
        return numbers;
    }

    /**
     * The database itself refuses the statement as an integrity violation (SQLSTATE class 23: a CHECK, a foreign or
     * unique key, a NOT NULL). Any other failure - a typo, a missing column - is not the rule under test refusing
     * it, and fails the test instead of passing it.
     */
    public static void refused(Connection db, String rule, String sql, Object... values) {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> update(db, sql, values));
        if (refusal == null) {
            throw new AssertionError("%s is refused, but the database accepted: %s".formatted(rule, sql));
        }
        assertThat(refusal.getSQLState()).as("%s: %s", rule, refusal.getMessage()).startsWith("23");
    }

    private static PreparedStatement prepare(Connection db, String sql, Object... values) throws SQLException {
        PreparedStatement statement = db.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement;
        } catch (SQLException e) {
            statement.close();
            throw e;
        }
    }
}
