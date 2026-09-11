package com.stockflow.common.persistence;

import com.stockflow.common.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * How {@link Money} is stored: an amount column and a currency column, always together.
 *
 * <p>Two columns rather than one, because an amount without its currency is not money — and a
 * single-column {@code AttributeConverter} could only store one of them. The pair is bound together
 * here so no entity can define one and forget the other.</p>
 *
 * <h2>Using it</h2>
 *
 * <p>The column names come from {@code @AttributeOverride} on the owning entity, because a table
 * usually has more than one money field and they cannot all be called {@code amount}:</p>
 *
 * <pre>
 * &#64;Embedded
 * &#64;AttributeOverrides({
 *         &#64;AttributeOverride(name = "amount", column = &#64;Column(name = "total_amount", nullable = false)),
 *         &#64;AttributeOverride(name = "currency", column = &#64;Column(name = "currency", nullable = false))
 * })
 * private MoneyEmbeddable total;
 * </pre>
 *
 * <p>Migrations should declare the amount as {@code NUMERIC(19, 4)} and the currency as
 * {@code VARCHAR(3)} — not {@code CHAR(3)}, which Hibernate's {@code ddl-auto: validate} reports as
 * a type mismatch against a {@code String} field.</p>
 *
 * <h2>Currency is stored as a code, not a {@code Currency}</h2>
 *
 * <p>Keeping the column a plain {@code String} means no {@code AttributeConverter} is involved and
 * nothing can auto-apply itself to unrelated fields. {@link #toMoney()} does the parsing, once, at
 * the boundary.</p>
 */
@Embeddable
public class MoneyEmbeddable {

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Required by JPA. */
    protected MoneyEmbeddable() {
    }

    private MoneyEmbeddable(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public static MoneyEmbeddable of(Money money) {
        java.util.Objects.requireNonNull(money, "money");
        return new MoneyEmbeddable(money.amount(), money.currency().getCurrencyCode());
    }

    /** For nullable money columns — an optional discount, a not-yet-quoted freight charge. */
    public static MoneyEmbeddable ofNullable(Money money) {
        return money == null ? null : of(money);
    }

    /**
     * @throws IllegalStateException if the row holds an amount with no currency, which means a
     *                               migration added one column and not the other
     */
    public Money toMoney() {
        if (currency == null) {
            throw new IllegalStateException(
                    "Stored amount %s has no currency - the currency column was not populated"
                            .formatted(amount));
        }
        return new Money(amount, Currency.getInstance(currency));
    }

    /** Null-safe counterpart of {@link #ofNullable}, for reading. */
    public static Money toMoney(MoneyEmbeddable stored) {
        return stored == null ? null : stored.toMoney();
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MoneyEmbeddable that)) {
            return false;
        }
        return java.util.Objects.equals(amount, that.amount)
                && java.util.Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
