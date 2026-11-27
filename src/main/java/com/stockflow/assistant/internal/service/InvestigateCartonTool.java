package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import org.springframework.stereotype.Component;

/**
 * A carton nobody can find, investigated (contract 3.2) - the chat's way to the same answer as the button "Tìm thùng
 * này": "thùng LPN-… đâu rồi?". Over {@link CartonInvestigation}, under the carton lookup's guard, as
 * {@link LookupScanTool}: the carton is found first and then refused for another warehouse, as the screen answers 404
 * or 403. Its answer is the investigation's own record, built by the assistant field by field.
 *
 * <p>The ranking of where to look is fixed by rules, not by the model: the description tells the model to quote the
 * places in their order and their reasons, and never to add a place of its own.</p>
 */
@Component
class InvestigateCartonTool implements AssistantTool {

    private static final ToolSpec SPEC = new ToolSpec("investigateCarton",
            "Where a missing carton may be, by its LPN (LPN-...): the carton (SKU, status, stock status, where the "
                    + "books last put it, warehouse), its history on the stock ledger newest first (at most 30 lines: "
                    + "when, movement type, from, to, what it was for, by whom), the open work naming it (pick task, "
                    + "carton check, putaway task, count, adjustment waiting for approval, return receipt), and "
                    + "whereToLook: places in the order to look, each with why (LAST_RECORDED where the books put it, "
                    + "PUTAWAY_TARGET the bin a putaway trip assigned it, STAGING_AREA where its pick task sends "
                    + "cartons, QUARANTINE, NEIGHBOUR_BIN a bin beside the last one, RECEIVING_AREA). Quote the places "
                    + "in their order with their reasons; never add a place that is not listed. timelineWithheld "
                    + "BLIND_COUNT means a count holds the carton and the user may not see its books: say so, and "
                    + "do not guess the history; NO_PERMISSION means the user may not read the stock ledger. An LPN "
                    + "that is no carton comes back NOT_FOUND: say it was not found and should be scanned again. For a "
                    + "label that may be a bin or a product barcode use lookupScan.",
            ToolSchema.object()
                    .string("lpn", "The carton's LPN as scanned or typed, whole")
                    .build());

    private final CartonInvestigation investigation;

    InvestigateCartonTool(CartonInvestigation investigation) {
        this.investigation = investigation;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/inventory/stock-packages/{lpn}";
    }

    @Override
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.STOCK_PACKAGES, action = Action.READ)
    public CartonInvestigation.Investigation run(ToolArguments arguments) {
        return investigation.investigate(arguments.text("lpn", 64).strip());
    }
}
