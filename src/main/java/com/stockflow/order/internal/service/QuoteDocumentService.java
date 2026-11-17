package com.stockflow.order.internal.service;

import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.internal.domain.QuoteStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A quote drawn as a PDF (kltn-docs 14 §4.1): the file a wholesale customer takes into its own approval before it
 * accepts, and Sales sends or prints. Whoever may read the quote may print it, through the same rule: the quote is read
 * with {@link Quotes#get}, so a portal customer gets only their own customer's quote once sent - another customer's and
 * Sales' working draft answer 404, as reading them does - and sees the offer they were sent, not a revision Sales
 * reopened and dropped.
 *
 * <p>The seller is the company of {@code stockflow.seller.*}, as on the contract; a deployment without it refuses
 * (503 {@code SELLER_PROFILE_UNAVAILABLE}) rather than print an offer from nobody. The buyer is the customer's legal
 * profile when recorded (the contract will name it so), else its name and tax code: a quote comes before the contract,
 * and its customer may not have the profile yet.</p>
 */
@Service
@Transactional(readOnly = true)
public class QuoteDocumentService {

    /** The PDF and the name it is offered under: the quote's number. */
    public record Pdf(String fileName, byte[] content) {
    }

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final Quotes quotes;
    private final QuoteDocument document;
    private final SellerProperties seller;
    private final CustomerLegalProfiles legalProfiles;
    private final CustomerService customers;
    private final Clock clock;

    QuoteDocumentService(Quotes quotes, QuoteDocument document, SellerProperties seller,
                         CustomerLegalProfiles legalProfiles, CustomerService customers, Clock clock) {
        this.quotes = quotes;
        this.document = document;
        this.seller = seller;
        this.legalProfiles = legalProfiles;
        this.customers = customers;
        this.clock = clock;
    }

    /**
     * @throws com.stockflow.common.error.NotFoundException no such quote, or not one this portal user may see
     * @throws BusinessException {@code SELLER_PROFILE_UNAVAILABLE}
     */
    public Pdf document(CurrentUser user, UUID quoteId) {
        Quotes.QuoteView quote = quotes.get(user, quoteId);
        if (!seller.complete()) {
            throw new BusinessException(ErrorCode.SELLER_PROFILE_UNAVAILABLE,
                    "stockflow.seller.* is not configured in this deployment");
        }
        SalesContracts.Party sellerParty = new SalesContracts.Party(seller.name(), seller.taxCode(), seller.address(),
                seller.representative(), seller.representativeTitle());
        // Dated the day it was sent; a draft (a first one, or a revision reopened) is dated the day it is printed.
        LocalDate dated = LocalDate.ofInstant(quote.status() == QuoteStatus.DRAFT || quote.sentAt() == null
                ? clock.instant() : quote.sentAt(), VIETNAM);
        return new Pdf(quote.quoteNumber() + ".pdf",
                document.render(new QuoteDocument.Facts(quote, sellerParty, buyer(quote.customerId()), dated)));
    }

    private SalesContracts.Party buyer(UUID customerId) {
        return legalProfiles.legalProfile(customerId)
                .map(p -> new SalesContracts.Party(p.companyName(), p.taxCode(), p.registeredAddress(),
                        p.representativeName(), p.representativeTitle()))
                .orElseGet(() -> customers.findById(customerId)
                        .map(c -> new SalesContracts.Party(c.fullName(), c.taxCode(), null, null, null))
                        .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)));
    }
}
