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
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * ROLE-6: what a customer pays for a SKU - "Công ty ABC lấy 10.000 ly kraft 12oz thì giá bao nhiêu, MOQ bao nhiêu?",
 * the first question of every quote (kltn-docs 14 step 2). The answer is the price schedule a quote line starts from:
 * the customer's own tiers, else its group's price, else the base price, exactly as the customer-prices screen shows it
 * ({@code GET /api/v1/catalog/customer-prices/{customerId}/{sku}/schedule}).
 *
 * <p><b>The same schedule as that screen.</b> {@code CatalogService.priceSchedule} and the screen resolve through the
 * catalog's one price resolver. The screen trims and upper-cases the SKU before it does; the {@code api} method passes
 * it on as given, so the tool normalises it the same way ({@link Sku}). Otherwise "eval-lid-90" would find no own
 * price, no group price and no base price, and read as a SKU that is not for sale.</p>
 *
 * <p><b>The tier for a quantity</b> is checkout's rule ({@code PriceResolver.unitPrice}): the last tier starting at or
 * below it. Under the first tier the quantity is below the customer's minimum: checkout refuses it and Sales quotes it
 * instead (BR-05). Worked out here, so the model never picks a tier from a table itself.</p>
 *
 * <p><b>The customer</b> is resolved by {@link CustomerMatch}: a caller who may list customers is offered the names a
 * fragment matches; one who may not (the order coordinator reads prices for the orders they place) names the customer
 * exactly. Only the company name and the account status come back - no contact, e-mail, phone or tax code - and
 * sending the name to the model provider is the business's to accept (design §13 AQ-01).</p>
 *
 * <p><b>No validity dates.</b> A price has none in this system (V20261017000510: the documents give none); it applies
 * until someone changes it, so there is nothing to report.</p>
 */
@Component
class GetCustomerPriceTool implements AssistantTool {

    /** A quantity beyond any order of cups and lids; larger is a typing error, not a question. */
    static final int MAX_QUANTITY = 100_000_000;

    private static final ToolSpec SPEC = new ToolSpec("getCustomerPrice",
            "The prices one customer gets for one SKU, which a quote line starts from. source says whose price it "
                    + "is: CUSTOMER (the customer's own price list), SEGMENT (its customer group's price) or BASE "
                    + "(the list price). tiers: from minQuantity units on, unitPrice per unit in currency; moq is the "
                    + "first tier's quantity, below which this customer has no price. Give a quantity to be told "
                    + "which tier applies (tierForQuantity, the last tier starting at or below it): when belowMoq is "
                    + "true there is no price for that quantity, so say the quantity is under the minimum and that "
                    + "Sales quotes it separately. Never pick a tier yourself. Prices have no validity dates: they "
                    + "apply until changed. customerStatus is the customer account's: anything but ACTIVE means the "
                    + "customer can be neither quoted (BR-ORD-007) nor sold to now, so say so beside the price. The "
                    + "customer is named as the user named it: the company name, whole or in "
                    + "part, or its tax code. When it does not name exactly one customer, no price is returned and the "
                    + "note says what to ask; candidates, when given, are the customers that matched, by name: list "
                    + "them and ask which. When the SKU has no price at all, the note says so. It returns no customer "
                    + "contact details. For a product named in words, call searchCatalog first for its SKU. When the "
                    + "user gives quantities per design or per line of one order, call once with their sum.",
            ToolSchema.object()
                    .string("customer", "The customer as the user named it, every word kept: the company name whole "
                            + "or in part, words such as Công ty, Cửa hàng or Khách hàng included, since they can be "
                            + "part of the registered name; or its tax code")
                    .string("sku", "The SKU exactly as the user or an earlier answer gave it")
                    .integer("quantity", "The total units of this SKU in the one order or quote asked about, every "
                            + "line and every printed design counted together, as checkout and quotes read the tier at "
                            + "that total; 1 to " + MAX_QUANTITY + ", 0 when the user gave none")
                    .build());

    private final CatalogService catalog;
    private final CustomerService customers;
    private final CurrentUserProvider currentUsers;

    GetCustomerPriceTool(CatalogService catalog, CustomerService customers, CurrentUserProvider currentUsers) {
        this.catalog = catalog;
        this.customers = customers;
        this.currentUsers = currentUsers;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/catalog/customer-prices/{customerId}/{sku}/schedule";
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
        PriceSchedule schedule;
        try {
            schedule = catalog.priceSchedule(customer.customerId(), sku);
        } catch (BusinessException unpriced) {
            if (unpriced.errorCode() != ErrorCode.PRICE_NOT_AVAILABLE) {
                throw unpriced;
            }
            return Answer.noPrice(customer, sku);
        }
        return Answer.of(customer, schedule, quantity == 0 ? null : quantity);
    }

    /**
     * @param source          CUSTOMER, SEGMENT or BASE: whose price the schedule is; null when there is none
     * @param moq             the first tier's quantity: below it the customer has no price
     * @param tierForQuantity the tier {@code quantity} falls in; null without a quantity, or below the minimum
     * @param belowMoq        the quantity asked is under {@code moq}; null without a quantity
     * @param candidates      the customers the text matched, when it named none exactly
     * @param note            why there is no price, when there is none
     */
    record Answer(CustomerName customerName, String customerStatus, String sku, String source, Integer moq,
                  String currency, List<Tier> tiers, Integer quantity, Tier tierForQuantity, Boolean belowMoq,
                  List<CustomerMatch.Candidate> candidates, String note) {

        static Answer of(CustomerMatch customer, PriceSchedule schedule, Integer quantity) {
            List<Tier> tiers = schedule.tiers().stream()
                    .map(tier -> new Tier(tier.minQuantity(), tier.unitPrice().amount())).toList();
            Optional<Tier> applies = quantity == null ? Optional.empty() : tiers.stream()
                    .filter(tier -> tier.minQuantity() <= quantity)
                    .max(Comparator.comparingInt(Tier::minQuantity));
            String currency = schedule.tiers().isEmpty() ? null
                    : schedule.tiers().getFirst().unitPrice().currency().getCurrencyCode();
            return new Answer(customer.customerName(), customer.status(), schedule.sku(), schedule.source().name(),
                    schedule.moq(), currency, tiers, quantity, applies.orElse(null),
                    quantity == null ? null : applies.isEmpty(), List.of(), null);
        }

        static Answer noCustomer(String sku, CustomerMatch customer) {
            return new Answer(null, null, sku, null, null, null, List.of(), null, null, null, customer.candidates(),
                    customer.note());
        }

        static Answer noPrice(CustomerMatch customer, String sku) {
            return new Answer(customer.customerName(), customer.status(), sku, null, null, null, List.of(), null, null,
                    null,
                    List.of(), "No price is set for " + sku + ": not for this customer, not for its customer group "
                    + "and not on the list price. The SKU may be mistyped, or it is not priced for sale; say so and "
                    + "suggest checking the code, never a price.");
        }
    }

    /** From {@code minQuantity} units on, {@code unitPrice} per unit. */
    record Tier(int minQuantity, BigDecimal unitPrice) {
    }
}
