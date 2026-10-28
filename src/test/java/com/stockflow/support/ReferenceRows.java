package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;

import java.util.UUID;

/**
 * Minimal rows for the tables other modules point at, for persistence tests on an empty database.
 *
 * <p>Since ADR-0007 a reference to another module's row is a foreign key: a design draft's
 * {@code customer_id}, its owner and reviewer, a customer's {@code user_id} must exist. A test that
 * invents {@code Identifiers.newId()} for them is refused at flush. These insert the smallest valid
 * row with native SQL, so a test of one module does not need another module's service wired up.
 * The user cannot sign in: its password hash is not a hash.</p>
 */
public final class ReferenceRows {

    private ReferenceRows() {
    }

    /** An {@code identity.app_user} row; returns its id. */
    public static UUID user(EntityManager em) {
        UUID id = Identifiers.newId();
        em.createNativeQuery("""
                        INSERT INTO identity.app_user (id, username, email, password_hash, status, version, created_at)
                        VALUES (?1, ?2, ?3, '!test-no-login', 'ACTIVE', 0, NOW())""")
                .setParameter(1, id)
                .setParameter(2, "test-" + id)
                .setParameter(3, "test-" + id + "@example.com")
                .executeUpdate();
        return id;
    }

    /** A {@code customer.customer} row with no account; returns its id. */
    public static UUID customer(EntityManager em) {
        UUID id = Identifiers.newId();
        em.createNativeQuery("""
                        INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                        VALUES (?1, 'Test customer', ?2, 'ACTIVE', 0, NOW())""")
                .setParameter(1, id)
                .setParameter(2, "customer-" + id + "@example.com")
                .executeUpdate();
        return id;
    }

    /** A product and its default variant, as product creation leaves them. */
    public record ProductRow(UUID productId, UUID variantId, String sku) {
    }

    /**
     * A DRAFT {@code product.products} row with its default ACTIVE variant; the variant insert creates
     * the SKU's inventory item (trigger). Since C1, a design draft's {@code product_id} and an order
     * line's {@code sku} are foreign keys to these.
     */
    public static ProductRow product(EntityManager em) {
        UUID productId = Identifiers.newId();
        UUID variantId = Identifiers.newId();
        String code = "T" + productId.toString().replace("-", "").substring(20).toUpperCase();
        em.createNativeQuery("""
                        INSERT INTO product.products (id, code, name, slug, status, version, created_at)
                        VALUES (?1, ?2, 'Test product', ?3, 'DRAFT', 0, NOW())""")
                .setParameter(1, productId)
                .setParameter(2, code)
                .setParameter(3, code.toLowerCase())
                .executeUpdate();
        em.createNativeQuery("""
                        INSERT INTO product.variants (id, product_id, sku, name, status, is_default,
                                                      attribute_signature, position, version, created_at)
                        VALUES (?1, ?2, ?3, 'Test product', 'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW())""")
                .setParameter(1, variantId)
                .setParameter(2, productId)
                .setParameter(3, code)
                .executeUpdate();
        return new ProductRow(productId, variantId, code);
    }

    /**
     * An APPROVED {@code product.products} row, approved by someone other than who submitted it, with its default
     * ACTIVE variant: a SKU a purchase order line may take (kltn-docs 02 BR-01, SCRUM-461). {@link #product} is a DRAFT,
     * which a purchase order refuses.
     */
    public static ProductRow approvedProduct(EntityManager em) {
        ProductRow row = product(em);
        UUID submitter = user(em);
        UUID approver = user(em);
        em.createNativeQuery("""
                        UPDATE product.products SET status = 'APPROVED', submitted_by = ?1, submitted_at = NOW(),
                               approved_by = ?2, approved_at = NOW() WHERE id = ?3""")
                .setParameter(1, submitter)
                .setParameter(2, approver)
                .setParameter(3, row.productId())
                .executeUpdate();
        return row;
    }

    /** Every fixture order number starts with this, after the date: real numbers never reach 9 followed by 11 digits. */
    public static final String ORDER_NUMBER_INFIX = "-9";

    /**
     * A well-formed order number ({@code SO-yyyyMMdd-NNNNNN...}, what {@code OrderNumber} accepts - the reporting
     * rebuild and the order API parse every order in the table) that is still recognisable as a fixture's:
     * {@code SO-<placed day>-9<11 digits from the id>}.
     */
    static String orderNumber(UUID order) {
        long digits = Long.remainderUnsigned(order.getLeastSignificantBits(), 100_000_000_000L);
        return "SO-%s%s%011d".formatted(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), ORDER_NUMBER_INFIX, digits);
    }

    /**
     * A placed order with one line of {@code sku}, in {@code status}, on credit (the payment term every status
     * accepts); {@code releasedBy} and {@code warehouseId} are what an order that has been released needs.
     * Returns the order id. For tests of what refers to a SKU.
     */
    public static UUID orderWithLine(EntityManager em, String sku, String status, UUID customerId, UUID releasedBy,
                                     UUID warehouseId) {
        UUID order = Identifiers.newId();
        boolean released = status.equals("IN_PRODUCTION") || status.equals("READY_TO_FULFILL");
        boolean cancelled = status.equals("CANCELLED");
        em.createNativeQuery("""
                        INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                            currency, placed_at, payment_term, credit_term_days, released_at, released_by, warehouse_id,
                            cancellation_reason, cancellation_reason_code, hold_reason, shipped_at, delivered_at,
                            completed_at, version, created_at)
                        VALUES (?1, ?2, ?3, ?4, ?5, 1000, 'VND', NOW(), 'CREDIT', 30, ?6, ?7, ?8, ?9, ?10,
                                CASE WHEN ?5 = 'ON_HOLD' THEN 'CREDIT' END,
                                CASE WHEN ?5 IN ('SHIPPED', 'DELIVERY_FAILED', 'DELIVERED', 'COMPLETED') THEN NOW() END,
                                CASE WHEN ?5 IN ('DELIVERED', 'COMPLETED') THEN NOW() END,
                                CASE WHEN ?5 = 'COMPLETED' THEN NOW() END, 0, NOW())""")
                .setParameter(1, order)
                .setParameter(2, orderNumber(order))
                .setParameter(3, customerId)
                .setParameter(4, Identifiers.newId())
                .setParameter(5, status)
                .setParameter(6, released ? java.sql.Timestamp.from(java.time.Instant.now()) : null)
                .setParameter(7, released ? releasedBy : null)
                .setParameter(8, released ? warehouseId : null)
                .setParameter(9, cancelled ? "x" : null)
                .setParameter(10, cancelled ? "OTHER" : null)
                .executeUpdate();
        em.createNativeQuery("INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                        + "VALUES (?1, ?2, ?3, 1, 1000, 'VND')")
                .setParameter(1, Identifiers.newId())
                .setParameter(2, order)
                .setParameter(3, sku)
                .executeUpdate();
        return order;
    }
}
