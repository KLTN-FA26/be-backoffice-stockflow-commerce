package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.order.internal.domain.SalesFollowUps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tells the salesperson handling a contract when the customer keeps it waiting (kltn-docs 14 §6: "Khách đã ký nhưng
 * chậm thanh toán đợt ON_SIGNING - Sale nhắc qua chat; quá lâu thì Sale huỷ đơn"): a contract sent and not signed for
 * {@code stockflow.order.follow-up.unsigned-contract-after}, an order signed and waiting for the installment on signing
 * for {@code stockflow.order.follow-up.unpaid-order-after} - three days each unless configured. Nothing is cancelled or
 * expired here: whether to chase the customer or cancel is Sales' call, and 14 gives neither a deadline.
 *
 * <p>One inbox notice per contract and per order ({@code dedupeKey}): the inbox posts a key to a recipient once, so the
 * hourly run tells nobody twice, and a run that died half way is simply run again. The salesperson is who sent the
 * contract - orders have no assigned salesperson of their own - or, for a contract with none recorded, who recorded it
 * signed. System text only: numbers, codes and days.</p>
 */
@Service
@Transactional
public class SalesFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(SalesFollowUpService.class);

    static final String CATEGORY = "SALES_FOLLOW_UP";
    /** More than a sales team leaves waiting at once; the newest first, so the ones just past the line always fit. */
    static final int BATCH_SIZE = 200;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SalesFollowUps followUps;
    private final Inbox inbox;
    private final Clock clock;
    private final Duration unsignedAfter;
    private final Duration unpaidAfter;

    SalesFollowUpService(SalesFollowUps followUps, Inbox inbox, Clock clock,
                         @Value("${stockflow.order.follow-up.unsigned-contract-after:P3D}") Duration unsignedAfter,
                         @Value("${stockflow.order.follow-up.unpaid-order-after:P3D}") Duration unpaidAfter) {
        if (unsignedAfter.isNegative() || unsignedAfter.isZero() || unpaidAfter.isNegative() || unpaidAfter.isZero()) {
            throw new IllegalArgumentException("stockflow.order.follow-up.* must be positive durations");
        }
        this.followUps = followUps;
        this.inbox = inbox;
        this.clock = clock;
        this.unsignedAfter = unsignedAfter;
        this.unpaidAfter = unpaidAfter;
    }

    /** What one run did: the contracts and orders found waiting, and how many recipients were told for the first time. */
    public record Reminded(int unsignedContracts, int unpaidOrders, int newlyTold) {
    }

    /** Posts the notices due now; those already posted are not posted again. */
    public Reminded remind() {
        Instant now = clock.instant();
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<SalesFollowUps.UnsignedContract> unsigned = followUps.unsignedSince(now.minus(unsignedAfter), BATCH_SIZE);
        List<SalesFollowUps.UnpaidOrder> unpaid = followUps.unpaidSince(now.minus(unpaidAfter), BATCH_SIZE);
        int told = 0;
        for (SalesFollowUps.UnsignedContract contract : unsigned) {
            told += inbox.post(List.of(contract.salesUserId()), new InboxMessage(CATEGORY, InboxSeverity.WARN,
                    texts.getString("inbox.sales.follow-up.contract-unsigned.title")
                            .formatted(contract.contractNumber(), unsignedAfter.toDays()),
                    texts.getString("inbox.sales.follow-up.contract-unsigned.body").formatted(contract.contractNumber(),
                            contract.orderNumber(), DAY.format(BusinessCalendar.date(contract.sentAt()))),
                    "/admin/orders/" + contract.orderId(), "SALES_CONTRACT", contract.contractId().toString(),
                    "contract-unsigned:" + contract.contractId()));
        }
        for (SalesFollowUps.UnpaidOrder order : unpaid) {
            told += inbox.post(List.of(order.salesUserId()), new InboxMessage(CATEGORY, InboxSeverity.WARN,
                    texts.getString("inbox.sales.follow-up.order-unpaid.title")
                            .formatted(order.orderNumber(), unpaidAfter.toDays()),
                    texts.getString("inbox.sales.follow-up.order-unpaid.body").formatted(order.orderNumber(),
                            order.contractNumber(), DAY.format(BusinessCalendar.date(order.signedAt()))),
                    "/admin/orders/" + order.orderId(), "ORDER", order.orderId().toString(),
                    "order-unpaid:" + order.contractId()));
        }
        if (told > 0) {
            log.info("Sales follow-up: {} contract(s) unsigned, {} order(s) unpaid, {} notice(s) posted",
                    unsigned.size(), unpaid.size(), told);
        }
        return new Reminded(unsigned.size(), unpaid.size(), told);
    }
}
