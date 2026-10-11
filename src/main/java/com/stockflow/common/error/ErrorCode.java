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
    VALIDATION_FAILED("Invalid request data", 400),
    INVENTORY_POLICY_INVALID("Invalid SKU inventory policy", 400),
    INVENTORY_POLICY_STOCK_CONFLICT("Existing stock is incompatible with this policy", 409),
    CATALOG_SLUG_EXISTS("This storefront URL is already reserved", 409),
    CATALOG_LISTING_REQUIRED("Configure the storefront listing before publishing", 409),
    PRODUCT_NOT_APPROVED("Approve the product before publishing", 409),
    PRODUCT_CATEGORY_REQUIRED("A valid product category is required", 409),
    PRODUCT_SKU_REQUIRED("At least one sellable SKU is required", 409),
    PRODUCT_GALLERY_REQUIRED("An approved non-empty product gallery is required", 409),
    PRODUCT_NOT_PURCHASABLE("This product is not currently available for purchase", 409),
    PRICE_NOT_AVAILABLE("No selling price is available for this SKU", 409),
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
    PURCHASE_ORDER_NOT_FOUND("Purchase order not found", 404),
    PRODUCT_NOT_FOUND("Product not found", 404),
    CATEGORY_NOT_FOUND("Category not found", 404),
    BRAND_NOT_FOUND("Brand not found", 404),
    VARIANT_NOT_FOUND("Variant not found", 404),
    MEDIA_NOT_FOUND("Media not found", 404),
    CUSTOMER_NOT_FOUND("Customer not found", 404),
    ADDRESS_NOT_FOUND("Address not found", 404),
    WAREHOUSE_NOT_FOUND("Warehouse not found", 404),
    GOODS_RECEIPT_NOT_FOUND("Goods receipt not found", 404),
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
    STOCK_ADJUSTMENT_NOT_FOUND("Stock adjustment not found", 404),
    CANCELLATION_REQUEST_NOT_FOUND("Cancellation request not found", 404),
    RECEIVABLE_NOT_FOUND("Receivable not found", 404),
    RESERVATION_NOT_FOUND("Stock reservation not found", 404),
    TRANSFER_ORDER_NOT_FOUND("Transfer order not found", 404),
    /** The SKU has no inventory item yet: its logistics data must be completed first (docs 01 BR-03). */
    INVENTORY_ITEM_NOT_FOUND("No inventory item for this SKU", 404),

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

    // ---- 409: the request is fine, the current state is not
    PO_COMMUNICATION_NOT_CONFIGURED("Buyer contact and receiving address must be configured before sending", 409),
    CONFLICT("Conflicting state", 409),
    CHECKOUT_PRICE_CHANGED("The selling price changed; review the basket and confirm again", 409),
    COUNT_POLICY_CONFLICT("Reconcile the active count and SKU tracking policy before proceeding",409),
    DESIGN_QUOTE_REQUIRED("An accepted design quote matching this order line is required",409),
    INSUFFICIENT_STOCK("Not enough available stock", 409),
    /** The destination already holds this SKU and lot in another status (QC state does not merge). */
    STOCK_STATUS_MISMATCH("The destination holds this lot in a different status", 409),
    /** The location exists, but it, its shelf or its warehouse is BLOCKED, in MAINTENANCE or INACTIVE (issue #67). */
    LOCATION_NOT_USABLE("This location cannot take stock right now", 409),
    /** Docs 11 BR-01: stock changes warehouse only through a transfer order (SCRUM-459). */
    MOVE_ACROSS_WAREHOUSES("Stock moves to another warehouse only through a transfer order", 409),
    /** Four eyes: whoever asked for a stock adjustment may not decide it. */
    ADJUSTMENT_SELF_APPROVAL("A stock adjustment cannot be decided by the person who requested it", 409),
    INVALID_ADJUSTMENT_TRANSITION("This stock adjustment has already been decided", 409),
    INVALID_TRANSFER_TRANSITION("This transfer order cannot move to that status right now", 409),
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
    /** SCRUM-434: a SUBCONTRACT purchase order goes only to a supplier flagged print subcontractor. */
    SUPPLIER_NOT_SUBCONTRACTOR("This supplier is not a print subcontractor", 409),
    /** BR-PRD-08: a new design goes to print only after the customer approved its sample. */
    DESIGN_SAMPLE_NOT_APPROVED("A design on this order has no approved sample yet", 409),
    /** BR-PRD-09: a deposit order goes to production only once the deposit has arrived. */
    ORDER_DEPOSIT_NOT_RECEIVED("The deposit for this order has not arrived yet", 409),
    /** SCRUM-460: from IN_PRODUCTION on, a customer asks and Sales or the coordinator decides. */
    ORDER_NOT_CANCELLABLE("This order can no longer be cancelled; raise a return instead", 409),
    ORDER_CANCELLATION_REQUEST_PENDING("A cancellation request for this order is already waiting for a decision", 409),
    ORDER_CANCELLATION_REQUEST_NOT_PENDING("This cancellation request has already been decided", 409),
    PAYMENT_TERM_NOT_ALLOWED("This payment term is not available for this order", 409),
    /** SCRUM-427: credit approval or refusal on an order that is not waiting for one. */
    ORDER_NOT_ON_CREDIT_HOLD("This order is not waiting for a credit decision", 409),
    /** SCRUM-431, kltn-docs 15 BR-07: one bank statement line is recorded once. */
    TRANSFER_REFERENCE_ALREADY_RECORDED("This transfer was already recorded", 409),
    ALLOCATION_CUSTOMER_MISMATCH("This receivable belongs to another customer", 409),
    /** SCRUM-431, kltn-docs 15 §4.3 step 4: an overdue customer gets no new credit until they pay. */
    CUSTOMER_CREDIT_OVERDUE("The customer has overdue receivables", 409),
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
    INVALID_SUPPLIER_CONFIRMATION("This supplier confirmation cannot be recorded", 409),
    PRODUCT_CODE_ALREADY_EXISTS("A product with this code already exists", 409),
    BRAND_CODE_ALREADY_EXISTS("A brand with this code or slug already exists", 409),
    CATEGORY_CODE_ALREADY_EXISTS("A category with this code or slug already exists", 409),
    VARIANT_SKU_ALREADY_EXISTS("A variant with this SKU or attribute combination already exists", 409),
    INVALID_VARIANT_TRANSITION("This variant cannot move to that status right now", 409),
    INVALID_PRODUCT_STATUS_TRANSITION("This product cannot move to that status right now", 409),
    SELF_APPROVAL_NOT_ALLOWED("A product cannot be approved by the person who submitted it", 409),
    CUSTOMER_EMAIL_ALREADY_EXISTS("A customer account with this email already exists", 409),

    /** Checkout needs somewhere to deliver: no shipping address was chosen and none is the default. */
    SHIPPING_ADDRESS_REQUIRED("Add a shipping address before placing an order", 409),

    /** Deployment rollout gate for guest checkout; see application.yml. */
    GUEST_CHECKOUT_DISABLED("Guest checkout is not available", 403),
    INVALID_PURCHASE_ORDER_TRANSITION("This purchase order cannot move to that status right now", 409),
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
