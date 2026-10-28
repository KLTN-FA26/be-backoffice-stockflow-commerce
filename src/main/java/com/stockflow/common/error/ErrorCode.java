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
    PO_DELIVERY_DATE_REQUIRED("A delivery date is required before sending", 400),
    PO_REASON_REQUIRED("A reason of 1..1000 characters is required", 400),
    PO_SUPPLIER_RESPONSE_INVALID("Supplier response must be CONFIRMED or REJECTED", 400),
    PO_LINE_DESCRIPTION_REQUIRED("A product description is required for each purchase order line", 400),
    SUPPLIER_DELIVERY_CONTACT_INVALID("Supplier delivery contact is invalid or not allowed", 400),
    SUPPLIER_PROFILE_INVALID("Invalid supplier profile or commercial terms", 400),
    // Supplier catalog field codes (SCRUM-77): each names one rule on one input of the catalog form, so
    // the field error is translated by its own code. The response's errorCode stays VALIDATION_FAILED.
    SUPPLIER_ITEM_SKU_INVALID("The SKU is not a well-formed SKU code", 400),
    SUPPLIER_ITEM_SUPPLIER_SKU_CODE_TOO_LONG("The supplier SKU code must be at most 50 characters", 400),
    SUPPLIER_ITEM_PRICE_INVALID("The price must be above zero, at most 9,999,999,999,999,999.99, and have no more"
            + " decimals than the supplier's currency allows", 400),
    SUPPLIER_ITEM_MOQ_INVALID("The minimum order quantity must be at least 1", 400),
    SUPPLIER_ITEM_PACK_SIZE_INVALID("The pack size must be at least 1", 400),
    SUPPLIER_ITEM_MOQ_NOT_PACK_MULTIPLE("The minimum order quantity must be a multiple of the pack size", 400),
    SUPPLIER_ITEM_LEAD_TIME_INVALID("The lead time must be between 0 and 365 days", 400),
    VALIDATION_FAILED("Invalid request data", 400),
    INVENTORY_POLICY_INVALID("Invalid SKU inventory policy", 400),
    /** A field-error code on {@link #INVENTORY_POLICY_INVALID}: {@code maxQty} sits below the reorder point or the safety stock. */
    INVENTORY_MAX_QTY_BELOW_THRESHOLD(
            "The order-up-to level must not be below the reorder point or the safety stock", 400),
    INVENTORY_POLICY_STOCK_CONFLICT("Existing stock is incompatible with this policy", 409),
    CATALOG_SLUG_EXISTS("This storefront URL is already reserved", 409),
    CATALOG_LISTING_REQUIRED("Configure the storefront listing before publishing", 409),
    /** Publishing, and since SCRUM-461 a purchase order line too (the field error carries this code): the product is not approved. */
    PRODUCT_NOT_APPROVED("The product is not approved", 409),
    PRODUCT_CATEGORY_REQUIRED("A valid product category is required", 409),
    PRODUCT_SKU_REQUIRED("At least one sellable SKU is required", 409),
    PRODUCT_GALLERY_REQUIRED("An approved non-empty product gallery is required", 409),
    PRODUCT_NOT_PURCHASABLE("This product is not currently available for purchase", 409),
    /** SCRUM-461, kltn-docs 02 BR-01: a new purchase order line takes only an active SKU. {@code fieldErrors[]} names each line. */
    SKU_NOT_PURCHASABLE("The SKU is not active for purchasing", 409),
    /** Review of #109, item 6: a quote, its acceptance or its order, or a new sample, on a SKU no longer ACTIVE. */
    SKU_NOT_SELLABLE("The SKU is no longer active; change the line to an active SKU", 409),
    /** SCRUM-461, kltn-docs 01 §3: a product is identified by its brand too, so it is chosen before approval. */
    PRODUCT_BRAND_REQUIRED("A brand is required before submitting for approval", 409),
    /** SCRUM-461, kltn-docs 01 BR-02: only a draft is deleted. */
    PRODUCT_NOT_DELETABLE("Only a draft product can be deleted", 409),
    /** SCRUM-461: stock, a purchase order, an order or a design already points at the product. */
    PRODUCT_IN_USE("The product is referenced by stock, purchasing, orders or designs", 409),
    PRICE_NOT_AVAILABLE("No selling price is available for this SKU", 409),
    /** kltn-docs 13 BR-05: the first tier of a customer's own price is its minimum order quantity. */
    /** Direct checkout never falls back to another price below the list's first tier (P4): Sales quotes it. */
    CUSTOMER_PRICE_BELOW_MOQ("The quantity is below this customer's price list minimum for the SKU; ask Sales for a quote", 409),
    CUSTOMER_PRICE_NOT_FOUND("This customer has no own price for the SKU", 404),
    /** P7: a customer group (customer.segment) that does not exist. */
    SEGMENT_NOT_FOUND("Customer group not found", 404),
    SEGMENT_PRICE_NOT_FOUND("This customer group has no price for the SKU", 404),
    CATALOG_PRICE_AMBIGUOUS("More than one selling price has the same priority", 409),
    MALFORMED_REQUEST("The request body could not be read", 400),
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

    // ---- 404
    NOT_FOUND("Resource not found", 404),
    ROLE_NOT_FOUND("Role not found", 404),
    USER_NOT_FOUND("User not found", 404),
    SUPPLIER_NOT_FOUND("Supplier not found", 404),
    SUPPLIER_ITEM_NOT_FOUND("Supplier item not found", 404),
    PURCHASE_ORDER_NOT_FOUND("Purchase order not found", 404),
    PRODUCT_NOT_FOUND("Product not found", 404),
    CATEGORY_NOT_FOUND("Category not found", 404),
    BRAND_NOT_FOUND("Brand not found", 404),
    VARIANT_NOT_FOUND("Variant not found", 404),
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
    /** An alert of a warehouse outside the caller's scope is {@code OUT_OF_DATA_SCOPE}, not this. */
    ALERT_NOT_FOUND("Stock alert not found", 404),
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
    RECEIVABLE_NOT_FOUND("Receivable not found", 404),
    RESERVATION_NOT_FOUND("Stock reservation not found", 404),
    TRANSFER_ORDER_NOT_FOUND("Transfer order not found", 404),
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
    /** An assisted design names a customer and an owner account; the owner must be that customer's own login. */
    DESIGN_OWNER_CUSTOMER_MISMATCH("The design owner does not belong to this customer", 400),

    // ---- 409: the request is fine, the current state is not
    PO_COMMUNICATION_NOT_CONFIGURED("Buyer contact and receiving address must be configured before sending", 409),
    CONFLICT("Conflicting state", 409),
    /** SCRUM-78: the envelope of a purchase order whose lines break the supplier's catalog; each line carries its own field code. */
    SUPPLIER_TERMS_VIOLATED("One or more lines do not meet the supplier's catalog terms", 409),
    SUPPLIER_ITEM_NOT_LISTED("This supplier does not list this SKU", 409),
    PO_LINE_BELOW_MOQ("The quantity is below the supplier's minimum order quantity", 409),
    PO_LINE_NOT_PACK_MULTIPLE("The quantity must be a multiple of the supplier's pack size", 409),
    /** A line entered in a packing unit is a whole number of that unit (BR-09, V20261020000400). */
    PO_LINE_UNIT_NOT_MULTIPLE("The quantity must be a whole number of the line's unit", 409),
    PO_LINE_PRICE_REQUIRED("A unit price is required: the catalog price is in another currency", 409),
    CHECKOUT_PRICE_CHANGED("The selling price changed; review the basket and confirm again", 409),
    COUNT_POLICY_CONFLICT("Reconcile the active count and SKU tracking policy before proceeding",409),
    /** The inventory item already has a unit with this code (its base unit included). */
    UOM_CONVERSION_DUPLICATE("This SKU already has a unit of measure with this code", 409),
    /**
     * The base unit, or a unit a variant sells in, cannot be deleted or retired (docs 01 BR-10, BR-11); a unit
     * a document counts in keeps its factor and code and is not deleted (BR-10, V20261020000600).
     */
    UOM_CONVERSION_IN_USE("This unit of measure is the base unit, is still sold in, or is used by a document", 409),
    /** The variant already sells in this unit. */
    SALES_UNIT_DUPLICATE("The variant already sells in this unit", 409),
    DESIGN_QUOTE_REQUIRED("An accepted design quote matching this order line is required",409),
    INSUFFICIENT_STOCK("Not enough available stock", 409),
    /** The destination already holds this SKU and lot in another status (QC state does not merge). */
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
    /** Docs 11 BR-01: stock changes warehouse only through a transfer order (SCRUM-459). */
    MOVE_ACROSS_WAREHOUSES("Stock moves to another warehouse only through a transfer order", 409),
    /** Four eyes: whoever asked for a stock adjustment may not decide it. */
    ADJUSTMENT_SELF_APPROVAL("A stock adjustment cannot be decided by the person who requested it", 409),
    INVALID_ADJUSTMENT_TRANSITION("This stock adjustment has already been decided", 409),
    INVALID_TRANSFER_TRANSITION("This transfer order cannot move to that status right now", 409),
    /** The life of a stock package (V20261020000100): RECEIVING → IN_STOCK | VOID, IN_STOCK → CONSUMED | LOST, LOST → IN_STOCK. */
    INVALID_STOCK_PACKAGE_TRANSITION("This stock package cannot do that in its current status", 409),
    /** Four eyes on a transfer above the approval threshold. */
    TRANSFER_SELF_APPROVAL("A transfer order cannot be decided by the person who submitted it", 409),
    /** BR-01 (docs 03): goods are received only against a CONFIRMED or PARTIALLY_RECEIVED purchase order. */
    PURCHASE_ORDER_NOT_RECEIVABLE("Goods cannot be received against this purchase order now", 409),
    INVALID_RECEIPT_TRANSITION("This goods receipt cannot move to that status right now", 409),
    /** BR-02: the PO line would be received beyond the supplier's over-receipt tolerance. */
    OVER_RECEIPT_TOLERANCE("The quantity exceeds what the purchase order line allows", 409),
    /** The location exists but is not the kind of area this step needs (RECEIVING, QUALITY_CONTROL,
     *  QUARANTINE), or is in another warehouse. */
    LOCATION_AREA_MISMATCH("The location is not an area of the right kind in this warehouse", 409),
    /** BR-03: a lot-tracked or expiry-tracked item was received without its lot or expiry date, or an
     *  untracked one with a lot. */
    RECEIPT_LOT_DATA_INVALID("Lot or expiry data does not match how the item is tracked", 409),
    /** BR-08: accepted + quarantined + rejected must add up to what was moved to the QC area. */
    QC_QUANTITY_MISMATCH("The QC quantities do not add up to the quantity inspected", 409),
    /** A line needs QC and the warehouse has no QUALITY_CONTROL area to send it to (docs 03 edge case). */
    QC_AREA_MISSING("This warehouse has no QC area yet for the items that need QC", 409),
    /** Two receipt lines for the same PO line and lot (uk_goods_receipt_lines_lot). */
    DUPLICATE_RECEIPT_LINE("The same purchase order line and lot appear twice on this receipt", 409),
    RECEIPT_LINE_NOT_ON_PO("This line is not a line of the receipt's purchase order", 409),
    /**
     * SCRUM-473: a receipt line that has ever had labelled cartons - VOID ones too, which still point at it
     * ({@code fk_stock_package_receipt_line}) - keeps its PO line and lot and cannot be removed from the draft, and
     * its unit cannot change while it keeps cartons in the old one. A lot or PO line on such a line is corrected by
     * cancelling the receipt and counting it again.
     */
    RECEIPT_LINE_HAS_PACKAGES("This receipt line has labelled cartons", 409),
    /** SCRUM-473: the cartons counted on a receipt line hold more units than the line received. */
    RECEIPT_PACKAGES_EXCEED_LINE("The cartons hold more than the receipt line", 422),
    /**
     * SCRUM-473 decision D5: a SUBCONTRACT order's goods are held for their production order one row per receipt
     * line, so they are not received into cartons.
     */
    RECEIPT_PACKAGES_NOT_SUPPORTED("Cartons are not labelled on a subcontract receipt", 409),
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
    /** SCRUM-92: the bin's storage class is not the one the item must be kept in. */
    LOCATION_STORAGE_CLASS_MISMATCH("The storage class of this bin does not fit the item", 409),
    /** SCRUM-92: the putaway would take the bin past its capacity in units or its weight limit. */
    LOCATION_CAPACITY_EXCEEDED("This bin cannot take that many units or that much weight", 409),
    /**
     * SCRUM-473: a line with cartons names the active packing unit they are in; a loose line counts in the base unit
     * and claims no packing unit it holds no carton of.
     */
    RECEIPT_LINE_UNIT_INVALID("A receipt line counts in its item's base unit, or in an active packing unit it has cartons of", 422),
    /** The PO line is closed, cancelled or fully received: nothing more is received against it. */
    PO_LINE_NOT_OPEN("This purchase order line is no longer open for receiving", 409),
    /** One stock row per SKU, location and lot: quarantined and rejected goods need two areas. */
    QC_LOCATIONS_MUST_DIFFER("Quarantined and rejected goods go to two different quarantine areas", 409),
    QC_REASON_REQUIRED("Quarantined or rejected goods need a reason (BR-08)", 400),
    QC_TARGET_LOCATION_REQUIRED("Quarantined or rejected goods need the quarantine area they go to", 400),
    RECEIPT_LINE_NOT_FOUND("This receipt has no such line", 404),
    /** SCRUM-434: a SUBCONTRACT purchase order goes only to a supplier flagged print subcontractor. */
    SUPPLIER_NOT_SUBCONTRACTOR("This supplier is not a print subcontractor", 409),
    /** BR-PRD-08: a new design goes to print only after the customer approved its sample. */
    DESIGN_SAMPLE_NOT_APPROVED("A design on this order has no approved sample yet", 409),
    /** SCRUM-394: a sample round moves only along 03-state-machines §17. */
    INVALID_SAMPLE_REQUEST_TRANSITION("This sample request cannot move to that status right now", 409),
    /** BR-ORD-007 / BR-CUS-003: new work (a sample round, a quote, an order) is only for an ACTIVE customer. */
    CUSTOMER_NOT_ACTIVE("This customer is not active and cannot take on new work", 409),
    /** SCRUM-394: a sample linked to a quote prints a design and blank of that quote, for its customer. */
    SAMPLE_QUOTE_MISMATCH("The quote is not for this customer, design and blank", 409),
    /** SCRUM-394: a sample is linked only to a quote still being negotiated. */
    QUOTE_NOT_OPEN("The quote is no longer open", 409),
    /** SCRUM-394: a round that ended in CHANGES_REQUESTED is followed by exactly one next round. */
    SAMPLE_NEXT_ROUND_EXISTS("The next round of this sample request has already been started", 409),
    /** SCRUM-396..401: a production order moves only along 03-state-machines §16. */
    INVALID_PRODUCTION_TRANSITION("This production order cannot move to that status right now", 409),
    /** BR-PRD-02: the print file no longer matches the design the order locked. */
    PRINT_FILE_MISMATCH("The print file does not match the locked design", 409),
    /** SCRUM-397: a hold issued to production is live, its owner's, and no longer waits for payment. */
    RESERVATION_NOT_ISSUABLE("This stock hold cannot be issued", 409),
    /** BR-PRD-03: never more blanks than the production order still needs. */
    ISSUE_EXCEEDS_REQUIREMENT("More blanks than the production order still needs", 409),
    /** Review of #109: goods past their expiry cannot move to a new location; they are written off (EXPIRED). */
    STOCK_EXPIRED("This stock is past its expiry date and can only be written off", 409),
    /** SCRUM-409: the location scanned at a blank issue is not where the hold's goods are. */
    ISSUE_LOCATION_MISMATCH("The scanned location is not where this hold's blanks are", 409),
    /** SCRUM-409: the SKU or barcode scanned at a blank issue is not the hold's. */
    ISSUE_ITEM_MISMATCH("The scanned item is not this hold's SKU", 409),
    /** BR-PRD-01: a source already has a production order, and it describes different work. */
    PRODUCTION_SOURCE_CONFLICT("A production order already exists for this source with different content", 409),
    /** SCRUM-448: a quote moves only along 03-state-machines §18. */
    INVALID_QUOTE_TRANSITION("This quote cannot move to that status right now", 409),
    /** BR-ORD-008: a quote is answered only within its validity. */
    QUOTE_EXPIRED("This quote is no longer valid", 409),
    /** BR-ORD-007: a quoted line is ordered at or above its minimum order quantity. */
    QUOTE_BELOW_MOQ("The quantity is below the minimum order quantity of the quote", 409),
    /** Reorder: only an order placed from a quote is re-quoted; a price-list order is simply placed again. */
    /** R1: a reorder line the price list cannot price waits for Sales; nothing is sent or accepted unpriced. */
    QUOTE_NOT_PRICED("A line of this quote has no price yet; Sales enters it before the quote is sent", 409),
    ORDER_NOT_QUOTED("This order was not placed from a quote; order it again at the current price", 409),
    /** BR-PRD-09: a deposit order goes to production only once the deposit has arrived. */
    ORDER_DEPOSIT_NOT_RECEIVED("The deposit for this order has not arrived yet", 409),
    /** SCRUM-460: from IN_PRODUCTION on, a customer asks and Sales or the coordinator decides. */
    ORDER_NOT_CANCELLABLE("This order can no longer be cancelled; raise a return instead", 409),
    /** Audit of #109 (decision 2026-10-17): before printing a cancellation is refunded in full - nothing is kept. */
    CANCELLATION_FEE_BEFORE_PRINTING("Printing has not started: the order is refunded in full, no fee is kept", 409),
    /** Work for an order that has been cancelled (printing started, a package completed) is refused. */
    ORDER_CANCELLED("This order is cancelled", 409),
    /**
     * Review 2026-10-25 of S6: production completes a print line short only within what the customer agreed with Sales,
     * recorded on the order line.
     */
    PRODUCTION_SHORTFALL_NOT_AGREED("The customer has not agreed to go without these units: Sales records it on the "
            + "order first", 409),
    /** The same review: who recorded the customer's agreement does not also close the shortfall (four eyes). */
    PRODUCTION_SHORTFALL_SELF_APPROVAL("The customer's agreement was recorded by you: someone else completes the job "
            + "short", 409),
    /** Decision 2026-10-17: once printing started, only staff cancel an order (fee, blanks, printed goods). */
    CANCELLATION_NEEDS_STAFF("Printing has started: staff decide this order's cancellation", 409),
    ORDER_CANCELLATION_REQUEST_PENDING("A cancellation request for this order is already waiting for a decision", 409),
    ORDER_CANCELLATION_REQUEST_NOT_PENDING("This cancellation request has already been decided", 409),
    PAYMENT_TERM_NOT_ALLOWED("This payment term is not available for this order", 409),
    /** SCRUM-427: credit approval or refusal on an order that is not waiting for one. */
    ORDER_NOT_ON_CREDIT_HOLD("This order is not waiting for a credit decision", 409),
    /** G3-2 (SCRUM-246/247): the order's lifecycle has no edge from its status to the one asked for. */
    ORDER_INVALID_TRANSITION("This order cannot move to that status now", 409),
    /** G3-2: resolving a hold on an order that is not on hold. */
    ORDER_NOT_ON_HOLD("This order is not on hold", 409),
    /** G3-2: a credit hold is decided through credit approval, not resolved like the others. */
    ORDER_CREDIT_HOLD_USE_APPROVAL("A credit hold is decided through credit approval", 409),
    /** G3-2 review: a design-mismatch hold is resolved by QC reverifying the package, which releases both. */
    ORDER_DESIGN_HOLD_USE_REVERIFICATION("A design hold is resolved by reverifying the package", 409),
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
    /** SCRUM-431, kltn-docs 15 BR-07: one bank statement line is recorded once. */
    TRANSFER_REFERENCE_ALREADY_RECORDED("This transfer was already recorded", 409),
    ALLOCATION_CUSTOMER_MISMATCH("This receivable or order belongs to another customer", 409),
    /** SCRUM-431, kltn-docs 15 §4.3 step 4: an overdue customer gets no new credit until they pay. */
    CUSTOMER_CREDIT_OVERDUE("The customer has overdue receivables", 409),
    /** G2-3 (the money ledger): no recorded transfer by that id. */
    TRANSFER_NOT_FOUND("Transfer not found", 404),
    /** G2-3: no allocation by that id on this transfer. */
    ALLOCATION_NOT_FOUND("Allocation not found", 404),
    /** G2-3: more than the transfer still has unallocated (15 BR-05: money is never spent twice). */
    ALLOCATION_EXCEEDS_TRANSFER("More than this transfer has left to allocate", 409),
    /** G2-3: more than the order may still take - its value less what it holds, owes on receivables or pays the carrier. */
    ALLOCATION_EXCEEDS_AMOUNT_DUE("More than this order still has to be paid", 409),
    /** G2-3, 15 edge "tiền về sau khi đơn đã huỷ": the money stays the customer's credit, for another order or a refund. */
    ALLOCATION_ORDER_CANCELLED("This order is cancelled: keep the money as the customer's credit or refund it", 409),
    /** G2-3: a draft, settled or credit order takes no money by allocation (a credit order is paid on its receivables). */
    ALLOCATION_ORDER_NOT_PAYABLE("This order does not take a payment now", 409),
    /** G2-3, 15 BR-06: an allocation is reversed once, and a reversal is not reversed. */
    ALLOCATION_ALREADY_REVERSED("This allocation was already reversed", 409),
    /** G2-3: taking the money back would leave a confirmed order unpaid; the accountant must agree to hold it. */
    PAYMENT_REVERSAL_NEEDS_HOLD("Taking this money back leaves a confirmed order unpaid: confirm putting it on hold", 409),
    /** G2-3: money already refunded, or the order settled, closed or cancelled: the reversal would rewrite history. */
    PAYMENT_REVERSAL_NOT_ALLOWED("This payment can no longer be taken back", 409),
    /** G2-3 (15 §4.2 step 3): the customer's terms do not let the balance be collected that way. */
    BALANCE_POLICY_NOT_ALLOWED("This customer may not use that way of paying the balance", 409),
    /**
     * BALCREDIT (lead's decision 2026-10-21): a deposit order's balance on credit would take the customer past their
     * credit limit, or they are overdue. Refused at placement, never held: Sales changes the terms.
     */
    BALANCE_CREDIT_LIMIT_EXCEEDED("The balance on credit would exceed the customer's credit limit", 409),
    /** PACK-PARTIAL (lead's decision 2026-10-21): the order does not allow a partial shipment; Sales decides. */
    ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED("This order does not allow partial shipments", 409),
    /** PACK-PARTIAL: whether the order may ship in parts can no longer change once everything left or it is closed. */
    ORDER_PARTIAL_SHIPMENT_LOCKED("Partial shipment can no longer be changed on this order", 409),
    /** LP-1 (lead's decision 2026-10-22): the delivery mode is locked once packing of the order started. */
    DELIVERY_MODE_LOCKED("The delivery mode can no longer be changed on this order", 409),
    /**
     * LP-2 (lead's decision 2026-10-22): a full truckload is never paid by a carrier collecting on delivery
     * ({@code ck_shipment_ftl_no_cod}); Sales changes the balance policy, or the order stays a parcel shipment.
     */
    DELIVERY_MODE_FTL_NO_COD("A full-truckload order cannot have its balance collected on delivery", 409),
    /** G2-3: a carrier remittance names a receivable the customer pays themselves. */
    RECEIVABLE_NOT_CARRIER_COLLECTED("This receivable is not collected by a carrier", 409),
    /** G2-3: the customer paid this balance to the carrier; record the carrier's remittance instead. */
    RECEIVABLE_CARRIER_COLLECTED("The carrier collects this amount; record the carrier's remittance instead", 409),
    /** G2-3: nothing is outstanding on this receivable; money for it again would be collected twice. */
    RECEIVABLE_SETTLED("This receivable is paid in full", 409),
    /** SCRUM-466: a task is picked in one warehouse; the order's stock is held in more than one, or elsewhere. */
    FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE("Stock held for this order is in another warehouse", 409),
    /** SCRUM-267 (docs 07 step 6): the bin scanned is not the one this pick line takes its goods from. */
    PICK_LOCATION_MISMATCH("The scanned location is not this line's", 409),
    /** SCRUM-274: the code scanned is neither this line's SKU nor its barcode. */
    PICK_ITEM_MISMATCH("The scanned item is not this line's SKU", 409),
    /** Docs 07 BR-04: never more than the line's allocation; more needs a re-allocation. */
    PICK_QUANTITY_EXCEEDS_ALLOCATION("More than allocated to this line", 409),
    /** Fewer than allocated: a short pick, reported through the short-pick flow (SCRUM-268). */
    PICK_QUANTITY_SHORT("Fewer than allocated - report a short pick", 409),
    PICK_LINE_NOT_OPEN("This line is no longer open", 409),
    /** A cycle count already completed or cancelled. */
    CYCLE_COUNT_NOT_OPEN("This cycle count is no longer open", 409),
    /** A planned count whose scope resolves to no stock slot. */
    CYCLE_COUNT_EMPTY_SCOPE("The count scope matches no stock", 422),
    /** A planned count above stockflow.inventory.cycle-count-max-lines. */
    CYCLE_COUNT_TOO_LARGE("The count scope is too large - narrow it", 422),
    /** A counter who is not the assignee and may not decide. */
    CYCLE_COUNT_NOT_ASSIGNEE("This count is assigned to someone else", 403),
    /** Submitting a count that still has uncounted lines. */
    CYCLE_COUNT_LINE_NOT_COUNTED("Some lines are not counted yet", 409),
    /** A slot already held by an open count. */
    CYCLE_COUNT_SLOT_BUSY("This stock slot is already on an open count", 409),
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
    /** Docs 07 step 9: a pick task closes only when every line is picked. */
    PICK_LINES_INCOMPLETE("Some lines are not picked yet", 409),
    PICK_TASK_STATE_CONFLICT("The task cannot do that in its current status", 409),
    /** The hold behind a pick line was released, expired or already consumed: nothing is deducted. */
    RESERVATION_NOT_ACTIVE("The stock hold is no longer active", 409),
    /** A production order's hold, or goods in the print shop: production closes them, not a pick (review of #109). */
    PRODUCTION_HOLD_NOT_PICKABLE("This stock hold belongs to production and cannot be picked", 409),
    /** Docs 07 BR-05: only goods picked for an order cancelled afterwards go back to a bin. */
    PUT_BACK_NOT_REQUIRED("Nothing on this line needs putting back", 409),
    PUT_BACK_LOCATION_INVALID("Put back onto an active bin of the task's warehouse", 409),
    /** G4-5 (docs 08 step 1): packing starts once picking is complete. */
    PACKING_NOT_READY("Picking must finish before packing starts", 409),
    /** G4-5: another packer took this order at the packing table; a warehouse manager may step in. */
    PACKING_TAKEN("Another packer is packing this order", 409),
    PACK_STATE_CONFLICT("Packing cannot do that in its current status", 409),
    /** Docs 08 step 2, BR-01: the scanned item is not on the order (a wrong item). */
    PACK_ITEM_NOT_IN_ORDER("This item is not in the order", 409),
    /** The scanned code is on two lines of the order (a plain and a printed cup of one SKU): name the line. */
    /** G4-5: a scan id already recorded on another package or task: the client reused it; send a new one. */
    PACK_SCAN_ID_CONFLICT("This scan id was already used for another package", 409),
    PACK_SCAN_AMBIGUOUS("This item is on several lines of the order: name the line", 409),
    /** Docs 08 BR-01: an over-scan, more than the order line still wants. */
    PACK_QUANTITY_EXCEEDS_ORDER("More than the order line still wants", 409),
    /** More than was picked (or produced) for the line: the rest has not reached packing. */
    PACK_QUANTITY_EXCEEDS_PICKED("More than reached packing for this line", 409),
    /** Docs 08 BR-01: the lot scanned was not picked for this line, or more of it than was picked. */
    PACK_LOT_MISMATCH("This lot was not picked for this line", 409),
    /** Docs 08 step 2: the serial scanned was not picked for this line, or is packed already. */
    PACK_SERIAL_MISMATCH("This serial number was not picked for this line, or is packed already", 409),
    PACKAGE_SEALED("The package is already sealed", 409),
    PACKAGE_EMPTY("The package has nothing in it", 409),
    /** Docs 08 BR-03, 09 BR-01: a package with goods in it is weighed, measured and sealed before it ships. */
    PACKAGE_NOT_SEALED("A package is still open: seal it or take it apart", 409),
    PACKAGE_SHIPPED("The package is in a shipment already", 409),
    /** Docs 08 BR-01: everything the order still wants must be packed, or a partial shipment asked for. */
    PACKING_INCOMPLETE("Not everything is packed yet", 409),
    /** PACK-PARTIAL (lead's decision 2026-10-21): the order does not allow a partial shipment; Sales decides. */
    PACK_PARTIAL_NOT_ALLOWED("Partial shipments are not allowed", 409),
    PACK_NOTHING_TO_SHIP("No sealed package is waiting to ship", 409),
    /** SCRUM-287 LP2-6: a kit ("bộ") ships whole - a part of it is not sealed, or part 1 holds no unit yet. */
    KIT_INCOMPLETE("A kit has parts that are not sealed yet", 409),
    /** LP2-5: a kit has as many parts as its item ships boxes ({@code package_count}); one box is no kit. */
    KIT_PART_COUNT_MISMATCH("The kit's parts do not match how many boxes the item ships as", 422),
    /** LP2-6: only part 1 of a kit holds goods, and one unit of its item. */
    KIT_CONTENTS_INVALID("Only part 1 of a kit holds goods: one unit of its item", 409),
    /** LP2-6 (review of #155): part 1 of a kit takes the item of the line the kit was opened for, no other. */
    KIT_ITEM_MISMATCH("This kit was opened for another item", 422),
    /** KIT-FORCE (lead's decision 2026-10-22): an item that ships as several boxes goes in an ordinary package only with a reason. */
    KIT_OVERRIDE_REASON_REQUIRED("This item ships as a kit: give a reason to pack it in an ordinary package", 422),
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
    /** SCRUM-287 PR T (LP2-8): only a transfer that travels as a full truckload is packed for a load plan. */
    TRANSFER_NOT_FULL_TRUCKLOAD("This transfer order does not travel as a full truckload", 409),
    /** Docs 10 BR-04: goods of the transfer left on a load plan's vehicle; its picking is no longer cancelled. */
    TRANSFER_GOODS_LEFT("Goods of this transfer order already left the source warehouse", 409),
    /** SCRUM-287 PR T: a transfer packed for a load plan is dispatched by the plan's last vehicle, not by hand. */
    TRANSFER_DISPATCH_BY_LOAD_PLAN("This transfer order is dispatched by its load plan's last vehicle", 409),
    /** Docs 09 BR-09 / LP2-1: only a packed transfer goes into a load plan. */
    TRANSFER_NOT_PACKED("This transfer order is not packed for dispatch", 409),
    /** Docs 09 §4.2 step 1 / 10 step 5: a transfer leaves whole with its load plan; nothing of it stays behind. */
    TRANSFER_SHIPS_WHOLE("A transfer order leaves whole with its load plan", 409),
    /** Docs 09 §3 LoadPlan.source_type: a plan holds customer orders' shipments or one transfer order, not both. */
    LOAD_PLAN_SOURCE_MISMATCH("This load plan holds another kind of source", 409),
    /** LP2-8: the stock carton is not an unopened carton of this transfer's goods at the source warehouse. */
    STOCK_CARTON_NOT_SHIPPABLE("This stock carton cannot ship as it is", 409),
    /** Docs 09 BR-10 / BR-11: a placement breaks a loading rule; the field errors name each package and rule. */
    PLACEMENT_RULE_VIOLATION("A placement breaks a loading rule", 422),
    /** Docs 09 edge "bỏ kiện & tính lại": parcels were taken out of a vehicle; recalculate it before confirming. */
    LOAD_PLAN_NEEDS_RECALCULATION("A vehicle of this load plan lost parcels and must be recalculated first", 409),
    /** A load of a load plan's vehicle changes only through its plan (reopen, cancel, recalculate). */
    LOAD_MANAGED_BY_PLAN("This load belongs to a load plan; change it through the plan", 409),
    /** Docs 09 BR-09: a parcel in a load plan is not voided, re-opened or re-measured. */
    PACKAGE_IN_LOAD_PLAN("This package is in a load plan; take it out of the plan first", 409),
    /** Docs 09 edge: a parcel of another shipment, warehouse or load scanned by mistake; the message names where it belongs. */
    PACKAGE_NOT_IN_LOAD("This package is not on this load's manifest", 409),
    PACKAGE_ALREADY_LOADED("This package is already on another load", 409),
    /** Docs 09 BR-01: not sealed, in no shipment, or its shipment no longer waits for dispatch (cancelled, left). */
    PACKAGE_NOT_READY("The package is not ready to hand over", 409),
    PACKAGE_OTHER_CARRIER("This package goes with another carrier", 409),
    /** Docs 09 BR-08: a full truckload is loaded from its load plan, not onto a parcel load. */
    PACKAGE_REQUIRES_LOAD_PLAN("Full-truckload packages go through a load plan", 409),
    SHIPMENT_NOT_FOR_LOAD("This shipment does not leave on this load", 409),
    SHIPMENT_OTHER_CARRIER("This shipment goes with another carrier", 409),
    SHIPMENT_ON_ANOTHER_LOAD("This shipment is on another load", 409),
    /** Docs 09 BR-03: a shipment leaves with all its parcels; take it off the manifest to let the rest go. */
    LOAD_NOT_FULLY_LOADED("Some packages on the manifest are not scanned yet", 409),
    LOAD_EMPTY("Nothing is on this load's manifest", 409),
    /** Docs 09 BR-04: the COD the order fixes at the handover is not the one on the parcels' label: reprint it. */
    LABEL_OUTDATED("The package label shows another COD amount than the order now fixes; reprint it", 409),
    LOAD_SCAN_ID_CONFLICT("This scan id was already used for another package or load", 409),
    /** SCRUM-392: parcels of a cancelled order are still on the vehicle; unload them before it is handed over. */
    LOAD_UNLOAD_PENDING("Parcels of a cancelled order are still on this vehicle; unload them first", 409),
    OPTIMISTIC_LOCK("The record changed meanwhile, please retry", 409),
    /** Two writers reached the same row; the loser waited for the lock and gave up. Retryable. */
    LOCK_TIMEOUT("The record is busy, please retry", 409),
    DUPLICATE_KEY("A record with these values already exists", 409),
    /** The same idempotency key is still being processed. The client should poll, not retry blindly. */
    IDEMPOTENT_REQUEST_IN_PROGRESS("An identical request is still being processed", 409),
    /** BR-PO: a new PO cannot be raised against a supplier that is not ACTIVE. */
    SUPPLIER_INACTIVE("This supplier cannot receive new purchase orders", 409),
    SUPPLIER_HAS_OPEN_PURCHASE_ORDERS("Supplier has open purchase orders", 409),
    SUPPLIER_CODE_ALREADY_EXISTS("A supplier with this code already exists", 409),
    SUPPLIER_TAX_CODE_ALREADY_EXISTS("A supplier with this tax code already exists", 409),
    SUPPLIER_ITEM_DUPLICATE("This supplier already lists this SKU", 409),
    SUPPLIER_ITEM_IN_USE("An open purchase order still orders this SKU from this supplier", 409),
    INVALID_SUPPLIER_CONFIRMATION("This supplier confirmation cannot be recorded", 409),
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
    /**
     * kltn-docs 02 §4 step 7 (owner decision SCRUM-124): the approver's approval limit - the highest of their roles'
     * {@code procurement.approval_limits} in the order's currency - is below the order's total after tax. A business
     * rule on a request that is otherwise allowed, hence 409 and not 403 (403 means a missing permission): the order
     * waits for someone whose limit covers it. Never raised for an approver with no limit configured (PO-NOLIMIT).
     */
    PO_APPROVAL_LIMIT_EXCEEDED("The purchase order total is above your approval limit", 409),
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
    /** Guest checkout is permanently disabled by the B2B-only decision. */
    GUEST_CHECKOUT_DISABLED("Guest checkout is not available", 403),
    INVALID_PURCHASE_ORDER_TRANSITION("This purchase order cannot move to that status right now", 409),
    /** Only a DRAFT standard order is edited in place; afterwards changes go through a revision (BR-02, BR-10). */
    PURCHASE_ORDER_NOT_EDITABLE("Only a draft standard purchase order can be edited", 409),
    /** Only a DRAFT transfer is edited; a rejected one is back in DRAFT for that (docs 10 §5). */
    TRANSFER_ORDER_NOT_EDITABLE("Only a draft transfer order can be edited", 409),
    /** The warehouse exists but is not ACTIVE: nothing new is received into or sent from it (SCRUM-390). */
    WAREHOUSE_INACTIVE("This warehouse is not active", 409),
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
    INVALID_ALERT_TRANSITION("This alert cannot be acknowledged in its current state", 409),
    /** User management acts on staff accounts; customer accounts belong to the customer screens. */
    STAFF_ACCOUNT_REQUIRED("Customer accounts are managed from the customer screens", 409),

    // ---- 413 / 415: payload problems
    PAYLOAD_TOO_LARGE("The uploaded file is too large", 413),
    UNSUPPORTED_MEDIA_TYPE("This file type is not accepted", 415),

    // ---- 422: understood, but semantically refused
    /** The key was reused for a DIFFERENT request. Replaying the stored response would be a lie. */
    IDEMPOTENCY_KEY_REUSED("This idempotency key was already used for a different request", 422),

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
