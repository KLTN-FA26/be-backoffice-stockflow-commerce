package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * ROLE-6: what a customer pays for a SKU - "Công ty ABC lấy 500 thùng ly kraft 12oz thì giá bao nhiêu, MOQ bao
 * nhiêu?", the first question of every quote (kltn-docs 14 step 2). The answer is the customer's own price row, which
 * a quote line starts from, exactly as the customer-prices screen shows it
 * ({@code GET /api/v1/catalog/customer-prices/{customerId}/{sku}}).
 *
 * <p><b>The price a quote line starts from, and which one it is</b> (kltn-docs 13 BR-04 as changed on 2026-11-26,
 * 14 step 2): the customer's own price for its SKU; without one, the standard price of the variant (quy cach) the SKU
 * was drawn on, which the Sales Manager keeps for a usual print; without either, none. The same order the quote follows
 * ({@code QuoteService.startingPrice}), read the same way: the variant behind the SKU through {@code product :: api}
 * {@link CustomerSkus}, its price through {@code CatalogService.standardPrice}. The answer says which kind it is
 * ({@code priceKind}), because a standard price is not what the customer negotiated: a design printed in more colours
 * is priced up from it, and a quote priced too far below it waits for the Sales Manager (14 BR-10). Asked about a
 * catalogue variant itself, the tool answers with its standard price and says it is one: the customer is quoted for its
 * own SKU drawn on it. Another customer's SKU gets no price at all (13 BR-02: this customer cannot be quoted it).
 * The standard price is the standard-price screen's ({@code catalog-standard-prices:READ}): a caller without that
 * permission is told one may exist, never what it is. There is no group or list price behind either. The {@code api} methods pass the SKU on as given while the screen trims and
 * upper-cases it, so the tool normalises it the same way ({@link Sku}): "eval-lid-90" would otherwise find no row.</p>
 *
 * <p><b>The tier for a quantity</b> is the last tier starting at or below it. Under the first tier the quantity is
 * below the customer's minimum. Worked out here, so the model never picks a tier from a table itself.</p>
 *
 * <p><b>The customer</b> is resolved by {@link CustomerMatch}: a caller who may list customers is offered the names a
 * fragment matches; one who may not names the customer exactly. Only the company name and the account status come
 * back - no contact, e-mail, phone or tax code - and the name goes to the model as an alias (ADR-0015).</p>
 *
 * <p><b>What the numbers count.</b> Every price counts whole cartons now - a customer price names only the customer's
 * own SKU ({@code catalog.check_customer_price_own_sku}) and a standard price counts cartons by definition - while
 * users count in pieces: "10.000 ly" passed as the quantity was read as 10,000 cartons, fifty times the order. So the
 * answer carries the catalog's {@code measure} (CARTON; UNIT survives only for a price kept on a stock variant before
 * 4B-2, which the catalog can no longer hold) and, when the SKU has one, its carton's pieces ({@code unitsPerCarton},
 * the carton unit a quote is written in: {@code UomConversion.cartonOf}), and the quantity argument says in what it is
 * counted.
 * The pack size is read through {@code inventory :: api} as the stock tools read it ({@link Packing}): how a figure the
 * user may see is counted, not data of its own; unreadable, it is left out and the price still answers.</p>
 *
 * <p><b>No validity dates.</b> A price has none in this system; it applies until someone changes it, so there is
 * nothing to report.</p>
 */
@Component
class GetCustomerPriceTool implements AssistantTool {

    private static final Logger log = LoggerFactory.getLogger(GetCustomerPriceTool.class);

    /** A quantity beyond any order of cups and lids; larger is a typing error, not a question. */
    static final int MAX_QUANTITY = 100_000_000;

    private static final ToolSpec SPEC = new ToolSpec("getCustomerPrice",
            "The price a quote line for this customer and SKU starts from, and which kind it is (priceKind): "
                    + "CUSTOMER_PRICE, the customer's own negotiated price for its SKU; or, when the customer has none, "
                    + "STANDARD_PRICE, the standard price of the variant the SKU was drawn on (standardPriceOf names "
                    + "that variant), kept by the Sales Manager for a usual print (a 1-2 colour logo). Always say which "
                    + "kind you give: a standard price is a reference, not a price agreed with this customer; a design "
                    + "in more colours is priced up from it, and a quote priced too far below it waits for the Sales "
                    + "Manager's approval. There is no list or group price behind either, so with neither the note says "
                    + "there is none. measure says what minQuantity, moq and quantity count: CARTON, whole cartons, "
                    + "for every current price (UNIT, pieces, only on an old price kept on a stock product); "
                    + "unitsPerCarton is the pieces in one carton of the SKU, when it has a carton. tiers: from "
                    + "minQuantity on, unitPrice per piece in currency; moq is the first tier's quantity, below which "
                    + "that price does not apply. Always name the measure with a quantity: '200 cartons (10,000 "
                    + "pieces)', never a bare number. Give a quantity to be told which tier applies (tierForQuantity, "
                    + "the last tier starting at or below it): when belowMoq is true there is no price for that "
                    + "quantity, so say the quantity is under the minimum and that Sales agree it separately. Never "
                    + "pick a tier yourself. Prices have no validity dates: they apply until changed. customerStatus is "
                    + "the customer account's: anything but ACTIVE means the customer can be neither quoted nor sold to "
                    + "now, so say so beside the price. The customer is named as the user named it: the company name, "
                    + "whole or in part, or its tax code. When it does not name exactly one customer, no price is "
                    + "returned and the note says what to ask; candidates, when given, are the customers that matched, "
                    + "by name: list them and ask which. When there is no price, or the SKU is not this customer's, the "
                    + "note says so. It returns no customer contact details. For a product named in words, call "
                    + "searchCatalog first for its SKU. When the user gives quantities per design or per line of one "
                    + "order, call once with their sum.",
            ToolSchema.object()
                    .string("customer", "The customer as the user named it, every word kept: the company name whole "
                            + "or in part, words such as Công ty, Cửa hàng or Khách hàng included, since they can be "
                            + "part of the registered name; or its tax code")
                    .string("sku", "The SKU exactly as the user or an earlier answer gave it")
                    .integer("quantity", "The total quantity of this SKU in the one quote asked about, every line "
                            + "counted together, as a quote reads the tier at that total, COUNTED IN THE PRICE'S "
                            + "MEASURE: whole cartons for a customer's own SKU, pieces only when an earlier answer for "
                            + "this customer and SKU said measure UNIT. A number of cups, lids or pieces ('10.000 ly', "
                            + "'5.000 cái') is never a number of cartons: when the user counted pieces and the measure "
                            + "is CARTON or not known yet, pass 0 first, then pass pieces divided by unitsPerCarton "
                            + "only when it divides exactly, saying so, and otherwise ask how many cartons. 1 to "
                            + MAX_QUANTITY + ", 0 when the user gave none")
                    .build());

    private final CatalogService catalog;
    private final CustomerService customers;
    private final CurrentUserProvider currentUsers;
    private final UomConversionService units;
    private final CustomerSkus customerSkus;

    GetCustomerPriceTool(CatalogService catalog, CustomerService customers, CurrentUserProvider currentUsers) {
        this(catalog, customers, currentUsers, null, null);
    }

    GetCustomerPriceTool(CatalogService catalog, CustomerService customers, CurrentUserProvider currentUsers,
                         UomConversionService units) {
        this(catalog, customers, currentUsers, units, null);
    }

    /**
     * @param customerSkus the customers' own SKUs, for the variant a SKU was drawn on; null (a test without it) answers
     *                     with the customer's own price only
     */
    @org.springframework.beans.factory.annotation.Autowired
    GetCustomerPriceTool(CatalogService catalog, CustomerService customers, CurrentUserProvider currentUsers,
                         UomConversionService units, CustomerSkus customerSkus) {
        this.catalog = catalog;
        this.customers = customers;
        this.currentUsers = currentUsers;
        this.units = units;
        this.customerSkus = customerSkus;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/catalog/customer-prices/{customerId}/{sku}";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.CUSTOMER_PRICES, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        CurrentUser user = currentUsers.current()
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        // The customer list is the back office's; a portal account holds customer-customers:READ for its own profile.
        ScopeGuards.requireBackOffice(user);
        String customerText = arguments.text("customer", 200);
        String sku = new Sku(arguments.text("sku", Sku.MAX_LENGTH)).code();
        int quantity = arguments.integer("quantity", 0, MAX_QUANTITY);
        CustomerMatch customer = CustomerMatch.find(customers, customerText,
                user.can(ToolPermissions.CUSTOMERS, Action.READ), arguments.pseudonyms());
        if (!customer.found()) {
            return Answer.noCustomer(sku, customer);
        }
        Integer asked = quantity == 0 ? null : quantity;
        Optional<PriceSchedule> own = catalog.customerPrice(customer.customerId(), sku);
        if (own.isPresent()) {
            return Answer.of(customer, own.get().sku(), own.get(), PriceKind.CUSTOMER_PRICE, null,
                    unitsPerCarton(own.get().sku()), asked, null);
        }
        if (customerSkus == null) {
            return Answer.noPrice(customer, sku);
        }
        Optional<CustomerSku> customerSku = customerSkus.find(sku);
        if (customerSku.isPresent() && !customer.customerId().equals(customerSku.get().ownerCustomerId())) {
            // 13 BR-02: another customer's SKU is not this customer's to be quoted; whose it is stays unsaid.
            return Answer.notTheirs(customer, sku);
        }
        if (!user.can(ToolPermissions.STANDARD_PRICES, Action.READ)) {
            // The standard price is the standard-price screen's: a caller who may not open it is not told it here.
            return Answer.noOwnPrice(customer, sku);
        }
        // The customer's own SKU: the standard price of the variant it was drawn on. A catalogue variant (no owner):
        // its own standard price, said to be one. An unknown code finds none.
        String variant = customerSku.map(CustomerSku::sourceSku).orElse(sku);
        Optional<PriceSchedule> standard = variant == null ? Optional.empty() : catalog.standardPrice(variant);
        if (standard.isEmpty()) {
            return Answer.noPrice(customer, sku);
        }
        return Answer.of(customer, sku, standard.get(), PriceKind.STANDARD_PRICE, standard.get().sku(),
                unitsPerCarton(sku), asked, customerSku.isPresent() ? null : variantNote(sku));
    }

    private static String variantNote(String sku) {
        return sku + " is a catalogue variant, not one of this customer's own SKUs: this is its standard price. A "
                + "quote is written for the customer's own SKU drawn on it, which the customer gets once its design is "
                + "approved.";
    }

    /**
     * The pieces in the SKU's carton, the unit a quote is written in ({@link UomConversion#cartonOf}); null when it has
     * none, or several and none the shipping one, or the units cannot be read: the price answers without it.
     */
    private Integer unitsPerCarton(String sku) {
        if (units == null) {
            return null;
        }
        try {
            return UomConversion.cartonOf(units.conversionsOf(List.of(new Sku(sku))).getOrDefault(sku, List.of()))
                    .map(UomConversion::factor).orElse(null);
        } catch (RuntimeException unreadable) {
            log.warn("Assistant price answer without the carton size of {}: the units could not be read", sku,
                    unreadable);
            return null;
        }
    }

    /** Which price the answer gives (13 BR-04 as changed on 2026-11-26), in the order a quote line starts from. */
    enum PriceKind {
        /** The customer's own price for its SKU: negotiated with this customer. */
        CUSTOMER_PRICE,
        /** The standard price of the variant behind the SKU: a reference for a usual print, not negotiated. */
        STANDARD_PRICE
    }

    /**
     * @param sku             the SKU asked about, as the catalog spells it
     * @param priceKind       which price it is; null without a price
     * @param standardPriceOf the variant whose standard price it is, when {@code priceKind} is STANDARD_PRICE
     * @param measure         what {@code moq}, the tiers' {@code minQuantity} and {@code quantity} count: CARTON or
     *                        UNIT ({@link PriceSchedule.Measure}); null without a price
     * @param unitsPerCarton  pieces in one carton of the SKU; null when it has no single carton unit or without a price
     * @param moq             the first tier's quantity: below it the customer has no price
     * @param tierForQuantity the tier {@code quantity} falls in; null without a quantity, or below the minimum
     * @param belowMoq        the quantity asked is under {@code moq}; null without a quantity
     * @param candidates      the customers the text matched, when it named none exactly
     * @param note            why there is no price, when there is none
     */
    record Answer(CustomerName customerName, String customerStatus, String sku, PriceKind priceKind,
                  String standardPriceOf, String measure, Integer unitsPerCarton, Integer moq,
                  String currency, List<Tier> tiers, Integer quantity, Tier tierForQuantity, Boolean belowMoq,
                  List<CustomerMatch.Candidate> candidates, String note) {

        static Answer of(CustomerMatch customer, String sku, PriceSchedule schedule, PriceKind kind,
                         String standardPriceOf, Integer unitsPerCarton, Integer quantity, String note) {
            List<Tier> tiers = schedule.tiers().stream()
                    .map(tier -> new Tier(tier.minQuantity(), tier.unitPrice().amount())).toList();
            Optional<Tier> applies = quantity == null ? Optional.empty() : tiers.stream()
                    .filter(tier -> tier.minQuantity() <= quantity)
                    .max(Comparator.comparingInt(Tier::minQuantity));
            String currency = schedule.tiers().isEmpty() ? null
                    : schedule.tiers().getFirst().unitPrice().currency().getCurrencyCode();
            return new Answer(customer.customerName(), customer.status(), sku, kind, standardPriceOf,
                    schedule.measure() == null ? null : schedule.measure().name(), unitsPerCarton, schedule.moq(),
                    currency, tiers, quantity, applies.orElse(null), quantity == null ? null : applies.isEmpty(),
                    List.of(), note);
        }

        static Answer noCustomer(String sku, CustomerMatch customer) {
            return new Answer(null, null, sku, null, null, null, null, null, null, List.of(), null, null, null,
                    customer.candidates(), customer.note());
        }

        static Answer noPrice(CustomerMatch customer, String sku) {
            return new Answer(customer.customerName(), customer.status(), sku, null, null, null, null, null, null,
                    List.of(), null, null, null, List.of(), "This customer has no price of its own for " + sku
                    + ", and no standard price stands behind it (none is set for the variant it was drawn on); there "
                    + "is no list or group price either: Sales price the quote line themselves. The SKU may also be "
                    + "mistyped; say so and suggest checking the code, never a price.");
        }

        static Answer noOwnPrice(CustomerMatch customer, String sku) {
            return new Answer(customer.customerName(), customer.status(), sku, null, null, null, null, null, null,
                    List.of(), null, null, null, List.of(), "This customer has no price of its own for " + sku
                    + ". A standard price of the variant it was drawn on may stand behind it, but this account may not "
                    + "read standard prices (catalog-standard-prices:READ): say so, never a price.");
        }

        static Answer notTheirs(CustomerMatch customer, String sku) {
            return new Answer(customer.customerName(), customer.status(), sku, null, null, null, null, null, null,
                    List.of(), null, null, null, List.of(), sku + " is not one of this customer's own SKUs: this "
                    + "customer has no price of its own for it, no standard price applies, and it cannot be quoted it. "
                    + "Say so, and suggest checking the code; never say whose SKU it is.");
        }
    }

    /** From {@code minQuantity} on, {@code unitPrice} per piece. */
    record Tier(int minQuantity, BigDecimal unitPrice) {
    }
}
