package com.stockflow.order.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.QuoteStatusChanged;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteLine;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Decision D-1 (2026-11-25, keeping the 2026-10-12 decision that Sales price a quote without approval): when a quote is
 * sent, every carton line priced more than {@code stockflow.order.quote.discount-alert-percent} below the customer's
 * own price at the line's cartons is told to the sales managers in the in-app inbox, once per quote revision. It only
 * tells them; nothing is held or refused, and the customer still accepts or not.
 *
 * <p>The managers are whoever holds {@value #MANAGES_SALES} (the sales manager's own permission in the role matrix,
 * which the System Admin also holds), so who hears follows the matrix as it is edited. A line without a price of the customer's own, below its minimum,
 * or a legacy one has nothing to compare with and is skipped. After the send commits, asynchronously: the inbox is
 * idempotent per recipient and key, so a replayed event posts nothing new.</p>
 */
@Component
class QuoteDiscountAlerts {

    private static final Logger log = LoggerFactory.getLogger(QuoteDiscountAlerts.class);

    static final String MANAGES_SALES = "customer-status:APPROVE";
    private static final int MAX_BODY = 1000;

    private final QuoteRepository quotes;
    private final CatalogService catalog;
    private final IdentityService identities;
    private final Inbox inbox;
    private final BigDecimal percent;

    QuoteDiscountAlerts(QuoteRepository quotes, CatalogService catalog, IdentityService identities, Inbox inbox,
                        @Value("${stockflow.order.quote.discount-alert-percent:10}") BigDecimal percent) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new IllegalArgumentException("stockflow.order.quote.discount-alert-percent must be in [0, 100)");
        }
        this.quotes = quotes;
        this.catalog = catalog;
        this.identities = identities;
        this.inbox = inbox;
        this.percent = percent;
    }

    /** A line priced below the floor: what it was quoted at and what the customer's own price is there. */
    record Below(String sku, int cartons, BigDecimal quoted, BigDecimal own) {
    }

    @ApplicationModuleListener
    public void on(QuoteStatusChanged event) {
        if (!QuoteStatus.SENT.name().equals(event.toStatus())) {
            return;
        }
        Optional<Quote> found = quotes.findById(event.quoteId());
        if (found.isEmpty()) {
            return;
        }
        Quote quote = found.get();
        if (quote.revision() != event.revision()) {
            // Revised between the send and this listener: its lines are no longer the ones sent, and the customer
            // cannot accept while a revision is drafted. The revision that replaces it is checked when it is sent.
            log.info("Quote {} revision {} is now revision {}; its discount is checked when that one is sent",
                    LogSafe.text(quote.quoteNumber()), event.revision(), quote.revision());
            return;
        }
        List<Below> below = below(quote);
        if (below.isEmpty()) {
            return;
        }
        List<UUID> managers = identities.notificationRecipientsHolding(MANAGES_SALES, null).stream()
                .map(StaffContact::userId).toList();
        if (managers.isEmpty()) {
            log.warn("Quote {} revision {}: {} line(s) more than {}% below the customer's own price and nobody holds {}"
                            + " - nobody was told", LogSafe.text(quote.quoteNumber()), event.revision(), below.size(),
                    percent.toPlainString(), MANAGES_SALES);
            return;
        }
        int told = inbox.post(managers, message(quote, event.revision(), below));
        log.info("Quote {} revision {}: {} line(s) more than {}% below the customer's own price, {} manager(s) newly told",
                LogSafe.text(quote.quoteNumber()), event.revision(), below.size(), percent.toPlainString(), told);
    }

    /** The carton lines quoted more than the threshold below the customer's own price at their cartons. */
    List<Below> below(Quote quote) {
        BigDecimal floorShare = BigDecimal.ONE.subtract(percent.movePointLeft(2));
        List<Below> below = new ArrayList<>();
        for (QuoteLine line : quote.offer().lines()) {
            if (line.legacy() || !line.priced() || line.sku() == null) {
                continue;
            }
            int cartons = line.quantity();
            Optional<BigDecimal> own = catalog.customerPrice(quote.customerId(), line.sku().code())
                    .filter(schedule -> cartons >= schedule.moq())
                    .map(schedule -> ownAt(schedule, cartons));
            if (own.isEmpty() || cartons < line.moq()) {
                continue;
            }
            BigDecimal quoted = line.priceFor(cartons).amount();
            if (quoted.compareTo(own.get().multiply(floorShare)) < 0) {
                below.add(new Below(line.sku().code(), cartons, quoted, own.get()));
            }
        }
        return below;
    }

    private static BigDecimal ownAt(PriceSchedule schedule, int cartons) {
        BigDecimal price = schedule.tiers().getFirst().unitPrice().amount();
        for (PriceSchedule.Tier tier : schedule.tiers()) {
            if (tier.minQuantity() <= cartons) {
                price = tier.unitPrice().amount();
            }
        }
        return price;
    }

    private InboxMessage message(Quote quote, int revision, List<Below> below) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        NumberFormat money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi"));
        StringBuilder body = new StringBuilder(texts.getString("inbox.quote.discount.body")
                .formatted(percent.stripTrailingZeros().toPlainString()));
        for (Below line : below) {
            String one = "\n" + texts.getString("inbox.quote.discount.line").formatted(line.sku(), line.cartons(),
                    money.format(line.quoted().setScale(0, RoundingMode.HALF_UP)),
                    money.format(line.own().setScale(0, RoundingMode.HALF_UP)));
            if (body.length() + one.length() > MAX_BODY - 4) {
                body.append("\n...");
                break;
            }
            body.append(one);
        }
        return new InboxMessage("QUOTE", InboxSeverity.WARN,
                texts.getString("inbox.quote.discount.title").formatted(quote.quoteNumber()), body.toString(),
                "/admin/sales/quotes/" + quote.id(), "QUOTE", quote.id().toString(),
                "quote-discount:%s:%d".formatted(quote.id(), revision));
    }
}
