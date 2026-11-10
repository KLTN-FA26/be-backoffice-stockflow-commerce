package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * A carton damaged outside taken out of the order at the check station (kltn-docs 08 §4.2 step 4). The body is optional.
 *
 * @param note what is wrong with it (dented, torn, wet), kept on the carton for whoever puts it into quarantine
 */
public record CartonRemovalRequest(@Size(max = 500) String note) {
}
