package com.stockflow.common.error;

/**
 * Shared business error codes. Clients branch on the code, never on the human-readable message.
 *
 * <p><b>Adding a code is a contract change.</b> Every code here can appear in an API response and
 * a client may branch on it, so a code is added freely and renamed never. The message is free to
 * change — it is for humans, and it gets translated.</p>
 *
 * <p>Codes are grouped by the HTTP status they map to, because that mapping is the part clients
 * actually depend on: whether to show a form error, retry, or give up.</p>
 */
public enum ErrorCode {
    // ---- 400: the request itself is wrong; retrying it unchanged will not help
    VALIDATION_FAILED("Invalid request data", 400),
    /** A customization template's model, limits or print areas cannot be printed as given (kltn-docs 01 BR-21). */
    PRINT_CONFIG_INVALID("The customization template is not valid", 400),
    /**
     * Stock that cannot be used as asked: blocked or expired stock to ship, several layers where one is meant, a
     * layer the fixed stock policy refuses to raise. The name is from the configurable policy team plan 5-5 removed
     * (with {@code INVENTORY_POLICY_INVALID} and {@code COUNT_POLICY_CONFLICT}); kept, since clients match on it.
     */
    INVENTORY_POLICY_STOCK_CONFLICT("This stock cannot be used this way; reconcile it first", 409),
    CATALOG_SLUG_EXISTS("This storefront URL is already reserved", 409),
    CATALOG_LISTING_REQUIRED("Configure the storefront listing before publishing", 409),
    /** Publishing, or a quote or order line (the field error carries this code): the product is not approved. */
    PRODUCT_NOT_APPROVED("The product is not approved", 409),
    PRODUCT_CATEGORY_REQUIRED("A valid product category is required", 409),
    PRODUCT_SKU_REQUIRED("At least one sellable SKU is required", 409),
    PRODUCT_GALLERY_REQUIRED("An approved non-empty product gallery is required", 409),
    /** Review of #109, item 6: a quote, its acceptance or its order, or a new sample, on a SKU no longer ACTIVE. */
    SKU_NOT_SELLABLE("The SKU is no longer active; change the line to an active SKU", 409),
    /** SCRUM-461, kltn-docs 01 §3: a product is identified by its brand too, so it is chosen before approval. */
    PRODUCT_BRAND_REQUIRED("A brand is required before submitting for approval", 409),
    /** SCRUM-87: the request requires them, so a product reaching approval without them is a legacy row. */
    PRODUCT_DETAILS_REQUIRED("An English name and a tax class are required before submitting for approval", 409),
    /** SCRUM-87: a retired brand stays on the products that chose it, but no product newly chooses it. */
    BRAND_INACTIVE("This brand is inactive and cannot be chosen for a product", 409),
    /** SCRUM-87: a retired category stays on the products that chose it, but no product newly chooses it. */
    CATEGORY_INACTIVE("This category is inactive and cannot be chosen for a product", 409),
    /** SCRUM-461, kltn-docs 01 BR-02: only a draft is deleted. */
    PRODUCT_NOT_DELETABLE("Only a draft product can be deleted", 409),
    /** SCRUM-461: stock, a production order, an order or a design already points at the product. */
    PRODUCT_IN_USE("The product is referenced by stock, production orders, orders or designs", 409),
    PRICE_NOT_AVAILABLE("No selling price is available for this SKU", 409),
    CUSTOMER_PRICE_NOT_FOUND("This customer has no own price for the SKU", 404),
    /** P7: a customer group (customer.segment) that does not exist. */
    SEGMENT_NOT_FOUND("Customer group not found", 404),
    MALFORMED_REQUEST("The request body could not be read", 400),
    /** SCRUM-243: the units of a return do not add up (received, accepted and rejected, where they went, how made good). */
    RETURN_QUANTITIES_INVALID("The quantities of this return do not add up", 400),
    UNSUPPORTED_PARAMETER("Unsupported parameter value", 400),
    /** The URL itself was refused before routing: {@code //}, an encoded {@code ..}, a {@code ;}. */
    REQUEST_REJECTED("The request URL is not acceptable", 400),
    /** A permission-matrix edit named a code no {@code @PermissionResource} declares. */
    UNKNOWN_PERMISSION("One or more permissions do not exist", 400),

    // ---- 401 / 403: who the caller is, and what they may do
    UNAUTHORIZED("Not authenticated", 401),
    FORBIDDEN("Not authorised", 403),
    /** Distinct from FORBIDDEN: the action is allowed, but not on these rows. See {@code DataScope}. */
    OUT_OF_DATA_SCOPE("Not authorised for this data", 403),
    /** A provider webhook whose signature is missing, wrong or outside the replay window (SCRUM-352). */
    WEBHOOK_SIGNATURE_INVALID("The webhook signature is missing or invalid", 401),

    // ---- 404
    NOT_FOUND("Resource not found", 404),
    ROLE_NOT_FOUND("Role not found", 404),
    USER_NOT_FOUND("User not found", 404),
    PRODUCT_NOT_FOUND("Product not found", 404),
    CATEGORY_NOT_FOUND("Category not found", 404),
    BRAND_NOT_FOUND("Brand not found", 404),
    VARIANT_NOT_FOUND("Variant not found", 404),
    /** A customization template that does not exist, or not on this variant (kltn-docs 01 §4.10). */
    PRINT_CONFIG_NOT_FOUND("Customization template not found", 404),
    ATTRIBUTE_NOT_FOUND("Attribute not found", 404),
    ATTRIBUTE_VALUE_NOT_FOUND("Attribute value not found", 404),
    MEDIA_NOT_FOUND("Media not found", 404),
    CUSTOMER_NOT_FOUND("Customer not found", 404),
    ADDRESS_NOT_FOUND("Address not found", 404),
    WAREHOUSE_NOT_FOUND("Warehouse not found", 404),
    /** G4-5: a parcel of another task is not found either. */
    PACKAGE_NOT_FOUND("Package not found", 404),
    SHIPMENT_NOT_FOUND("Shipment not found", 404),
    GOODS_RECEIPT_NOT_FOUND("Goods receipt not found", 404),
    /** Also the answer for a message of another user: an inbox id is only meaningful to its recipient. */
    NOTIFICATION_NOT_FOUND("Notification not found", 404),
    /** Also the answer for a zone of another warehouse: a zone id is only meaningful in its own. */
    ZONE_NOT_FOUND("Zone not found", 404),
    SHELF_NOT_FOUND("Shelf not found", 404),
    SHELF_LEVEL_NOT_FOUND("Shelf level not found", 404),
    BIN_NOT_FOUND("Bin not found", 404),
    AREA_NOT_FOUND("Area not found", 404),
    BOUNDARY_NOT_FOUND("Boundary not found", 404),
    /** The storage location id or code is not on any warehouse map. */
    LOCATION_NOT_FOUND("Location not found", 404),
    /** No stock of this SKU (and lot) is held at this location. */
    STOCK_ITEM_NOT_FOUND("No such stock at this location", 404),
    /** No stock package (carton) with this id or LPN (SCRUM-473). */
    STOCK_PACKAGE_NOT_FOUND("No such stock package", 404),
    STOCK_ADJUSTMENT_NOT_FOUND("Stock adjustment not found", 404),
    CANCELLATION_REQUEST_NOT_FOUND("Cancellation request not found", 404),
    /** SCRUM-243: no return request by that id - or not the caller's own, which is not revealed. */
    RETURN_REQUEST_NOT_FOUND("Return request not found", 404),
    RESERVATION_NOT_FOUND("Stock reservation not found", 404),
    /** The SKU has no inventory item yet: its logistics data must be completed first (docs 01 BR-03). */
    INVENTORY_ITEM_NOT_FOUND("No inventory item for this SKU", 404),
    /** No such unit of measure on this SKU's inventory item (docs 01 UoM conversion). */
    UOM_CONVERSION_NOT_FOUND("No such unit of measure on this SKU", 404),
    /** No such sales unit on this variant (docs 01 BR-11). */
    SALES_UNIT_NOT_FOUND("No such sales unit on this variant", 404),

    /** Credentials were correct but the account is LOCKED or DISABLED. Distinct from UNAUTHORIZED,
     *  which covers "wrong username or password" without revealing the account exists. */
    ACCOUNT_NOT_ACTIVE("This account cannot sign in right now", 403),
    /** Too many wrong passwords in a row; the lock ends on its own (SCRUM-456). Only revealed to a
     *  caller who then gave the right password, so it cannot be used to probe for accounts. */
    ACCOUNT_TEMPORARILY_LOCKED("Too many failed sign-in attempts; try again later", 403),
    /** The administrator-set temporary password is older than 72 hours; only a reset by an administrator
     *  helps (SCRUM-474 FIRST-LOGIN). Like ACCOUNT_NOT_ACTIVE it is only revealed after the right password. */
    TEMPORARY_PASSWORD_EXPIRED("The temporary password has expired; ask an administrator to reset it", 403),
    /** The account still has an administrator-set password: every call except changing it (and reading who
     *  you are, and signing out) is refused until it is changed (SCRUM-474 FIRST-LOGIN). */
    PASSWORD_CHANGE_REQUIRED("You must change your temporary password before using the system", 403),
    /** You can only hand out what you hold: a role or permission the caller lacks, or managing an
     *  account that holds more than the caller (SCRUM-456). */
    PRIVILEGE_ESCALATION("You cannot grant or manage permissions you do not hold yourself", 403),
    /** The account administration endpoints never act on the caller's own account. */
    OWN_ACCOUNT_NOT_MANAGEABLE("You cannot change your own account from user management", 403),
    /** Only the person who asked for a stock adjustment may take it back (SCRUM-459). */
    ADJUSTMENT_NOT_REQUESTER("Only the person who requested this stock adjustment can withdraw it", 403),

    /** The current password given when changing it was wrong. Distinct from a weak new password so a
     *  form can mark the right field, and a 400 rather than a 401 so a client does not read it as
     *  "your session expired" and sign the user out. */
    INVALID_CURRENT_PASSWORD("The current password is incorrect", 400),

    /** The new password is the same as the current one. */
    PASSWORD_UNCHANGED("The new password must differ from the current one", 400),
    /** SCRUM-427: commercial terms that contradict themselves (kltn-docs 18 §3). */
    CREDIT_PROFILE_INVALID("These commercial terms are inconsistent", 400),

    // ---- 409: the request is fine, the current state is not
    CONFLICT("Conflicting state", 409),
    /** The inventory item already has a unit with this code (its base unit included). */
    UOM_CONVERSION_DUPLICATE("This SKU already has a unit of measure with this code", 409),
    /**
     * The base unit, or a unit a variant sells in, cannot be deleted or retired (docs 01 BR-10, BR-11); a unit
     * a document counts in keeps its factor and code and is not deleted (BR-10, V20261020000600).
     */
    UOM_CONVERSION_IN_USE("This unit of measure is the base unit, is still sold in, or is used by a document", 409),
    /** The variant already sells in this unit. */
    SALES_UNIT_DUPLICATE("The variant already sells in this unit", 409),
    INSUFFICIENT_STOCK("Not enough available stock", 409),
    /** The destination already holds this SKU and lot in another status (stock of two statuses does not merge). */
    STOCK_STATUS_MISMATCH("The destination holds this lot in a different status", 409),
    /** The location exists, but it, its shelf or its warehouse is BLOCKED, in MAINTENANCE or INACTIVE (issue #67). */
    LOCATION_NOT_USABLE("This location cannot take stock right now", 409),
    /** A named location, or its shelf, is INACTIVE: off the layout, so it gets no label (SCRUM-97). */
    LOCATION_INACTIVE("This location is off the layout", 409),
    /** A label print whose selection resolves to no location on the layout (SCRUM-97). */
    LOCATION_LABELS_EMPTY("No storage location matches this selection", 422),
    /** More labels than one print request renders; print by zone or shelf instead (SCRUM-97). */
    LOCATION_LABELS_TOO_MANY("Too many labels for one request", 422),
    /** A carton label print that names neither a receipt line with live cartons nor any carton (SCRUM-473). */
    STOCK_PACKAGE_LABELS_EMPTY("Choose a receipt line or the cartons to print", 422),
    /** More carton labels than one print request renders (SCRUM-473). */
    STOCK_PACKAGE_LABELS_TOO_MANY("At most 500 carton labels at a time", 422),
    /**
     * Docs 11 BR-01: a move stays inside one warehouse (SCRUM-459). Stock never changes warehouse: transfers between
     * warehouses were removed, a Production Order delivers straight to the warehouse that ships (kltn-docs 17).
     */
    MOVE_ACROSS_WAREHOUSES("Stock moves only between locations of one warehouse", 409),
    /**
     * Four eyes: whoever asked for a stock adjustment may not decide it - nor, for a {@code COUNT_VARIANCE}, whoever
     * counted the line it came from.
     */
    ADJUSTMENT_SELF_APPROVAL("A stock adjustment cannot be decided by the person who requested it", 409),
    INVALID_ADJUSTMENT_TRANSITION("This stock adjustment has already been decided", 409),
    /** The life of a stock package (V20261020000100): RECEIVING → IN_STOCK | VOID, IN_STOCK → CONSUMED | LOST, LOST → IN_STOCK. */
    INVALID_STOCK_PACKAGE_TRANSITION("This stock package cannot do that in its current status", 409),
    INVALID_RECEIPT_TRANSITION("This goods receipt cannot move to that status right now", 409),
    /** The location exists but is not the kind of area this step needs (RECEIVING, QUARANTINE), or is in another
     *  warehouse. */
    LOCATION_AREA_MISMATCH("The location is not an area of the right kind in this warehouse", 409),
    /** BR-03: a receipt line without its lot - every SKU is lot-tracked (kltn-docs 01 BR-17, team plan 5-5). */
    RECEIPT_LOT_DATA_INVALID("Every receipt line needs a lot number", 409),
    /** Two receipt lines for the same production order line (uk_goods_receipt_lines_production_order_line). */
    DUPLICATE_RECEIPT_LINE("The same production order line appears twice on this receipt", 409),
    /**
     * SCRUM-473: a receipt line that has ever had labelled cartons - VOID ones too, which still point at it
     * ({@code fk_stock_package_receipt_line}) - cannot be removed from the draft, and its unit cannot change while it
     * keeps cartons in the old one. Such a line is dropped by cancelling the receipt and counting it again.
     */
    RECEIPT_LINE_HAS_PACKAGES("This receipt line has labelled cartons", 409),
    /** SCRUM-473: the cartons counted on a receipt line hold more units than the line received. */
    RECEIPT_PACKAGES_EXCEED_LINE("The cartons hold more than the receipt line", 422),
    /** Kltn-docs 03 BR-12: every unit received is in a carton; units outside one are not received. */
    RECEIPT_LINE_NOT_IN_CARTONS("Every unit of a receipt line must be in a carton", 422),
    /** Kltn-docs 03 BR-13: a carton holds the SKU's standard count; one of another count is not received. */
    RECEIPT_PACKAGE_NOT_STANDARD("A carton must hold the SKU's standard count", 422),
    /** SCRUM-92: no putaway task with this id. */
    PUTAWAY_TASK_NOT_FOUND("Putaway task not found", 404),
    /** SCRUM-92: the task has been put away completely; nothing is left to move. */
    PUTAWAY_TASK_DONE("This putaway task is already done", 409),
    /** SCRUM-92: more units than the task has left, a zero or negative count, or part of a carton. */
    PUTAWAY_QUANTITY_EXCEEDED("The putaway quantity does not fit what the task has left", 422),
    /** SCRUM-92: the scanned LPN is not the task's carton (or none was scanned for a carton task). */
    PUTAWAY_LPN_MISMATCH("The scanned carton is not the one this putaway task is for", 409),
    /** SCRUM-92: the chosen target is a storage area; goods are put away into bins. */
    PUTAWAY_TARGET_NOT_BIN("Goods are put away into a bin, not a storage area", 422),
    /** SCRUM-92, kltn-docs 05 BR-03: the putaway would take the bin past its computed volume or its weight limit. */
    LOCATION_CAPACITY_EXCEEDED("This bin has no room left for this carton", 409),
    /**
     * kltn-docs 06 BR-01, 11 BR-10: a carton does not fit the bin dimension by dimension, even turned on the
     * horizontal when its item allows. Every move of a carton into a bin is refused for it: putaway, put-back, a move.
     */
    CARTON_DOES_NOT_FIT_BIN("This carton does not fit the bin", 422),
    /**
     * kltn-docs 06 BR-23: resizing a bin, a level's clear height or a carton unit would leave a carton standing where it
     * no longer fits, or a bin holding more volume than it has. The details list the bins and cartons; move them first.
     */
    CARTONS_WOULD_NOT_FIT("This change would leave cartons that no longer fit where they stand", 409),
    /** kltn-docs 05 BR-13: a carton goes to the OVERFLOW area only when no bin of its warehouse takes it. */
    PUTAWAY_OVERFLOW_NOT_ALLOWED("A bin can still take this carton; put it away into a bin", 409),
    /**
     * SCRUM-473: a line with cartons names the active packing unit they are in; a loose line counts in the base unit
     * and claims no packing unit it holds no carton of.
     */
    RECEIPT_LINE_UNIT_INVALID("A receipt line counts in its item's base unit, or in an active packing unit it has cartons of", 422),
    RECEIPT_LINE_NOT_FOUND("This receipt has no such line", 404),
    /** Photos of a delivery or of one of its lines: a bounded set, not an album. */
    GOODS_RECEIPT_PHOTO_LIMIT_REACHED("This receipt has as many photos here as it may hold", 409),
    /** BR-ORD-007 / BR-CUS-003: new work (a sample round, a quote, an order) is only for an ACTIVE customer. */
    CUSTOMER_NOT_ACTIVE("This customer is not active and cannot take on new work", 409),
    /** SCRUM-394: a sample is linked only to a quote still being negotiated. */
    QUOTE_NOT_OPEN("The quote is no longer open", 409),
    /** SCRUM-448: a quote moves only along 03-state-machines §18. */
    INVALID_QUOTE_TRANSITION("This quote cannot move to that status right now", 409),
    /** BR-ORD-008: a quote is answered only within its validity. */
    QUOTE_EXPIRED("This quote is no longer valid", 409),
    /** BR-ORD-007: a quoted line is ordered at or above its minimum order quantity. */
    QUOTE_BELOW_MOQ("The quantity is below the minimum order quantity of the quote", 409),
    /**
     * R1: a line drafted without a price - only a reorder drafted before team plan 5-2 has one - waits for Sales;
     * nothing is sent or accepted unpriced.
     */
    QUOTE_NOT_PRICED("A line of this quote has no price yet; Sales enters it before the quote is sent", 409),
    /** 4B-2, QD-16: a quote drafted on blanks and designs before the customer's own SKUs is kept to read. */
    QUOTE_LINES_LEGACY("This quote was drafted on blanks and designs; quote the customer's own SKUs instead", 409),
    /** 4B-3 (kltn-docs 14 §4.1 step 3, BR-21): a line's VAT follows its product's tax class, which is not set yet. */
    QUOTE_TAX_CLASS_MISSING("A product on this quote has no tax class; set it before the quote is sent", 409),
    /** 4B-3 (kltn-docs 14 BR-17): a SKU produced for the first time is sent with its plate fee. */
    QUOTE_PLATE_FEE_MISSING("A SKU on this quote is produced for the first time and has no plate fee; edit the quote",
            409),
    /** 4B-4 (kltn-docs 14 §4.3): the order has no live sales contract, or no contract by that id. */
    SALES_CONTRACT_NOT_FOUND("No sales contract for this order", 404),
    /** 4B-4 (14 BR-12): one live contract per order. */
    SALES_CONTRACT_EXISTS("This order already has a sales contract in force; cancel it to draw up another", 409),
    /** 4B-4 (14 §4.3 step 11). */
    SALES_CONTRACT_ORDER_NOT_DRAFT("A sales contract is drawn up, edited and sent for a draft order", 409),
    /** 4B-4 (14 BR-23): the delivery schedule counts cartons. */
    SALES_CONTRACT_ORDER_NOT_CARTONS("A sales contract schedules cartons of the customer's own SKUs; this order has lines not ordered in cartons", 409),
    /** 4B-4 (14 §5.2, BR-14): the contract's state machine. */
    SALES_CONTRACT_STATUS_INVALID("The sales contract is not in a status that allows this", 409),
    /** 4B-4 (14 BR-23): batches, windows and cartons; the field errors name each rule broken. */
    DELIVERY_SCHEDULE_INVALID("The delivery schedule breaks a rule of the contract", 409),
    /** 4B-4 (14 BR-13): installments adding up to the total with tax; the field errors name each rule broken. */
    PAYMENT_PLAN_INVALID("The payment plan breaks a rule of the contract", 409),
    /** 4B-4 (18 BR-05). */
    CUSTOMER_LEGAL_PROFILE_MISSING("The customer's registered address and legal representative are recorded before a contract is drawn up", 409),
    /** 4B-4 (18 §3): the legal profile read before it was recorded. */
    LEGAL_PROFILE_NOT_FOUND("The customer's legal profile is not recorded yet", 404),
    /** 4A-2: no production order with that id (for a warehouse-bound caller: none received at their warehouses). */
    PRODUCTION_ORDER_NOT_FOUND("Production order not found", 404),
    /** 4A-2 (17 §5.3): the production order's status does not allow this. */
    PRODUCTION_ORDER_STATUS_INVALID("The production order is not in a status that allows this", 409),
    /** 4A-2 (17 BR-23): the one who drew a production order up does not approve it. */
    PRODUCTION_ORDER_SELF_APPROVAL("A production order is approved by someone who did not draw it up", 409),
    /** 4A-2 (17 BR-05): production orders come from a confirmed order of a contract with a shipping warehouse. */
    PRODUCTION_ORDER_NOT_ALLOWED("A production order is drawn up for a confirmed order of a contract", 409),
    /** 4A-2 (17 BR-05): the delivery batch lacks nothing - held, or on its way. */
    PRODUCTION_ORDER_NOTHING_SHORT("The delivery batch lacks nothing to produce", 409),
    /** 4A-2: a line makes no more than its delivery batch lacks. */
    PRODUCTION_ORDER_EXCEEDS_SHORTAGE("A line makes more cartons than the delivery batch lacks", 409),
    /**
     * 4A-5 (kltn-docs 03 BR-01, BR-15): goods are received against a production order IN_PRODUCTION or
     * PARTIALLY_RECEIVED - or SENT / ACCEPTED when no print sample is to be approved first - and nothing else.
     */
    PRODUCTION_ORDER_NOT_RECEIVABLE("Goods cannot be received against this production order now", 409),
    /** 4A-5 (03 BR-02): the cartons on the receipts not cancelled, drafts included, never exceed what the line orders. */
    RECEIPT_EXCEEDS_PRODUCTION_ORDER("More cartons than the production order line orders", 409),
    /** 4A-5 (03 edge cases): a production order with a draft receipt is neither cancelled nor closed. */
    PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS("A receipt against this production order is still a draft; confirm or cancel it first", 409),
    /** 4A-5 (03 §4.1 step 2): a receipt line counts a line of the receipt's production order. */
    RECEIPT_LINE_NOT_ON_PRODUCTION_ORDER("This line is not a line of the receipt's production order", 409),
    /** 4A-5 (03 BR-12, BR-13): every carton received holds exactly the SKU's standard count. */
    RECEIPT_CARTON_NOT_STANDARD("A carton received holds exactly the standard count of its SKU", 422),
    /**
     * 4A-4 (17 BR-24): with a print sample to approve, mass printing starts when the customer approves it - not when the
     * coordinator records it.
     */
    PRODUCTION_ORDER_SAMPLE_REQUIRED("The customer approves this production order's print sample before mass printing", 409),
    /** 4A-4 (17 BR-24): a production order without a print sample to approve has no sample rounds. */
    PRODUCTION_ORDER_SAMPLE_NOT_REQUIRED("This production order has no print sample to approve", 409),
    /**
     * 4A-4 (17 §4.3 step 9): a new round follows the customer's reprint request of the round before; one round waits
     * for the customer at a time.
     */
    SAMPLE_ROUND_NOT_OPENABLE("A new print sample round follows the customer's request to print it again", 409),
    /** 4A-4 (13 BR-13): no such round - or, for a customer, none of their own production orders'. */
    SAMPLE_ROUND_NOT_FOUND("Print sample round not found", 404),
    /** 4A-4 (13 BR-13): a round takes one answer, on the storefront or through the chat card, and keeps it. */
    SAMPLE_ROUND_ANSWERED("This print sample round has been answered already", 409),
    /** 4A-2: an INACTIVE producer takes no new production order. */
    PRODUCER_INACTIVE("This producer takes no new production order", 409),
    /** 4A-2 (17 BR-05): the print files changed since the design was approved. */
    PRINT_FILE_MISMATCH("The print files no longer match the approved design", 409),
    /** 4A-1: no producer with that id. */
    PRODUCER_NOT_FOUND("Producer not found", 404),
    /** 4A-1: a producer's code identifies it for good. */
    PRODUCER_CODE_TAKEN("A producer has this code already", 409),
    /** 4A-3: a link to a production order's files that was not made by this system, or names something else. */
    PRODUCTION_ORDER_LINK_INVALID("This link is not valid", 403),
    /** 4A-3: a link to a production order's files that has expired, or whose production order is no longer open. */
    PRODUCTION_ORDER_LINK_EXPIRED("This link has expired, or its production order is no longer open", 410),
    /** 4A-3: a production order sent before slips were kept, or not sent yet. */
    PRODUCTION_ORDER_SLIP_NOT_FOUND("The production order has no slip", 404),
    /** 4A-3: the digest the store keeps for the slip is no longer the one recorded when it was sent. */
    PRODUCTION_ORDER_SLIP_MISMATCH("The stored slip no longer matches what was sent", 409),
    /** 4A-3: a production order's message to its producer cannot go again now (not open, not e-mailed, on its way). */
    PRODUCTION_ORDER_RESEND_NOT_ALLOWED("The production order's message cannot be sent to its producer again now", 409),
    /** 4B-7 (17 §4.2 step 2): only a CONFIRMED order of a contract gets a shipping warehouse. */
    ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED("The shipping warehouse is chosen for a confirmed order of a contract", 409),
    /** 4B-7 (17 BR-22): the shipping warehouse changes only while nothing is held for the order. */
    ORDER_SHIPPING_WAREHOUSE_LOCKED("Stock is held at the order's shipping warehouse; it can no longer change", 409),
    /** 4B-7 (17 BR-22): an order of a contract is released from its shipping warehouse only. */
    ORDER_SHIPPING_WAREHOUSE_MISMATCH("The order ships from another warehouse", 409),
    /** 4B-5: an order confirmed by its contract holds no stock until its shipping warehouse is chosen (4B-7). */
    ORDER_STOCK_NOT_HELD("This order holds no stock yet; its shipping warehouse is chosen and its stock held first", 409),
    /** 4B-8: no delivery batch with that id on the order's signed contract. */
    DELIVERY_BATCH_NOT_FOUND("Delivery batch not found on this order", 404),
    /**
     * 4B-8 (17 BR-29): delivery batches are released one at a time, in schedule order, each once the one before it was
     * handed over; the one named is not the next.
     */
    DELIVERY_BATCH_NOT_NEXT("This is not the order's next delivery batch to release", 409),
    /** 4B-8 (09 BR-04, 17 BR-29): a batch's goods leave from the first day of its delivery window. */
    DELIVERY_BATCH_WINDOW_NOT_OPEN("This delivery batch's window has not opened yet", 409),
    /** 4B-8 (17 BR-28): a window moves only while no carton of the batch was handed over. */
    DELIVERY_BATCH_WINDOW_LOCKED("Goods of this delivery batch were handed over; its window no longer moves", 409),
    /**
     * 4B-8 (09 BR-18): the vehicle is not handed over at all while an order on it may not let goods leave;
     * {@code details} lists each order, why, and what it still owes.
     */
    LOAD_HANDOVER_BLOCKED("Some orders on this vehicle may not be handed over yet", 409),
    /** WF-05 E2: no e-invoice with that id. */
    EINVOICE_NOT_FOUND("E-invoice not found", 404),
    /** WF-05 E2 (kltn-docs 21 §5.1): the e-invoice's status does not allow this - an issued invoice above all, which is never cancelled or changed (TT 91 Điều 10). */
    EINVOICE_INVALID_TRANSITION("The e-invoice's status does not allow this", 409),
    /** WF-05 E2 (21 BR-03): only a draft e-invoice is corrected. */
    EINVOICE_NOT_DRAFT("Only a draft e-invoice can be changed", 409),
    /** WF-05 E2 (21 BR-06; NĐ 254 Phụ lục điểm 4.b): the buyer's name, tax code or address is missing, so the invoice is not issued. */
    EINVOICE_BUYER_PROFILE_INCOMPLETE("The buyer's name, tax code and address are needed before the invoice is issued", 409),
    /** WF-05 E2 (21 BR-01): the shipment is not delivered, or delivered nothing an invoice can carry. */
    EINVOICE_NOT_INVOICEABLE("This shipment cannot be invoiced: it is not delivered", 409),
    /**
     * WF-05 E2 (NĐ 254 Điều 9 khoản 1; WF05-E0 L6): the delivered shipment has no delivery time, and the invoice is
     * dated by it - never by today. A code of its own because it reaches the accountants as the cause of an
     * {@code EInvoiceDraftFailed}, and it tells them what to correct.
     */
    EINVOICE_DELIVERY_TIME_MISSING("The delivery has no delivery time to date its invoice by; record it first", 409),
    /** WF-05 E2 (TT 91 Điều 4): no invoice series is in use at the provider for the invoice's year. */
    EINVOICE_SERIES_NOT_CONFIGURED("No invoice series is configured for the invoice's year", 409),
    /**
     * WF-05 E2 (NĐ 254 Phụ lục điểm 2; TT 91 Điều 10 khoản 6a): an invoice of the same year dated earlier is still to be
     * issued; issuing this one first would give it the lower number, and numbers would run against the dates.
     */
    EINVOICE_OLDER_DRAFT_PENDING("An invoice dated earlier is still to be issued; issue or void it first", 409),
    /** WF-05 E2: the e-invoice is not issued, so it has no XML or PDF yet. */
    EINVOICE_NOT_ISSUED("The e-invoice is not issued yet", 409),
    /** WF-05 E2: the e-invoice provider is not configured, or did not answer; nothing is known about the transaction until it is asked again. */
    EINVOICE_PROVIDER_UNAVAILABLE("The e-invoice provider is not available right now; the invoice will be checked again", 503),
    /** WF-05 E3: a real e-invoice provider is put in use only once its account is stored. */
    EINVOICE_PROVIDER_ACCOUNT_INCOMPLETE("The e-invoice provider's account is not stored yet", 409),
    /** WF-05 E3: an invoice at the provider is sent as a new transaction only when the provider no longer remembers the old one and confirms it holds no such invoice. */
    EINVOICE_RESUBMISSION_REFUSED("The e-invoice cannot be sent as a new transaction: the provider may hold it", 409),
    /** WF-05 E3+E4: the provider in use writes every invoice as an original, so a correction sent to it would be a second invoice. */
    EINVOICE_PROVIDER_CANNOT_CORRECT("The e-invoice provider in use cannot issue adjustments or replacements yet; the correction is kept as a draft", 409),
    /** WF-05 E4 (TT 91 Điều 10): only an issued invoice still in force is adjusted, replaced or notified as drawn up wrongly. */
    EINVOICE_NOT_IN_FORCE("Only an issued e-invoice that is still in force can be corrected", 409),
    /** WF-05 E4 (TT 91 Điều 10 khoản 6 điểm a): an invoice already adjusted is adjusted again, one replaced is replaced again. */
    EINVOICE_CORRECTION_FORM_FIXED("This e-invoice is already being corrected in the other form; the form first used is kept", 409),
    /** WF-05 E4: the correction asked for is not one the law gives for this invoice (an adjustment of an adjustment, a return that adds, an agreement where none is asked). */
    EINVOICE_CORRECTION_NOT_APPLICABLE("This correction does not apply to this e-invoice", 409),
    /** WF-05 E4 (TT 91 Điều 10 khoản 1 điểm b): the correction of an error is not sent without the written agreement with the buyer. */
    EINVOICE_AGREEMENT_REQUIRED("The written agreement with the buyer - its file, number and date - is needed before the correction is issued", 409),
    /** WF-05 E4: adjustments may not reduce an invoice below nothing. */
    EINVOICE_ADJUSTMENT_EXCEEDS_INVOICE("The adjustment would reduce the e-invoice by more than it holds", 409),
    /** WF-05 E4: no such 04/SS-HĐĐT notice on this invoice. */
    EINVOICE_ERROR_NOTICE_NOT_FOUND("Notice of an e-invoice drawn up wrongly not found", 404),
    /** WF-05 E4: a notice of this invoice is still on its way, or the notice asked about is already answered. */
    EINVOICE_ERROR_NOTICE_NOT_SENDABLE("This notice cannot be recorded or sent now: another one of the invoice is still open, or it is already answered", 409),
    /** 4B-4, WF-05 E2: stockflow.seller.* is blank in this deployment; no contract or invoice names a seller. */
    SELLER_PROFILE_UNAVAILABLE("The seller's company details are not configured; contracts and invoices cannot be drawn up", 503),
    /**
     * 4B-2 (kltn-docs 14 BR-04, 13 BR-02): a quote line, an order line placed from one, or a customer price names one
     * of the customer's own SKUs - not a stock variant or a blank. Another customer's SKU answers VARIANT_NOT_FOUND.
     */
    CUSTOMER_SKU_REQUIRED("Only the customer's own SKUs are quoted and priced for them", 409),
    /** 4B-2 (kltn-docs 01 BR-03, BR-11): a SKU is sold in cartons, and this one has no single carton unit. */
    CARTON_SPEC_MISSING("This SKU has no carton specification; it is sold in cartons", 409),
    /**
     * A legacy print line - a blank with a design snapshot, placed before lines named the customer's own SKU (4B-2) -
     * names the blank its design is printed on, whose printed goods were never received into stock: releasing would
     * allocate, pick and ship plain blanks. A line of the customer's own SKU is not refused: its goods arrive printed,
     * received against a production order, as stock (4A-6, team plan QD-16).
     */
    ORDER_PRINT_GOODS_NOT_RECEIVED("This order has printed goods that are not in stock yet; it is released once they "
            + "are received from the producer", 409),
    /** SCRUM-460: once released, a customer asks and Sales decides. */
    ORDER_NOT_CANCELLABLE("This order can no longer be cancelled; raise a return instead", 409),
    /** Audit of #109 (decision 2026-10-17): before printing a cancellation is refunded in full - nothing is kept. */
    CANCELLATION_FEE_BEFORE_PRINTING("Printing has not started: the order is refunded in full, no fee is kept", 409),
    /** Work for an order that has been cancelled (printing started, a package completed) is refused. */
    ORDER_CANCELLED("This order is cancelled", 409),
    /** Decision 2026-10-17: once printing started, only staff cancel an order (fee, blanks, printed goods). */
    CANCELLATION_NEEDS_STAFF("Printing has started: staff decide this order's cancellation", 409),
    ORDER_CANCELLATION_REQUEST_PENDING("A cancellation request for this order is already waiting for a decision", 409),
    ORDER_CANCELLATION_REQUEST_NOT_PENDING("This cancellation request has already been decided", 409),
    // ---- returns (SCRUM-243 / SCRUM-260, 03 §12): each names the rule that refused, so the portal can say what to do
    /** BR-RMA-001: only goods the customer received can come back. */
    RETURN_ORDER_NOT_DELIVERED("This order has not been delivered yet; nothing can be returned", 409),
    /** The order's returns were already settled with money, or it is closed: a new claim needs Sales. */
    RETURN_ORDER_NOT_RETURNABLE("This order no longer takes a return", 409),
    /** BR-RMA-001, OQ-07: the return window after delivery has passed. */
    RETURN_WINDOW_CLOSED("The return window for this order has closed", 409),
    /** More units than were delivered, less what the line's other returns claim. */
    RETURN_QUANTITY_EXCEEDED("More units than can still be returned from this order line", 409),
    /** One open return per order line: the open one is decided first. */
    RETURN_LINE_ALREADY_OPEN("This order line already has an open return", 409),
    /** BR-RMA-002: goods printed to the customer's design come back only when defective. */
    RETURN_CUSTOM_ITEM_NOT_DEFECTIVE("Custom-printed goods are returned only when defective", 409),
    /** SCRUM-243 (B2B plan 2026-10-06): returns are taken for defects and delivery faults, not a change of mind. */
    RETURN_REASON_NOT_ACCEPTED("Returns are accepted for defects and delivery faults only", 409),
    /** 03 §12: the return's status has no such step. */
    RETURN_INVALID_TRANSITION("This return cannot do that in its current status", 409),
    /** A defect is approved on evidence: at least one photo of the problem. */
    RETURN_EVIDENCE_REQUIRED("Add at least one photo of the problem before the return is approved", 409),
    RETURN_EVIDENCE_LIMIT_REACHED("This return has as many photos as it may hold", 409),
    /** Kltn-docs 17 BR-18 (team plan 4D-3): the goods of an approved return always come back on a return receipt. */
    RETURN_GOODS_REQUIRED("The goods of this return must be sent back before it is refunded", 409),
    /**
     * Four eyes on anything of value (BR-PAY-003 without approver limits): a claim Sales opened, and a refund - each
     * approved by someone else than who opened or proposed it.
     */
    RETURN_SELF_APPROVAL("A return cannot be approved by the person who opened or proposed it", 409),
    /** BR-CUS-003: a customer who is not ACTIVE opens no return themselves. */
    RETURN_CUSTOMER_NOT_ACTIVE("The customer account is not active", 409),
    /**
     * Team plan 4D-3 (kltn-docs 15 §4.5): a return is refunded against the order's installments; an order paid any
     * other way (a checkout order of before contracts) has none, and its return is refunded outside the system.
     */
    RETURN_REFUND_NO_INSTALLMENTS("This order is not paid through a contract's installments; refund it outside the "
            + "system", 409),
    /** SCRUM-427: credit approval or refusal on an order that is not waiting for one. */
    ORDER_NOT_ON_CREDIT_HOLD("This order is not waiting for a credit decision", 409),
    /** G3-2 (SCRUM-246/247): the order's lifecycle has no edge from its status to the one asked for. */
    ORDER_INVALID_TRANSITION("This order cannot move to that status now", 409),
    /** G3-2: resolving a hold on an order that is not on hold. */
    ORDER_NOT_ON_HOLD("This order is not on hold", 409),
    /** G3-2: a credit hold is decided through credit approval, not resolved like the others. */
    ORDER_CREDIT_HOLD_USE_APPROVAL("A credit hold is decided through credit approval", 409),
    /** G4-Q5: an order with shipments records its delivery outcome per shipment, not for the whole order. */
    ORDER_DELIVERY_VIA_SHIPMENT("This order's delivery is recorded per shipment", 409),
    /** G3-Q4: money due before the goods leave has not all arrived; the handover waits for it. */
    ORDER_BALANCE_NOT_PAID("This order must be paid before its goods leave", 409),
    /** G3-2 (split shipments): no shipment by that id was handed over for this order. */
    ORDER_SHIPMENT_NOT_FOUND("This order has no such shipment", 404),
    /** G3-2: the same shipment handed over again with other goods. */
    ORDER_SHIPMENT_CONFLICT("This shipment was already handed over with other goods", 409),
    /** G3-2: more units would leave than the order line has left to ship. */
    ORDER_SHIPMENT_QUANTITY_EXCEEDED("More than this order line has left to ship", 409),
    /** G3-2: a shipment outcome its status does not allow (kltn-docs 09 §5). */
    ORDER_SHIPMENT_INVALID_TRANSITION("This shipment cannot move to that status now", 409),
    /** SCRUM-431, kltn-docs 15 §4.3 step 4: an overdue customer gets no new credit until they pay. */
    CUSTOMER_CREDIT_OVERDUE("The customer has overdue receivables", 409),
    /** 4B-6: no installment with that id (for a customer: none of theirs). */
    INSTALLMENT_NOT_FOUND("Installment not found", 404),
    /** 4B-6, 15 BR-05: a paid or cancelled installment takes no more money. */
    INSTALLMENT_NOT_PAYABLE("This installment is paid or cancelled; it takes no more money", 409),
    /** 4B-6, 15 BR-05: a receipt pays installments of its own customer only. */
    INSTALLMENT_CUSTOMER_MISMATCH("This installment belongs to another customer", 409),
    /** 4B-6: no payment receipt with that id. */
    PAYMENT_RECEIPT_NOT_FOUND("Payment receipt not found", 404),
    /** 4B-6, 15 BR-03: one bank statement line, one live receipt. */
    PAYMENT_RECEIPT_REFERENCE_IN_USE("This bank statement line is already recorded", 409),
    /** 4B-6, 15 BR-05: a line pays no more than its installment still owes; nothing is left over. */
    PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING("A line pays more than its installment still owes", 409),
    /** 4B-6, 15 BR-04: a receipt is voided once. */
    PAYMENT_RECEIPT_ALREADY_VOIDED("This payment receipt is already voided", 409),
    /** 4B-6, 15 §4.4 step 13: the order is cancelled, completed, closed, being returned or refunded. */
    PAYMENT_RECEIPT_VOID_NOT_ALLOWED("This payment receipt can no longer be voided", 409),
    /** 4B-6, 15 BR-13: the void leaves a confirmed order short of its installment on signing; the accountant agrees to hold it. */
    PAYMENT_RECEIPT_VOID_NEEDS_HOLD("Voiding this receipt leaves a confirmed order unpaid: confirm putting it on hold",
            409),
    /** 4B-9, 15 BR-14: a refund recorded is never more than what the order still has to refund. */
    REFUND_EXCEEDS_REFUNDABLE("This refund is more than the order still has to refund", 409),
    /** 4B-9: no refund record with that id. */
    REFUND_RECORD_NOT_FOUND("Refund record not found", 404),
    /** 4B-9, 15 §4.5 step 16: a refund record is voided once. */
    REFUND_RECORD_ALREADY_VOIDED("This refund record is already voided", 409),
    /** 4B-9: one outgoing bank statement line, one live refund record. */
    REFUND_REFERENCE_IN_USE("This bank statement line is already recorded as a refund", 409),
    /** PACK-PARTIAL (lead's decision 2026-10-21): the order does not allow a partial shipment; Sales decides. */
    ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED("This order does not allow partial shipments", 409),
    /** PACK-PARTIAL: whether the order may ship in parts can no longer change once everything left or it is closed. */
    ORDER_PARTIAL_SHIPMENT_LOCKED("Partial shipment can no longer be changed on this order", 409),
    /** SCRUM-466: a task is picked in one warehouse; the order's stock is held in more than one, or elsewhere. */
    FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE("Stock held for this order is in another warehouse", 409),
    /** SCRUM-267 (docs 07 step 6): the bin scanned is not the one this pick line takes its goods from. */
    PICK_LOCATION_MISMATCH("The scanned location is not this line's", 409),
    /**
     * Kltn-docs 07 BR-10 (team plan 4C-4): the code scanned is not this line's carton LPN - a SKU, a barcode or another
     * carton is refused, to pick a line or to put it back. No quantity is scanned any more (BR-04), so the former
     * PICK_QUANTITY_EXCEEDS_ALLOCATION and PICK_QUANTITY_SHORT are gone.
     */
    PICK_ITEM_MISMATCH("The scanned code is not this line's carton LPN", 409),
    /**
     * Team plan 4C-4 (kltn-docs 07 BR-04, BR-10): the stock held for a pick line is in no carton, so no LPN could
     * confirm it. A guard: allocation takes whole cartons only, so nothing should reach it.
     */
    PICK_HOLD_NOT_IN_CARTON("The stock held for this line is in no carton; reallocate it to whole cartons", 409),
    PICK_LINE_NOT_OPEN("This line is no longer open", 409),
    /** A cycle count already completed or cancelled. */
    CYCLE_COUNT_NOT_OPEN("This cycle count is no longer open", 409),
    /** A planned count whose scope resolves to no stock slot. */
    CYCLE_COUNT_EMPTY_SCOPE("The count scope matches no stock", 422),
    /** A planned count above stockflow.inventory.cycle-count-max-lines. */
    CYCLE_COUNT_TOO_LARGE("The count scope is too large - narrow it", 422),
    /** A counter who is not the assignee and may not decide. */
    CYCLE_COUNT_NOT_ASSIGNEE("This count is assigned to someone else", 403),
    /**
     * A line already counted by someone else on a count assigned to a third person: the figure is the counter's to
     * correct. Whoever may decide could otherwise replace it and then approve the variance it raises.
     */
    CYCLE_COUNT_LINE_COUNTED_BY_OTHER("This line was counted by someone else; only the assignee may count it again", 403),
    /** Submitting a count that still has uncounted lines. */
    CYCLE_COUNT_LINE_NOT_COUNTED("Some lines are not counted yet", 409),
    /** A slot already held by an open count. */
    CYCLE_COUNT_SLOT_BUSY("This stock slot is already on an open count", 409),
    /** A manual move out of or into a slot a cycle count holds (SCRUM-146). */
    STOCK_SLOT_UNDER_COUNT("This stock slot is under a cycle count; stock cannot be moved out of or into it", 409),
    /** A surplus where the slot has no layer to receive it. */
    CYCLE_COUNT_UNKNOWN_LAYER("The surplus cannot be placed on any receipt layer", 422),
    /** A shortage larger than what the slot holds now. */
    CYCLE_COUNT_STALE("Stock changed since it was counted - recount the line", 409),
    /** A COUNT_VARIANCE adjustment withdrawn by its requester: a count's variance is approved or rejected. */
    COUNT_VARIANCE_NOT_WITHDRAWABLE("A count variance is approved or rejected, not withdrawn", 409),
    /** A carton counted at a location it is not in. */
    STOCK_PACKAGE_WRONG_LOCATION("The carton is not at this location", 422),
    /** A carton counted above its received quantity. */
    STOCK_PACKAGE_OVERFILLED("A carton cannot hold more than it was received with", 422),
    /**
     * Kltn-docs 11 BR-05: a carton held or allocated for an order is not moved by hand; its picker would find it gone.
     * Nor written off by hand (20 §4.3, team plan 4C-3): the order's hold is released or reallocated first.
     */
    STOCK_PACKAGE_HELD("The carton is held for an order and cannot be moved or written off by hand", 409),
    /** Team plan 4C-2 (07 BR-03, 11 BR-04): stock leaves a carton whole; nothing takes part of one. */
    STOCK_PACKAGE_TAKEN_WHOLE("A carton is taken whole; part of it cannot be taken", 409),
    /**
     * Team plan 4C-2, review M-5 (kltn-docs 20 BR-14): stock is kept in whole cartons, so units are never added to a row
     * in no carton. A carton found is restocked through a count (by its LPN), never as loose units.
     */
    STOCK_NOT_IN_CARTON("Stock is kept in whole cartons; adjust a carton by its LPN", 422),
    /**
     * Review P-2 (kltn-docs 20 BR-04): a carton is adjusted by hand for DAMAGED, LOST, FOUND, DATA_CORRECTION or OTHER;
     * SCRAP and SAMPLE wrote down loose units. tg_stock_adjustment_whole_carton states it in the table.
     */
    STOCK_ADJUSTMENT_REASON_NOT_FOR_CARTON("A carton is adjusted for DAMAGED, LOST, FOUND, DATA_CORRECTION or OTHER", 422),
    /** Team plan 4D-1 (kltn-docs 20 §4.8): the carton is quarantined already. */
    STOCK_PACKAGE_QUARANTINED("The carton is already in quarantine", 409),
    /** Team plan 4D-1 (kltn-docs 05 BR-01, BR-14): only a quarantined carton is released to a putaway task. */
    STOCK_PACKAGE_NOT_QUARANTINED("The carton is not in quarantine", 409),
    /**
     * Team plan 4D-1 (kltn-docs 20 §4.8 step 3): a carton is released or written off, not both - one has an open
     * putaway task, or a write-off of it awaits approval, or it is not yet given the putaway task of its receipt.
     */
    STOCK_PACKAGE_DECISION_PENDING("A decision about this carton is already under way", 409),
    /** Team plan 4D-2 (kltn-docs 09 BR-06): a return receipt is opened for a shipment the carrier brought back. */
    SHIPMENT_NOT_RETURNED("The shipment has not come back to the warehouse", 409),
    /** Team plan 4D-2 (kltn-docs 17 §4.10): a return receipt is opened for a return request approved and awaiting its goods. */
    RETURN_NOT_AWAITING_GOODS("This return request is not waiting for its goods", 409),
    /** Team plan 4D-2: goods come back once - one return receipt not cancelled per shipment and per return request. */
    RETURN_RECEIPT_ALREADY_OPEN("A return receipt for these goods already exists", 409),
    /** Team plan 4D-2 (kltn-docs 03 BR-14): a return receipt takes only cartons of its shipment or order. */
    RETURN_CARTON_NOT_OF_SOURCE("This carton did not leave on the shipment or order the receipt is for", 409),
    /** Team plan 4D-2 (kltn-docs 03 §4.2 step 10): a carton still in the warehouse has not come back. */
    RETURN_CARTON_IN_WAREHOUSE("This carton is in the warehouse; it has not come back", 409),
    /** Team plan 4C-3 (20 §4.3, BR-05): one adjustment of a carton waits for approval at a time. */
    STOCK_PACKAGE_ADJUSTMENT_PENDING("Another adjustment of this carton is waiting for approval", 409),
    /** Docs 07 step 9: a pick task closes only when every line is picked. */
    PICK_LINES_INCOMPLETE("Some lines are not picked yet", 409),
    PICK_TASK_STATE_CONFLICT("The task cannot do that in its current status", 409),
    /** The hold behind a pick line was released, expired or already consumed: nothing is deducted. */
    RESERVATION_NOT_ACTIVE("The stock hold is no longer active", 409),
    /** 4B-7 (20 BR-17): a hold at the order's shipping warehouse is allocated there only. */
    RESERVATION_WAREHOUSE_MISMATCH("The stock is held at another warehouse", 409),
    RESERVATION_NOT_WHOLE_CARTONS("A stock reservation is a whole number of the SKU's cartons", 422),
    /**
     * A hold that is not an order's goods on a shelf - one on a legacy print-shop row (IN_PRODUCTION):
     * a pick does not close it (review of #109). The code keeps its name for clients that read it.
     */
    PRODUCTION_HOLD_NOT_PICKABLE("This stock hold is not an order's goods on a shelf and cannot be picked", 409),
    /** Docs 07 BR-05: only goods picked for an order cancelled afterwards go back to a bin. */
    PUT_BACK_NOT_REQUIRED("Nothing on this line needs putting back", 409),
    PUT_BACK_LOCATION_INVALID("Put back onto an active bin of the task's warehouse", 409),
    /**
     * Kltn-docs 11 BR-09, 20 BR-20 (team plan 4C-5): a staging area holds cartons picked for an order; stock enters it
     * only by picking, and leaves it only when the task moves to dispatch or by the put-back - never by hand.
     */
    STAGING_MOVE_NOT_ALLOWED("Stock enters a staging area only by picking, and leaves it by dispatch or put-back", 409),
    /** Kltn-docs 07 §3 (4C-5): a STAGING task names an active staging area of its own warehouse. */
    STAGING_AREA_INVALID("Choose an active staging area of the task's warehouse", 422),
    /**
     * Kltn-docs 07 §4.3 step 8 and its edge case of an order on hold while its task is STAGED (4C-5): the order keeps
     * its cartons in the staging area; the task moves to dispatch once the hold is resolved.
     */
    PICK_ORDER_ON_HOLD("The order is on hold; its cartons stay in the staging area until the hold is resolved", 409),
    /** G4-5 (docs 08 step 1): packing starts once picking is complete. */
    PACKING_NOT_READY("Picking must finish before packing starts", 409),
    /** G4-5: another packer took this order at the packing table; a warehouse manager may step in. */
    PACKING_TAKEN("Another packer is packing this order", 409),
    PACK_STATE_CONFLICT("Packing cannot do that in its current status", 409),
    /** The scanned code is on two lines of the order (a plain and a printed cup of one SKU): name the line. */
    PACKAGE_SHIPPED("The package is in a shipment already", 409),
    /** Docs 08 BR-01: everything the order still wants must be packed, or a partial shipment asked for. */
    PACKING_INCOMPLETE("Not everything is packed yet", 409),
    /** PACK-PARTIAL (lead's decision 2026-10-21): the order does not allow a partial shipment; Sales decides. */
    PACK_PARTIAL_NOT_ALLOWED("Partial shipments are not allowed", 409),
    PACK_NOTHING_TO_SHIP("No sealed package is waiting to ship", 409),
    /**
     * Kltn-docs 08 §4.2 step 3, BR-05 (team plan 4C-6): the scanned LPN is not a carton picked for this task - another
     * order's, one never picked, or an unreadable code. Refused; the warehouse manager checks the picking and the stock.
     */
    CARTON_NOT_IN_TASK("This carton was not picked for this order's task", 409),
    /** Kltn-docs 08 BR-08 (4C-6): a shipping label prints only for a carton whose LPN was scanned at the check. */
    CARTON_NOT_CHECKED("Only a checked carton gets a shipping label", 409),
    /**
     * Kltn-docs 08 BR-07 (4C-6): the SKU's carton spec declares no size or full weight, so no shipping label prints and
     * the check cannot complete until the warehouse manager completes it on the item.
     */
    CARTON_SPEC_UNMEASURED("The SKU's carton spec has no size or weight: no shipping label can print", 422),
    /** Docs 09 §5: the shipment's status does not allow the step. */
    SHIPMENT_STATE_CONFLICT("The shipment cannot do that in its current status", 409),
    // ---- G4-2a loading and handover (kltn-docs 09 §4.1, §5): 404 / 400 / 409 kept together with the flow
    /** G4-2a: a load (manifest) of a warehouse outside the caller's scope is OUT_OF_DATA_SCOPE, not this. */
    LOAD_NOT_FOUND("Load not found", 404),
    CARRIER_NOT_FOUND("Carrier not found", 404),
    /** Docs 09 §4.1 step 3: the manifest is signed by the warehouse and the carrier's driver. */
    HANDOVER_NOT_SIGNED("Both sides must sign the manifest", 400),
    CARRIER_CODE_EXISTS("Carrier code already exists", 409),
    CARRIER_INACTIVE("This carrier is no longer in use", 409),
    /** 3-1: service codes are kept only for a carrier the system books with through its API (kltn-docs 09 §4.1). */
    CARRIER_NOT_API_BOOKING("This carrier is not booked through an API", 409),
    /** SCRUM-287 load planning: the vehicle-type catalogue (kltn-docs 09 §3). */
    VEHICLE_TYPE_NOT_FOUND("Vehicle type not found", 404),
    VEHICLE_TYPE_CODE_EXISTS("Vehicle type code already exists", 409),
    /**
     * SCRUM-287 PR EST: the advisory load estimate (design v2 §2, LP2-1 to LP2-5). PACKAGING_MISSING names each blocked
     * line's inventory item in the field errors, so the screen links to the item to complete.
     */
    LOAD_ESTIMATE_NOT_FOUND("Load estimate not found", 404),
    LOAD_ESTIMATE_PACKAGING_MISSING("No line has an item with a size and weight to estimate its cartons", 422),
    LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE("This order cannot be estimated in its current status, or has nothing left to ship", 409),
    LOAD_ESTIMATE_SOURCE_UNSUPPORTED("This kind of source cannot be estimated yet", 400),
    LOAD_ESTIMATE_TOO_MANY_CARTONS("The order has too many cartons to estimate at once", 422),
    VEHICLE_TYPE_INACTIVE("This vehicle type is no longer in use", 409),
    INVALID_LOAD_TRANSITION("The load cannot do that in its current status", 409),
    // ---- SCRUM-287 PR D load plans (kltn-docs 09 §4.2, §5, BR-09..BR-15)
    /** A load plan of a warehouse outside the caller's scope is OUT_OF_DATA_SCOPE, not this. */
    LOAD_PLAN_NOT_FOUND("Load plan not found", 404),
    LOAD_PLAN_VEHICLE_NOT_FOUND("This vehicle is not in the load plan", 404),
    /** Docs 09 §5: the plan's status does not allow the step. */
    INVALID_LOAD_PLAN_TRANSITION("The load plan cannot do that in its current status", 409),
    /** Docs 09 BR-09: a parcel belongs to at most one load plan that is not cancelled. */
    LOAD_PLAN_PACKAGE_TAKEN("A package of this shipment is already in another load plan", 409),
    /** Docs 09 BR-09 / edge "kiện thiếu kích thước": only sealed parcels with sizes and weight are planned. */
    LOAD_PLAN_PACKAGE_UNMEASURED("A package has no size or weight: measure it again at packing", 422),
    /** Nothing to calculate or confirm: no parcels, no vehicle types, or no parcel placed. */
    LOAD_PLAN_EMPTY("The load plan has nothing to plan or confirm", 409),
    /** Docs 09 BR-13: parcels left unplaced must be acknowledged before the plan is confirmed. */
    LOAD_PLAN_UNPLACED_NOT_ACKNOWLEDGED("Acknowledge the packages left unplaced before confirming", 409),
    /** Docs 09 BR-14: once a vehicle of the plan started loading, the plan is neither reopened nor cancelled here. */
    LOAD_PLAN_LOADING_STARTED("A vehicle of this load plan is already being loaded", 409),
    /**
     * SCRUM-474 UNLOAD-REOPEN: a vehicle of the plan was once called off under control, so the plan counts as "loading
     * started" for good: it is neither reopened nor cancelled the plain way.
     */
    LOAD_PLAN_CONTROLLED_CANCELLED("A vehicle of this load plan was called off under control: the plan can no longer be "
            + "reopened or cancelled the normal way", 409),
    /** Docs 09 BR-10 / BR-11: a placement breaks a loading rule; the field errors name each package and rule. */
    PLACEMENT_RULE_VIOLATION("A placement breaks a loading rule", 422),
    /** Docs 09 edge "bỏ kiện & tính lại": parcels were taken out of a vehicle; recalculate it before confirming. */
    LOAD_PLAN_NEEDS_RECALCULATION("A vehicle of this load plan lost parcels and must be recalculated first", 409),
    /** Docs 09 BR-09: a parcel in a load plan is not voided, re-opened or re-measured. */
    PACKAGE_IN_LOAD_PLAN("This package is in a load plan; take it out of the plan first", 409),
    /** Docs 09 edge: a parcel of another shipment, warehouse or load scanned by mistake; the message names where it belongs. */
    PACKAGE_NOT_IN_LOAD("This package is not on this load's manifest", 409),
    PACKAGE_ALREADY_LOADED("This package is already on another load", 409),
    /** Docs 09 BR-01: not sealed, in no shipment, or its shipment no longer waits for dispatch (cancelled, left). */
    PACKAGE_NOT_READY("The package is not ready to hand over", 409),
    SHIPMENT_NOT_FOR_LOAD("This shipment does not leave on this load", 409),
    SHIPMENT_ON_ANOTHER_LOAD("This shipment is on another load", 409),
    /** Docs 09 BR-03: a shipment leaves with all its parcels; take it off the manifest to let the rest go. */
    LOAD_NOT_FULLY_LOADED("Some packages on the manifest are not scanned yet", 409),
    LOAD_EMPTY("Nothing is on this load's manifest", 409),
    /** 3-2 (kltn-docs 09 BR-23): the driver signs only once the load carries the carrier's waybill number. */
    LOAD_WAYBILL_MISSING("Record the carrier's waybill number before the handover", 409),
    /** 3-2 (09 BR-23, E19): a waybill number is unique within its carrier among loads not cancelled. */
    WAYBILL_DUPLICATE("Another load of this carrier has this waybill number", 409),
    /** 3-2 (09 §4.4 (4)): an API_BOOKING carrier's number comes from its booking API, never typed or imported. */
    WAYBILL_FROM_CARRIER_API("This carrier's waybill number comes from its booking", 409),
    /** 3-2 (09 BR-29): an API_BOOKING carrier is booked through its API, never by hand. */
    CARRIER_BOOKS_THROUGH_API("This carrier is booked through its API", 409),
    /** 3-4 (09 BR-29): the carrier's API refused the order; the message carries the carrier's reason. */
    CARRIER_BOOKING_FAILED("The carrier refused the booking", 422),
    /** 3-4: the carrier has no pickup point at the load's warehouse. */
    CARRIER_PICKUP_NOT_CONFIGURED("The carrier has no pickup point at this warehouse", 422),
    /** 3-4: no service code for the load's vehicle type in the pickup's province. */
    CARRIER_SERVICE_NOT_CONFIGURED("The carrier has no service for this vehicle type here", 422),
    /** 3-4 (09 E24): a drop-off lies outside the province the carrier picks up in. */
    CARRIER_STOP_OUT_OF_AREA("A drop-off lies outside the carrier's service area", 422),
    /** 3-4: the load is being booked, or is booked, with a carrier's API already. */
    CARRIER_BOOKING_IN_PROGRESS("This load is already being booked with the carrier", 409),
    /** 3-4 (09 BR-29): the carrier would not cancel the order (the driver picked up): settle it with the carrier. */
    CARRIER_CANCEL_REFUSED("The carrier did not cancel the order", 409),
    /** 3-5 (09 §4.5): no such call to a carrier's webhook. */
    CARRIER_EVENT_NOT_FOUND("Carrier event not found", 404),
    /** 3-5 (09 §4.5 step 8): only an open error-list event is resolved, once, and not while it is being handled. */
    CARRIER_EVENT_NOT_RESOLVABLE("This carrier event cannot be resolved", 409),
    /**
     * 3-6 (09 §4.3 step 5, E17): a delivery is corrected while its order is not completed, and only one that billed
     * nothing (a billed delivery's receivable is taken back by the instalments, not here).
     */
    ORDER_DELIVERY_NOT_CORRECTABLE("This delivery can no longer be corrected", 409),
    /**
     * 4B-1 (18 BR-03, 14 BR-13): an instalment after delivery needs the customer's credit approved by accounting with a
     * limit above 0 - and that credit is not taken away while the default schedule still uses it.
     */
    PAYMENT_SCHEDULE_NEEDS_CREDIT("Paying after delivery needs credit approved by accounting", 409),
    /** 3-3 (09 §4.4): the file is not the system's template table; the message says why. */
    WAYBILL_IMPORT_INVALID_FILE("This file cannot be read as the waybill import template", 400),
    WAYBILL_IMPORT_NOT_FOUND("Waybill import not found", 404),
    LOAD_SCAN_ID_CONFLICT("This scan id was already used for another package or load", 409),
    /** SCRUM-392: parcels of a cancelled order are still on the vehicle; unload them before it is handed over. */
    LOAD_UNLOAD_PENDING("Parcels of a cancelled order are still on this vehicle; unload them first", 409),
    /**
     * SCRUM-474 UNLOAD-DESIGN point 4: a parcel of a vehicle called off is locked - not scanned onto a load, not planned,
     * not voided - until it is scanned off (or confirmed off by a warehouse manager).
     */
    PACKAGE_UNLOAD_PENDING("This package is still to be unloaded from a vehicle that was called off; scan it off first",
            409),
    /** SCRUM-474 UNLOAD-DESIGN point 3: a vehicle of the plan already left; cancel the remaining vehicles one by one. */
    LOAD_PLAN_VEHICLE_DISPATCHED("A vehicle of this load plan already left the warehouse; cancel its remaining "
            + "vehicles one by one", 409),
    /**
     * SCRUM-474 UNLOAD-DROP: a vehicle is dropped from its plan only when the plan's other vehicles all left (and at
     * least one did); before that it is booked, loaded or called off like the others.
     */
    LOAD_PLAN_OTHER_VEHICLES_NOT_LEFT("Another vehicle of this load plan has not left yet: a vehicle is dropped from "
            + "the plan only once every other vehicle left", 409),
    /**
     * SCRUM-474 UNLOAD-DROP: the vehicle carries parcels of a shipment, or of an order that may not ship in parts,
     * whose other parcels are on a vehicle that already left: they cannot be left behind for a new plan.
     */
    LOAD_PLAN_VEHICLE_SHARES_ORDER("This vehicle carries parcels of an order that also left on another "
            + "vehicle: it cannot be dropped from the plan", 409),
    OPTIMISTIC_LOCK("The record changed meanwhile, please retry", 409),
    /** Two writers reached the same row; the loser waited for the lock and gave up. Retryable. */
    LOCK_TIMEOUT("The record is busy, please retry", 409),
    DUPLICATE_KEY("A record with these values already exists", 409),
    /** Customization templates hang off variants of CUSTOMIZABLE products only (kltn-docs 01 BR-19, 13 BR-14). */
    PRODUCT_NOT_CUSTOMIZABLE("This product cannot be customized", 409),
    /** A template code is unique per variant. */
    PRINT_CONFIG_CODE_TAKEN("This variant already has a template with this code", 409),
    /** A customer SKU is issued only from an ordinary ACTIVE variant of an approved or published product (B20). */
    CUSTOMER_SKU_SOURCE_UNAVAILABLE("This variant cannot have customer SKUs now", 409),
    /** A customer holds at most 50 drafts at once (kltn-docs 12 B37); deleting one makes room. */
    DESIGN_DRAFT_LIMIT_REACHED("You already have the most drafts allowed; delete one first", 409),
    /** Only a draft is renamed, laid out or deleted (kltn-docs 12 BR-07, BR-08, BR-16). */
    DESIGN_NOT_DRAFT("Only a draft design can be changed", 409),
    /**
     * The files submitted with a design must be rendered from the layout as it is now (kltn-docs 12 BR-10): one is
     * missing or names another layout. {@code details.roles} lists them.
     */
    DESIGN_FILES_OUTDATED("Render the design's files again from its current layout", 409),
    /**
     * The print file of a design being approved is not what the press needs: not a PDF, or not one page per print
     * area of the bleed box's size (kltn-docs 12 BR-09: the approval is refused and the design stays pending).
     */
    DESIGN_PRINT_FILE_INVALID("The design's print file does not fit its template", 409),
    /** A design holds at most 100 images (Design Studio plan 2F-4). */
    DESIGN_ASSET_LIMIT_REACHED("This design already has the most images allowed", 409),
    /**
     * A quote is asked on an approved design on its own; a draft asks with its submission (kltn-docs 12 §4.8).
     */
    DESIGN_NOT_APPROVED("This design is not approved", 409),
    /** A closed design project takes no design, batch or quote request; it never reopens (kltn-docs 12 BR-22, B42). */
    DESIGN_PROJECT_ARCHIVED("This design project is closed", 409),
    /** A design project holds at most 20 designs, archived ones not counted (kltn-docs 12 B42). */
    DESIGN_PROJECT_FULL("This design project already has the most designs allowed", 409),
    /** A design project's library holds at most 30 files (kltn-docs 12 B43). */
    DESIGN_PROJECT_LIBRARY_FULL("This design project's library already has the most files allowed", 409),
    /** A design project closes only once none of its designs waits for review (kltn-docs 12 BR-22). */
    DESIGN_PROJECT_HAS_PENDING("A design of this project waits for review", 409),
    /** A design belongs to one project at most (kltn-docs 12 BR-17). */
    DESIGN_IN_ANOTHER_PROJECT("This design belongs to another project", 409),
    /** A design waiting for review neither leaves its project nor moves to another (kltn-docs 12 BR-17). */
    DESIGN_PENDING_IN_PROJECT("A design waiting for review stays in its project", 409),
    /** The same idempotency key is still being processed. The client should poll, not retry blindly. */
    IDEMPOTENT_REQUEST_IN_PROGRESS("An identical request is still being processed", 409),
    PRODUCT_CODE_ALREADY_EXISTS("A product with this code already exists", 409),
    BRAND_CODE_ALREADY_EXISTS("A brand with this code or slug already exists", 409),
    CATEGORY_CODE_ALREADY_EXISTS("A category with this code or slug already exists", 409),
    VARIANT_SKU_ALREADY_EXISTS("A variant with this SKU or attribute combination already exists", 409),
    ATTRIBUTE_CODE_ALREADY_EXISTS("An attribute or value with this code already exists", 409),
    ATTRIBUTE_IN_USE("This attribute or value is used by a product or category", 409),
    INVALID_VARIANT_TRANSITION("This variant cannot move to that status right now", 409),
    /** kltn-docs 01 §5.2: an obsolete SKU is only shipped or adjusted to clear its stock; its settings stay as they are. */
    VARIANT_OBSOLETE("This variant is obsolete; its inventory settings can no longer be changed", 409),
    INVALID_PRODUCT_STATUS_TRANSITION("This product cannot move to that status right now", 409),
    PRODUCT_AXES_LOCKED("Variant axes can only change while the product is a draft", 409),
    VARIANT_OPTIONS_INVALID("The options do not name one value per axis of this product", 400),
    VARIANT_MATRIX_TOO_LARGE("Too many variants for one generation", 400),
    SELF_APPROVAL_NOT_ALLOWED("A product cannot be approved by the person who submitted it", 409),
    CUSTOMER_EMAIL_ALREADY_EXISTS("A customer account with this email already exists", 409),
    /** kltn-docs 18 BR-01: one pending or active customer per tax code. */
    CUSTOMER_TAX_CODE_ALREADY_EXISTS("A customer with this tax code already exists", 409),
    /** kltn-docs 18 §5: only onboarding enters PENDING_ACTIVATION, only activation leaves it for ACTIVE. */
    INVALID_CUSTOMER_STATUS_TRANSITION("This customer cannot move to that status", 409),

    /** Checkout needs somewhere to deliver: no shipping address was chosen and none is the default. */
    SHIPPING_ADDRESS_REQUIRED("Add a shipping address before placing an order", 409),

    /** B2B accounts must be onboarded by Sales, not through public registration. */
    CUSTOMER_REGISTRATION_DISABLED("Public customer registration is not available", 403),
    PORTAL_INVITATION_INVALID("This portal invitation is invalid or no longer available", 400),
    /** Resending would reset a password the customer already chose. */
    PORTAL_ACCOUNT_ALREADY_ACTIVE("This customer has already activated their portal account", 409),
    /** The prefix starts every location code of the warehouse, so it is unique system-wide. */
    WAREHOUSE_PREFIX_ALREADY_EXISTS("A warehouse with this prefix already exists", 409),
    ZONE_NAME_ALREADY_EXISTS("This warehouse already has a zone with this name", 409),
    /** BR-06: something would end up outside the warehouse map. */
    LAYOUT_OUT_OF_BOUNDS("This would leave part of the layout outside the warehouse map", 409),
    SHELF_CODE_ALREADY_EXISTS("This warehouse already has a shelf with this code", 409),
    SHELF_LEVEL_ALREADY_EXISTS("This shelf already has a level with this number", 409),
    BIN_CODE_ALREADY_EXISTS("This shelf level already has a bin with this code", 409),
    AREA_CODE_ALREADY_EXISTS("This warehouse already has an area with this code", 409),
    /** Issue #18 D10: a storage area keeps its storage location for good, so it never becomes NON_STORAGE. */
    AREA_TYPE_CHANGE_NOT_ALLOWED("An area that holds stock cannot become a non-storage area", 409),
    /** BR-07: two shelves, areas or bins would cover the same floor. Touching edges is fine. */
    LAYOUT_OVERLAP("This would overlap something already on the map", 409),
    /** BR-08: a shelf with pickable bins needs at least one side a picker can reach them from. */
    PICK_FACE_REQUIRED("A shelf with pickable bins needs at least one pick face", 409),
    /** A shelf is loaded and locked as one aggregate, so it is bounded: 20 levels, 200 bins a level. */
    SHELF_CAPACITY_EXCEEDED("This shelf cannot hold that many levels or bins", 409),
    /** BR-14: bins are generated on empty levels only - an INACTIVE bin still counts, its code is taken. */
    SHELF_LEVEL_HAS_BINS("Bins can only be generated on levels that have none yet", 409),

    /** Someone saved this role's permissions after the editor loaded them. Not retryable as-is:
     *  resending the same body would overwrite their change, so the client must reload first. */
    ROLE_PERMISSIONS_CHANGED("The permissions of this role were changed by someone else; reload and try again", 409),
    /** The role's permissions are managed by migrations, not the admin screen (CUSTOMER). */
    ROLE_NOT_EDITABLE("The permissions of this role cannot be edited here", 409),
    /** The edit would leave no role able to edit permissions, and nobody could ever undo it. */
    RBAC_LOCKOUT("At least one role must keep the right to manage permissions", 409),
    USERNAME_ALREADY_EXISTS("An account with this username already exists", 409),
    USER_EMAIL_ALREADY_EXISTS("An account with this email already exists", 409),
    ROLE_CODE_ALREADY_EXISTS("A role with this code already exists", 409),
    /** A custom role still held by someone cannot be deleted; reassign them first. */
    ROLE_IN_USE("This role is still assigned to users", 409),
    /** The roles the code knows by name are never renamed or deleted. */
    SYSTEM_ROLE_IMMUTABLE("System roles cannot be renamed or deleted", 409),
    /** The change would leave no active System Admin to administer the platform. */
    LAST_SYSTEM_ADMIN("At least one active System Admin must remain", 409),
    /** CUSTOMER belongs to customer accounts only, and staff roles to staff accounts only. */
    ROLE_NOT_ASSIGNABLE("This role cannot be assigned to this account", 409),
    INVALID_USER_STATUS_TRANSITION("This account cannot move to that status", 409),
    /** The administrator asked for a status the account already has (lock a locked account, enable an active one). */
    USER_STATUS_UNCHANGED("The account is already in this status", 409),
    /** User management acts on staff accounts; customer accounts belong to the customer screens. */
    STAFF_ACCOUNT_REQUIRED("Customer accounts are managed from the customer screens", 409),

    // ---- 413 / 415: payload problems
    PAYLOAD_TOO_LARGE("The uploaded file is too large", 413),
    /** A provider webhook larger than any real delivery report: read no further (SCRUM-352). */
    WEBHOOK_PAYLOAD_TOO_LARGE("The webhook body is too large", 413),
    /** A JSON body over its route's cap (stockflow.web.body-limits): a design document past 1,000,000 bytes. */
    REQUEST_BODY_TOO_LARGE("The request body is too large", 413),
    UNSUPPORTED_MEDIA_TYPE("This file type is not accepted", 415),

    // ---- 422: understood, but semantically refused
    /** The key was reused for a DIFFERENT request. Replaying the stored response would be a lie. */
    IDEMPOTENCY_KEY_REUSED("This idempotency key was already used for a different request", 422),
    /**
     * The server's preflight refused a submitted design (kltn-docs 12 BR-05); it stays a draft.
     * {@code details.issues} lists each finding as {@code {rule, surfaceCode?, elementId?, message}}.
     */
    DESIGN_PREFLIGHT_FAILED("The design did not pass the print checks", 422),
    /**
     * A batch is submitted whole or not at all (kltn-docs 12 BR-19): {@code details.designs} lists each design that
     * did not pass, with its {@code issues} or the {@code roles} of files to render again.
     */
    DESIGN_BATCH_PREFLIGHT_FAILED("Some designs did not pass the print checks; none was submitted", 422),

    // ---- 429
    RATE_LIMITED("Too many requests", 429),

    // ---- 5xx
    INTERNAL_ERROR("Internal server error", 500),
    /** A dependency we call failed. Separated from INTERNAL_ERROR so alerting can tell them apart. */
    EXTERNAL_SERVICE_ERROR("An upstream service is unavailable", 502),
    STORAGE_ERROR("File storage is unavailable", 503),
    /** No portal activation address is configured, so no invitation link can be built. */
    PORTAL_INVITATION_UNAVAILABLE("Portal invitations cannot be sent right now", 503),
    /** The company bank account customers transfer to is not configured for this deployment (SCRUM-193). */
    TRANSFER_INSTRUCTIONS_UNAVAILABLE("Transfer details cannot be shown right now", 503),
    /** No webhook signing secret is configured, so no webhook can be verified; the provider retries later. */
    WEBHOOK_UNAVAILABLE("Webhooks are not accepted right now", 503),
    /** No key to seal stored secrets with is configured (stockflow.security.secrets.key), so none can be saved. */
    SECRETS_UNAVAILABLE("Secrets cannot be saved right now", 503),
    /** The caller's permissions could not be looked up (ADR-0008). The token itself may be fine,
     *  which is why this is a 503 and not a 401 that would sign the user out. */
    AUTHORIZATION_UNAVAILABLE("Permissions cannot be checked right now, please retry", 503);

    private final String defaultMessage;
    private final int httpStatus;

    ErrorCode(String defaultMessage, int httpStatus) {
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * Whether repeating the identical request could plausibly succeed.
     *
     * <p>Exposed so a client — or our own {@code RestClient} retry policy — can decide without
     * hard-coding a list of status codes. A 409 from an optimistic lock is worth retrying; a 409
     * from insufficient stock is not, because nothing about retrying makes stock appear.</p>
     */
    public boolean isRetryable() {
        return switch (this) {
            case OPTIMISTIC_LOCK, LOCK_TIMEOUT, RATE_LIMITED,
                 EXTERNAL_SERVICE_ERROR, STORAGE_ERROR, IDEMPOTENT_REQUEST_IN_PROGRESS,
                 AUTHORIZATION_UNAVAILABLE -> true;
            default -> false;
        };
    }

    /** 5xx means we broke; 4xx means the caller did. Used to decide log level and alerting. */
    public boolean isServerFault() {
        return httpStatus >= 500;
    }
}
